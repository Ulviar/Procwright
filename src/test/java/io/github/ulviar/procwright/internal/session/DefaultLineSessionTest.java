/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class DefaultLineSessionTest {

    @Test
    void abandonedDecoderRuntimeAndErrorAreReportedOnceAfterProtocolCapacityIsReleased() throws Exception {
        for (Throwable lateFailure : List.of(
                new IllegalStateException("late line decoder runtime failure"),
                new AssertionError("late line decoder error"))) {
            assertAbandonedDecoderFailureIsIsolated(lateFailure);
        }
    }

    private static void assertAbandonedDecoderFailureIsIsolated(Throwable lateFailure) throws Exception {
        int initialCapacity = BoundedTaskRunner.PROTOCOL_CALLBACKS.availablePermits();
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger handlerCalls = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == lateFailure) {
                handlerCalls.incrementAndGet();
                handlerEntered.countDown();
                awaitUninterruptibly(releaseHandler);
            }
        });
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        DefaultLineSession lineSession = new DefaultLineSession(
                session(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())),
                LineSessionSettings.defaults().withResponseDecoder(reader -> {
                    decoderEntered.countDown();
                    awaitUninterruptibly(releaseDecoder);
                    if (lateFailure instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    throw (Error) lateFailure;
                }));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request =
                    caller.submit(() -> captureFailure(() -> lineSession.request("request", Duration.ofMillis(40))));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));

            LineSessionException timeout =
                    assertInstanceOf(LineSessionException.class, request.get(2, TimeUnit.SECONDS));
            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertEquals(initialCapacity - 1, BoundedTaskRunner.PROTOCOL_CALLBACKS.availablePermits());

            releaseDecoder.countDown();
            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(eventually(() -> BoundedTaskRunner.PROTOCOL_CALLBACKS.availablePermits() == initialCapacity));
            assertEquals(1, handlerCalls.get());
            Thread.sleep(25);
            assertEquals(1, handlerCalls.get());
        } finally {
            releaseDecoder.countDown();
            releaseHandler.countDown();
            lineSession.close();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        assertTrue(eventually(() -> BoundedTaskRunner.PROTOCOL_CALLBACKS.availablePermits() == initialCapacity));
    }

    @Test
    void blockingPublicExitContinuationObservesReleasedOutputCleanupOwners() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultLineSession lineSession =
                new DefaultLineSession(session(process, dispatcher), LineSessionSettings.defaults());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean cleanupWasComplete = new AtomicBoolean();
        CompletableFuture<Void> continuation = lineSession.onExit().thenRun(() -> {
            cleanupWasComplete.set(lineSession.physicalOutputCleanup().isDone()
                    && lineSession.outputCleanupCompleted()
                    && dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0);
            continuationEntered.countDown();
            awaitUninterruptibly(releaseContinuation);
        });
        FutureTask<Void> closeTask = new FutureTask<>(() -> {
            lineSession.close();
            return null;
        });
        Thread closeThread = new Thread(closeTask, "procwright-line-blocking-continuation-test");
        closeThread.setDaemon(true);
        closeThread.start();
        try {
            assertTrue(continuationEntered.await(1, TimeUnit.SECONDS));
            assertTrue(cleanupWasComplete.get());
            BoundedCloseDispatcher.Reservation fullCapacity = dispatcher.reserve(4);
            fullCapacity.release();
            assertFalse(continuation.isDone());
        } finally {
            releaseContinuation.countDown();
        }
        closeTask.get(1, TimeUnit.SECONDS);
        continuation.get(1, TimeUnit.SECONDS);
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void publicExitWaitsForFallbackOwnedPhysicalOutputClose() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("line stdout close starter failed");
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3, (name, task) -> {
            if (name.contains("stdout-close")) {
                throw startFailure;
            }
            return io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
        DefaultLineSession lineSession =
                new DefaultLineSession(session(process, dispatcher), LineSessionSettings.defaults());
        try {
            process.complete(0);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            assertFalse(lineSession.physicalOutputCleanup().isDone());
            assertFalse(lineSession.onExit().isDone(), "public helper exit must wait for fallback output settlement");

            stdout.releaseClose.countDown();
            lineSession.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(lineSession.physicalOutputCleanup().isDone());
            assertTrue(eventually(() -> dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0));
        } finally {
            stdout.releaseClose.countDown();
            process.complete(143);
            try {
                lineSession.close();
            } catch (RuntimeException | Error expectedTerminalFailure) {
                assertSame(startFailure, expectedTerminalFailure);
            }
        }
    }

    @Test
    void fallbackHelperTerminalContinuationCannotStrandAnotherLineSessionTerminal() throws Exception {
        BoundedCloseDispatcher dispatcher = outputStartFailingDispatcher(6);
        ControllableProcess firstProcess = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        ControllableProcess secondProcess = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultLineSession first =
                new DefaultLineSession(session(firstProcess, dispatcher), LineSessionSettings.defaults());
        DefaultLineSession second =
                new DefaultLineSession(session(secondProcess, dispatcher), LineSessionSettings.defaults());
        CompletableFuture<Void> escape = new CompletableFuture<>();
        CompletableFuture<Void> secondTerminal = second.onExit().handle((ignored, failure) -> null);
        CountDownLatch firstContinuationEntered = new CountDownLatch(1);
        CompletableFuture<Void> firstContinuation = first.onExit().handle((ignored, failure) -> {
            firstContinuationEntered.countDown();
            CompletableFuture.anyOf(secondTerminal, escape).join();
            return null;
        });
        try {
            firstProcess.complete(0);
            assertTrue(firstContinuationEntered.await(1, TimeUnit.SECONDS));

            secondProcess.complete(0);

            secondTerminal.get(1, TimeUnit.SECONDS);
            firstContinuation.get(1, TimeUnit.SECONDS);
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        } finally {
            escape.complete(null);
            firstProcess.complete(143);
            secondProcess.complete(143);
            closeIgnoringTerminal(first);
            closeIgnoringTerminal(second);
        }
    }

    private enum CallbackExit {
        RUNTIME_EXCEPTION,
        ERROR,
        TIMEOUT,
        CANCELLATION
    }

    private enum Abandonment {
        TIMEOUT,
        SESSION_CLOSE,
        CALLER_INTERRUPT
    }

    @Test
    void responseDurationUsesInjectedMonotonicTimeAndClampsBackwardReadings() throws Exception {
        AtomicLong nanoTime = new AtomicLong(100);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        DefaultLineSession lineSession = new DefaultLineSession(
                session(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())),
                LineSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                (limiter, threadPrefix, deadlineNanos, handoff, task) ->
                        BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task),
                transition -> {},
                () -> nanoTime.getAndSet(50));
        try {
            LineResponse response = lineSession.request("request", Duration.ofSeconds(1));

            assertEquals(Duration.ZERO, response.elapsed());
        } finally {
            lineSession.close();
            assertTrue(stdin.awaitClosed());
            stdout.close();
        }
    }

    @Test
    void responseReaderIsThreadConfinedAndExpiresWithItsDecoderInvocation() throws Exception {
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        AtomicReference<ResponseDecoder.Reader> retained = new AtomicReference<>();
        AtomicReference<Throwable> crossThreadFailure = new AtomicReference<>();
        AtomicBoolean firstDecode = new AtomicBoolean(true);
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            if (firstDecode.getAndSet(false)) {
                retained.set(reader);
                Thread foreign = new Thread(
                        () -> crossThreadFailure.set(captureFailure(reader::readLine)),
                        "procwright-line-reader-scope-test");
                foreign.setDaemon(true);
                foreign.start();
                try {
                    foreign.join(TimeUnit.SECONDS.toMillis(1));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while joining the reader-scope test", exception);
                }
                assertFalse(foreign.isAlive());
                assertTrue(crossThreadFailure.get() instanceof IllegalStateException);
            }
            return List.of(reader.readLine());
        });

        try (DefaultLineSession lineSession = new DefaultLineSession(session(process), settings)) {
            assertEquals("ok", lineSession.request("first").text());

            assertThrows(IllegalStateException.class, retained.get()::readLine);
            assertEquals("ok", lineSession.request("second").text());
            assertEquals("first\nsecond\n", stdin.writtenText());
        } finally {
            stdout.close();
        }
    }

    @ParameterizedTest
    @EnumSource(CallbackExit.class)
    void escapedResponseReaderExpiresAfterEveryDecoderExitBeforeQueueMutation(CallbackExit exit) throws Exception {
        ResponseInputStream stdout = new ResponseInputStream();
        stdout.publish("queued\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicReference<ResponseDecoder.Reader> retained = new AtomicReference<>();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        RuntimeException runtimeFailure = new IllegalStateException("decoder runtime failure");
        AssertionError fatalFailure = new AssertionError("decoder fatal failure");
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            retained.set(reader);
            callbackStarted.countDown();
            try {
                switch (exit) {
                    case RUNTIME_EXCEPTION -> throw runtimeFailure;
                    case ERROR -> throw fatalFailure;
                    case TIMEOUT, CANCELLATION -> awaitUninterruptibly(releaseCallback);
                }
                return List.of();
            } finally {
                callbackFinished.countDown();
            }
        });
        DefaultLineSession lineSession = new DefaultLineSession(
                session(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())), settings);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> outcome = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> lineSession.request(
                        "request", exit == CallbackExit.TIMEOUT ? Duration.ofMillis(50) : Duration.ofDays(1)));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
            if (exit == CallbackExit.CANCELLATION) {
                Objects.requireNonNull(callerThread.get(), "callerThread").interrupt();
            }

            Throwable requestFailure = outcome.get(2, TimeUnit.SECONDS);
            switch (exit) {
                case RUNTIME_EXCEPTION -> {
                    LineSessionException typed = assertInstanceOf(LineSessionException.class, requestFailure);
                    assertEquals(LineSessionException.Reason.DECODER_FAILED, typed.reason());
                    assertSame(runtimeFailure, typed.getCause());
                }
                case ERROR -> assertSame(fatalFailure, requestFailure);
                case TIMEOUT ->
                    assertEquals(
                            LineSessionException.Reason.TIMEOUT,
                            assertInstanceOf(LineSessionException.class, requestFailure)
                                    .reason());
                case CANCELLATION -> {
                    assertEquals(
                            LineSessionException.Reason.FAILURE,
                            assertInstanceOf(LineSessionException.class, requestFailure)
                                    .reason());
                    assertTrue(callerInterruptRestored.get());
                }
            }

            releaseCallback.countDown();
            assertTrue(callbackFinished.await(1, TimeUnit.SECONDS));
            int pendingBeforeEscapedRead = lineSession.pendingStdoutLineCount();

            assertThrows(IllegalStateException.class, retained.get()::readLine);

            assertEquals(pendingBeforeEscapedRead, lineSession.pendingStdoutLineCount());
            assertEquals("request\n", stdin.toString(StandardCharsets.UTF_8));
        } finally {
            releaseCallback.countDown();
            lineSession.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(Abandonment.class)
    void decoderInterruptObservesExpiredReaderBeforeQueueMutation(Abandonment abandonment) throws Exception {
        ResponseInputStream stdout = new ResponseInputStream();
        stdout.publish("queued\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch capabilityAttempted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Throwable> capabilityFailure = new AtomicReference<>();
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            callbackStarted.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("line decoder was not interrupted after abandonment");
            } catch (InterruptedException expected) {
                capabilityFailure.set(captureFailure(reader::readLine));
                capabilityAttempted.countDown();
                awaitUninterruptibly(releaseCallback);
                return List.of();
            }
        });
        DefaultLineSession lineSession = new DefaultLineSession(
                session(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())), settings);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> outcome = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> lineSession.request(
                        "request", abandonment == Abandonment.TIMEOUT ? Duration.ofMillis(250) : Duration.ofDays(1)));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            switch (abandonment) {
                case TIMEOUT -> {}
                case SESSION_CLOSE -> lineSession.close();
                case CALLER_INTERRUPT ->
                    Objects.requireNonNull(callerThread.get(), "callerThread").interrupt();
            }

            assertTrue(capabilityAttempted.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, capabilityFailure.get());
            assertEquals("request\n", stdin.toString(StandardCharsets.UTF_8));

            Throwable requestFailure = outcome.get(2, TimeUnit.SECONDS);
            LineSessionException typed = assertInstanceOf(LineSessionException.class, requestFailure);
            assertEquals(
                    switch (abandonment) {
                        case TIMEOUT -> LineSessionException.Reason.TIMEOUT;
                        case SESSION_CLOSE -> LineSessionException.Reason.CLOSED;
                        case CALLER_INTERRUPT -> LineSessionException.Reason.FAILURE;
                    },
                    typed.reason());
            if (abandonment == Abandonment.SESSION_CLOSE) {
                assertInstanceOf(BoundedTaskRunner.TaskCancelledException.class, typed.getCause());
            }
            assertEquals(abandonment == Abandonment.CALLER_INTERRUPT, callerInterruptRestored.get());
        } finally {
            releaseCallback.countDown();
            lineSession.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void decoderTimeoutIsSelectedBeforeItsInterruptDerivedFailure() throws Exception {
        ResponseInputStream stdout = new ResponseInputStream();
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        CountDownLatch decoderFinished = new CountDownLatch(1);
        AtomicReference<LineSessionException> decoderFailure = new AtomicReference<>();
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            try {
                return List.of(reader.readLine());
            } catch (LineSessionException failure) {
                decoderFailure.set(failure);
                throw failure;
            } finally {
                decoderFinished.countDown();
            }
        });
        DefaultLineSession lineSession = new DefaultLineSession(
                session(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())), settings);
        try {
            LineSessionException timeout = assertThrows(
                    LineSessionException.class, () -> lineSession.request("request", Duration.ofMillis(100)));

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertTrue(decoderFinished.await(1, TimeUnit.SECONDS), "decoder did not observe abandonment");
            assertSame(timeout, decoderFailure.get());
            assertEquals(
                    0,
                    Stream.of(timeout.getSuppressed())
                            .filter(LineSessionException.class::isInstance)
                            .map(LineSessionException.class::cast)
                            .filter(failure -> failure.reason() == LineSessionException.Reason.TIMEOUT)
                            .count());
        } finally {
            lineSession.close();
            stdout.close();
        }
    }

    @Test
    void serializedWaiterTimeoutBeforeAdmissionLeavesLineSessionReusableAndWritesNothing() throws Exception {
        assertRecoverableSerializedWaiterFailure(false);
    }

    @Test
    void serializedWaiterInterruptionBeforeAdmissionLeavesLineSessionReusableAndRestoresInterrupt() throws Exception {
        assertRecoverableSerializedWaiterFailure(true);
    }

    private static void assertRecoverableSerializedWaiterFailure(boolean interrupt) throws Exception {
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        AtomicInteger responses = new AtomicInteger();
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            if (responses.getAndIncrement() == 0) {
                firstResponseStarted.countDown();
                awaitUninterruptibly(releaseFirstResponse);
            }
            return List.of(reader.readLine());
        });
        ControlledRequestLockWaiter lockWaiter = new ControlledRequestLockWaiter();
        DefaultLineSession lineSession = new DefaultLineSession(
                session(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())),
                settings,
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                (limiter, threadPrefix, deadlineNanos, handoff, task) ->
                        BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task),
                transition -> {},
                System::nanoTime,
                lockWaiter);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
        try {
            Future<LineResponse> active = executor.submit(() -> lineSession.request("active"));
            assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));
            Future<Throwable> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> lineSession.request("not-admitted"));
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(lockWaiter.awaitContended(), "second request did not enter the serialization wait");

            if (interrupt) {
                Objects.requireNonNull(waiterThread.get(), "waiterThread").interrupt();
            } else {
                lockWaiter.expire();
            }
            LineSessionException failure =
                    assertInstanceOf(LineSessionException.class, waiter.get(2, TimeUnit.SECONDS));

            assertEquals(
                    interrupt ? LineSessionException.Reason.FAILURE : LineSessionException.Reason.TIMEOUT,
                    failure.reason());
            if (interrupt) {
                assertEquals("Interrupted while waiting to start line request", failure.getMessage());
                assertInstanceOf(InterruptedException.class, failure.getCause());
            } else {
                assertEquals("Line request timed out", failure.getMessage());
                assertNull(failure.getCause());
            }
            assertEquals(interrupt, waiterInterruptRestored.get());
            assertEquals("active\n", stdin.writtenText());
            assertFalse(lineSession.onExit().isDone());

            releaseFirstResponse.countDown();
            assertEquals("ok", active.get(2, TimeUnit.SECONDS).text());
            assertEquals("ok", lineSession.request("retry").text());
            assertEquals("active\nretry\n", stdin.writtenText());
            assertFalse(lineSession.onExit().isDone());
        } finally {
            releaseFirstResponse.countDown();
            lockWaiter.expire();
            lineSession.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void fatalErrorSelectedBeforeSerializedWaiterInterruptionWinsByIdentityAndRestoresInterrupt() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(true, true);
    }

    @Test
    void closedSelectedBeforeSerializedWaiterInterruptionWinsAndRestoresInterrupt() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(false, true);
    }

    @Test
    void fatalErrorSelectedBeforeSerializedWaiterTimeoutWinsByIdentity() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(true, false);
    }

    @Test
    void closedSelectedBeforeSerializedWaiterTimeoutWins() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(false, false);
    }

    private static void assertSelectedTerminalWinsSerializedWaiterFailure(boolean fatal, boolean interrupt)
            throws Exception {
        AssertionError fatalError = new AssertionError("fatal output failure selected while line request waits");
        InputStream stdout = fatal ? new GatedErrorInputStream(fatalError) : new BlockingUntilClosedInputStream();
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            responseStarted.countDown();
            awaitUninterruptibly(releaseResponse);
            return List.of(reader.readLine());
        });
        ControlledRequestLockWaiter lockWaiter = new ControlledRequestLockWaiter();
        DefaultLineSession lineSession = new DefaultLineSession(
                session(process),
                settings,
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                (limiter, threadPrefix, deadlineNanos, handoff, task) ->
                        BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task),
                transition -> {},
                System::nanoTime,
                lockWaiter);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> active = executor.submit(() -> captureFailure(() -> lineSession.request("active")));
            assertTrue(responseStarted.await(1, TimeUnit.SECONDS));
            Future<Throwable> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> lineSession.request("waiter"));
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(lockWaiter.awaitContended(), "second request did not enter the serialization wait");

            if (fatal) {
                ((GatedErrorInputStream) stdout).releaseFailure();
                lineSession.onExit().get(2, TimeUnit.SECONDS);
            } else {
                lineSession.close();
            }
            int fatalSuppressedBeforeWaiter = fatalError.getSuppressed().length;

            if (interrupt) {
                Objects.requireNonNull(waiterThread.get(), "waiterThread").interrupt();
            } else {
                lockWaiter.expire();
            }
            Throwable waiterFailure = waiter.get(2, TimeUnit.SECONDS);

            if (fatal) {
                assertSame(fatalError, waiterFailure);
                assertEquals(fatalSuppressedBeforeWaiter, fatalError.getSuppressed().length);
            } else {
                LineSessionException closed = assertInstanceOf(LineSessionException.class, waiterFailure);
                assertEquals(LineSessionException.Reason.CLOSED, closed.reason());
            }
            assertEquals(interrupt, waiterInterruptRestored.get());
            assertEquals("active\n", stdin.toString(StandardCharsets.UTF_8));

            releaseResponse.countDown();
            Throwable activeFailure = active.get(2, TimeUnit.SECONDS);
            if (fatal) {
                assertSame(fatalError, activeFailure);
            } else {
                assertEquals(
                        LineSessionException.Reason.CLOSED,
                        assertInstanceOf(LineSessionException.class, activeFailure)
                                .reason());
            }
            Throwable followUp = captureFailure(() -> lineSession.request("follow-up"));
            if (fatal) {
                assertSame(fatalError, followUp);
            } else {
                assertEquals(
                        LineSessionException.Reason.CLOSED,
                        assertInstanceOf(LineSessionException.class, followUp).reason());
            }
            assertEquals("active\n", stdin.toString(StandardCharsets.UTF_8));
        } finally {
            releaseResponse.countDown();
            lockWaiter.expire();
            lineSession.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void writeAdmissionTimeoutLeavesSessionOpenAndCannotWriteLate() throws Exception {
        BoundedTaskRunner.Limiter limiter = BoundedTaskRunner.BLOCKING_WRITES;
        int capacity = limiter.availablePermits();
        assertEquals(32, capacity, "another test leaked a production line-write permit");
        CountDownLatch callbacksStarted = new CountDownLatch(capacity);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        ExecutorService occupiers = Executors.newFixedThreadPool(capacity);
        List<DefaultLineSession> occupyingSessions = new ArrayList<>(capacity);
        List<BlockingReplyOutputStream> occupyingStdinStreams = new ArrayList<>(capacity);
        List<Future<LineResponse>> occupied = new ArrayList<>(capacity);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults());
        try {
            for (int index = 0; index < capacity; index++) {
                ResponseInputStream occupyingStdout = new ResponseInputStream();
                BlockingReplyOutputStream occupyingStdin =
                        new BlockingReplyOutputStream(occupyingStdout, callbacksStarted, releaseCallbacks);
                ControllableProcess occupyingProcess =
                        new ControllableProcess(occupyingStdin, occupyingStdout, InputStream.nullInputStream());
                DefaultLineSession occupyingSession =
                        new DefaultLineSession(session(occupyingProcess), LineSessionSettings.defaults());
                occupyingStdinStreams.add(occupyingStdin);
                occupyingSessions.add(occupyingSession);
                occupied.add(occupiers.submit(() -> occupyingSession.requestEncoded(
                        "occupy\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10))));
            }
            assertTrue(callbacksStarted.await(5, TimeUnit.SECONDS), "line-write callbacks did not occupy all permits");

            LineSessionException timeout = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "never-written\n".getBytes(StandardCharsets.UTF_8), Duration.ofMillis(50)));

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertEquals(0, stdin.writeCalls());
            assertFalse(lineSession.onExit().isDone());

            releaseCallbacks.countDown();
            for (Future<LineResponse> task : occupied) {
                assertEquals("ok", task.get(5, TimeUnit.SECONDS).text());
            }
            BoundedTaskRunner.run(
                    limiter, "procwright-line-write-saturation-barrier-", deadline(Duration.ofSeconds(1)), () -> null);
            assertEquals(0, stdin.writeCalls(), "timed-out request wrote after writer capacity became available");

            LineResponse response =
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1));
            assertEquals("ok", response.text());
            assertEquals(1, stdin.writeCalls());
            assertEquals("retry\n", stdin.writtenText());
        } finally {
            releaseCallbacks.countDown();
            try {
                lineSession.close();
            } finally {
                occupyingSessions.forEach(DefaultLineSession::close);
                assertTrue(stdin.awaitClosed());
                for (BlockingReplyOutputStream occupyingStdin : occupyingStdinStreams) {
                    assertTrue(occupyingStdin.awaitClosed());
                }
                stdout.close();
                occupiers.shutdownNow();
                assertTrue(occupiers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(capacity, limiter.availablePermits());
    }

    @Test
    void callerInterruptWhileWriteAdmissionIsSaturatedIsRetrySafeAndRestoresState() throws Exception {
        BoundedTaskRunner.Limiter limiter = BoundedTaskRunner.BLOCKING_WRITES;
        int capacity = limiter.availablePermits();
        assertEquals(32, capacity, "another test leaked a production line-write permit");
        List<BoundedTaskRunner.Permit> reservations = new ArrayList<>(capacity);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        CountDownLatch writeAdmissionAttempted = new CountDownLatch(1);
        DefaultLineSession lineSession = new DefaultLineSession(
                session(process),
                LineSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                (writeLimiter, threadPrefix, deadlineNanos, handoff, task) -> {
                    writeAdmissionAttempted.countDown();
                    BoundedTaskRunner.runTracked(writeLimiter, threadPrefix, deadlineNanos, handoff, task);
                },
                transition -> {});
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread caller = new Thread(
                () -> {
                    observedFailure.set(captureFailure(() -> lineSession.requestEncoded(
                            "never-written\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30))));
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                },
                "procwright-interrupted-line-caller-test");
        caller.setDaemon(true);
        try {
            for (int index = 0; index < capacity; index++) {
                reservations.add(limiter.acquire(deadline(Duration.ofSeconds(1))));
            }

            caller.start();
            assertTrue(writeAdmissionAttempted.await(5, TimeUnit.SECONDS), "caller did not begin write admission");
            assertTrue(lineSession.hasActiveRequestForTest());
            assertEquals(0, limiter.availablePermits());
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(caller.isAlive(), "interrupted caller did not return before the watchdog");
            assertTrue(observedFailure.get() instanceof LineSessionException);
            LineSessionException interrupted = (LineSessionException) observedFailure.get();
            assertEquals(LineSessionException.Reason.FAILURE, interrupted.reason());
            assertTrue(interrupted.getCause() instanceof InterruptedException);
            assertTrue(interruptRestored.get());
            assertEquals(0, stdin.writeCalls());
            assertFalse(lineSession.hasActiveRequestForTest());
            assertFalse(lineSession.onExit().isDone());
            assertEquals(0, limiter.availablePermits());

            reservations.forEach(BoundedTaskRunner.Permit::close);
            BoundedTaskRunner.run(
                    limiter, "procwright-interrupted-line-barrier-", deadline(Duration.ofSeconds(5)), () -> null);
            assertEquals(0, stdin.writeCalls(), "interrupted request wrote after admission capacity was restored");

            LineResponse response =
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
            assertEquals("ok", response.text());
            assertEquals("retry\n", stdin.writtenText());
            assertFalse(lineSession.hasActiveRequestForTest());
            assertEquals(capacity, limiter.availablePermits());
        } finally {
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));
            reservations.forEach(BoundedTaskRunner.Permit::close);
            try {
                lineSession.close();
            } finally {
                assertTrue(stdin.awaitClosed());
                stdout.close();
            }
        }
        assertEquals(capacity, limiter.availablePermits());
    }

    @Test
    void threadStartRejectionIsTypedRetrySafeAndLeavesLineSessionReusable() throws Exception {
        BoundedTaskRunner.Limiter limiter = BoundedTaskRunner.BLOCKING_WRITES;
        int capacity = limiter.availablePermits();
        assertEquals(32, capacity, "another test leaked a production line-write permit");
        SecurityException rejection = new SecurityException("line writer start denied");
        AtomicBoolean rejectNextStart = new AtomicBoolean(true);
        AtomicInteger threadSequence = new AtomicInteger();
        AtomicReference<Thread> rejectedThread = new AtomicReference<>();
        BoundedTaskRunner.TaskThreadFactory threadFactory = (threadPrefix, task) -> {
            Thread thread;
            if (rejectNextStart.compareAndSet(true, false)) {
                thread = new Thread(task, threadPrefix + threadSequence.getAndIncrement()) {
                    @Override
                    public synchronized void start() {
                        super.start();
                        throw rejection;
                    }
                };
                rejectedThread.set(thread);
            } else {
                thread = new Thread(task, threadPrefix + threadSequence.getAndIncrement());
            }
            thread.setDaemon(true);
            return thread;
        };
        DefaultLineSession.WriteTaskRunner taskRunner = (writeLimiter, threadPrefix, deadlineNanos, handoff, task) ->
                BoundedTaskRunner.runTracked(writeLimiter, threadPrefix, deadlineNanos, handoff, threadFactory, task);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultLineSession lineSession = new DefaultLineSession(
                session(process),
                LineSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                taskRunner,
                transition -> {});
        try {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "never-written\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10)));

            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertSame(rejection, failure.getCause());
            rejectedThread.get().join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(rejectedThread.get().isAlive());
            assertEquals(0, stdin.writeCalls());
            assertFalse(lineSession.hasActiveRequestForTest());
            assertFalse(lineSession.onExit().isDone());
            assertEquals(capacity, limiter.availablePermits());

            LineResponse response =
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
            assertEquals("ok", response.text());
            assertEquals("retry\n", stdin.writtenText());
            assertFalse(lineSession.hasActiveRequestForTest());
        } finally {
            try {
                lineSession.close();
            } finally {
                assertTrue(stdin.awaitClosed());
                stdout.close();
            }
        }
        assertEquals(capacity, limiter.availablePermits());
    }

    @Test
    void failuresImmediatelyAfterRequestOwnershipAcquisitionReleaseEveryGuard() throws Exception {
        for (DefaultLineSession.RequestTransition failingTransition : DefaultLineSession.RequestTransition.values()) {
            for (boolean fatal : List.of(false, true)) {
                ResponseInputStream stdout = new ResponseInputStream();
                ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
                ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
                Throwable injected = fatal
                        ? new AssertionError("fatal failure after " + failingTransition)
                        : new IllegalStateException("runtime failure after " + failingTransition);
                AtomicBoolean injectOnce = new AtomicBoolean(true);
                DefaultLineSession lineSession = new DefaultLineSession(
                        session(process),
                        LineSessionSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        (limiter, threadPrefix, deadlineNanos, handoff, task) ->
                                BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task),
                        transition -> {
                            if (transition == failingTransition && injectOnce.compareAndSet(true, false)) {
                                if (injected instanceof RuntimeException runtimeException) {
                                    throw runtimeException;
                                }
                                throw (Error) injected;
                            }
                        });
                try {
                    Throwable failure = captureFailure(() -> lineSession.requestEncoded(
                            "never-written\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10)));

                    assertSame(injected, failure);
                    assertEquals(0, stdin.writeCalls());
                    assertFalse(lineSession.hasActiveRequestForTest());
                    assertFalse(lineSession.onExit().isDone());

                    LineResponse response = lineSession.requestEncoded(
                            "retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
                    assertEquals("ok", response.text());
                    assertEquals("retry\n", stdin.writtenText());
                    assertFalse(lineSession.hasActiveRequestForTest());
                } finally {
                    try {
                        lineSession.close();
                    } finally {
                        assertTrue(stdin.awaitClosed());
                        stdout.close();
                    }
                }
            }
        }
    }

    @Test
    void callerInterruptAfterControlledPartialWriteClosesSessionAndPreservesTypedFailure() throws Exception {
        BoundedTaskRunner.Limiter limiter = BoundedTaskRunner.BLOCKING_WRITES;
        int baselineCapacity = limiter.availablePermits();
        assertEquals(32, baselineCapacity, "another test leaked a production line-write permit");
        BlockingAfterFirstByteOutputStream stdin = new BlockingAfterFirstByteOutputStream();
        BlockingUntilClosedInputStream stdout = new BlockingUntilClosedInputStream();
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        CountDownLatch writerWrapperCompleted = new CountDownLatch(1);
        BoundedTaskRunner.TaskThreadFactory threadFactory = (threadPrefix, task) -> {
            Thread thread = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            writerWrapperCompleted.countDown();
                        }
                    },
                    threadPrefix + "controlled");
            thread.setDaemon(true);
            return thread;
        };
        DefaultLineSession lineSession = new DefaultLineSession(
                rawSession,
                LineSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                (writeLimiter, threadPrefix, deadlineNanos, handoff, task) -> BoundedTaskRunner.runTracked(
                        writeLimiter, threadPrefix, deadlineNanos, handoff, threadFactory, task),
                transition -> {});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> requestCaller = new AtomicReference<>();
        try {
            Future<Throwable> request = executor.submit(() -> {
                requestCaller.set(Thread.currentThread());
                return captureFailure(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30)));
            });
            assertTrue(stdin.awaitFirstByte(), "writer did not reach the controlled partial-write boundary");
            requestCaller.get().interrupt();

            Throwable thrown = request.get(10, TimeUnit.SECONDS);
            assertTrue(thrown instanceof LineSessionException);
            LineSessionException interrupted = (LineSessionException) thrown;
            assertEquals(LineSessionException.Reason.FAILURE, interrupted.reason());
            assertTrue(interrupted.getCause() instanceof InterruptedException);
            assertArrayEquals(new byte[] {'r'}, stdin.writtenBytes());
            assertTrue(stdin.awaitWriterStopped());
            assertTrue(stdin.wasInterrupted());
            assertTrue(
                    writerWrapperCompleted.await(5, TimeUnit.SECONDS),
                    "writer wrapper did not complete after the delegate returned");
            assertEquals(
                    baselineCapacity,
                    limiter.availablePermits(),
                    "partial-write interrupt did not return the write permit after full wrapper completion");
            lineSession.onExit().get(1, TimeUnit.SECONDS);

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
            assertEquals(1, stdin.writeCalls());
        } finally {
            stdin.releaseWriter();
            try {
                lineSession.close();
            } finally {
                assertTrue(stdin.awaitClosed());
                stdout.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
        assertEquals(baselineCapacity, limiter.availablePermits());
    }

    @Test
    void eagerDecoderRuntimeFailuresAreTypedBeforeClaimAndLeaveRawSessionOpen() {
        for (int failingCreation : List.of(1, 2)) {
            for (Throwable cause : List.of(
                    new IllegalArgumentException("decoder creation " + failingCreation + " failed"),
                    new CoderMalfunctionError(new BufferUnderflowException()))) {
                DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
                TrackingInputStream stdout = new TrackingInputStream();
                TrackingInputStream stderr = new TrackingInputStream();
                ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
                DefaultSession rawSession = session(process);
                try {
                    LineSessionException failure = assertThrows(
                            LineSessionException.class, () -> new DefaultLineSession(rawSession, options(charset)));

                    assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
                    assertSame(cause, failure.getCause());
                    assertEquals(failingCreation, charset.decoderCreations());
                    assertTrue(process.isAlive());
                    assertFalse(rawSession.onExit().isDone());
                    assertEquals(0, stdout.reads());
                    assertEquals(0, stderr.reads());
                } finally {
                    rawSession.close();
                }
            }
        }
    }

    @Test
    void eagerDecoderFatalErrorsPreserveIdentityAndLeaveRawSessionOpen() {
        for (int failingCreation : List.of(1, 2)) {
            AssertionError cause = new AssertionError("fatal decoder creation " + failingCreation);
            DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
            DefaultSession rawSession = session(process);
            try {
                AssertionError thrown =
                        assertThrows(AssertionError.class, () -> new DefaultLineSession(rawSession, options(charset)));

                assertSame(cause, thrown);
                assertEquals(failingCreation, charset.decoderCreations());
                assertTrue(process.isAlive());
                assertFalse(rawSession.onExit().isDone());
                assertEquals(0, stdout.reads());
                assertEquals(0, stderr.reads());
            } finally {
                rawSession.close();
            }
        }
    }

    @Test
    void delegateIllegalStateExceptionIsFailureAndWriterIsFailStopped() throws Exception {
        IllegalStateException writeFailure = new IllegalStateException("delegate state failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(writeFailure, followUp.getCause().getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }

    @Test
    void delegateErrorClosesSessionAndIsRethrownByIdentity() throws Exception {
        AssertionError writeFailure = new AssertionError("delegate invariant failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            AssertionError thrown = assertThrows(
                    AssertionError.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertSame(writeFailure, thrown);
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertSame(writeFailure, followUp.getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }

    @Test
    void fatalOutputDecoderErrorFailStopsActiveAndFollowUpRequestsForEitherStream() throws Exception {
        for (boolean fatalStdout : List.of(true, false)) {
            AssertionError fatalError =
                    new AssertionError("fatal " + (fatalStdout ? "stdout" : "stderr") + " decoder failure");
            LatchingFatalDecoderCharset charset = new LatchingFatalDecoderCharset(fatalError);
            GatedByteInputStream fatalStream = new GatedByteInputStream((byte) '!');
            BlockingUntilClosedInputStream blockedStdout = new BlockingUntilClosedInputStream();
            InputStream stdout = fatalStdout ? fatalStream : blockedStdout;
            InputStream stderr = fatalStdout ? InputStream.nullInputStream() : fatalStream;
            CountingOutputStream stdin = new CountingOutputStream();
            ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultLineSession lineSession =
                    new DefaultLineSession(rawSession, options(charset).withTranscriptLimit(32));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request = executor.submit(() -> captureFailure(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2))));
                assertTrue(stdin.awaitWrite(), "the request must be active before output decoding fails");
                fatalStream.releaseByte();
                assertTrue(charset.awaitBeforeFailure());
                charset.releaseFailure();

                assertSame(fatalError, request.get(2, TimeUnit.SECONDS));
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
                assertTrue(lineSession.transcript().text().length() <= 32);
                int writesAfterFailure = stdin.writeCalls();

                AssertionError followUp = assertThrows(
                        AssertionError.class,
                        () -> lineSession.requestEncoded(
                                "retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
                assertSame(fatalError, followUp);
                assertEquals(writesAfterFailure, stdin.writeCalls());
            } finally {
                fatalStream.releaseByte();
                charset.releaseFailure();
                try {
                    lineSession.close();
                } finally {
                    blockedStdout.close();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void userCloseWinningBeforePumpErrorStillRegistersTheErrorForPhysicalCloseFailures() throws Exception {
        AssertionError pumpError = new AssertionError("late line pump failure");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ControlledPumpFailureInputStream stdout = new ControlledPumpFailureInputStream(pumpError, stdoutCloseFailure);
        ControlledPumpFailureInputStream stderr = new ControlledPumpFailureInputStream(null, stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        DefaultSession rawSession = session(process);
        List<Thread> pumpThreads = new ArrayList<>();
        AtomicReference<Throwable> uncaughtPumpFailure = new AtomicReference<>();
        PumpStarter starter = (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> uncaughtPumpFailure.compareAndSet(null, failure));
            pumpThreads.add(thread);
            thread.start();
            return thread;
        };
        DefaultLineSession lineSession = new DefaultLineSession(
                rawSession, LineSessionSettings.defaults(), ZeroReadBackoff.exponential(), starter);
        try {
            assertTrue(stdout.awaitReadEntered());

            lineSession.close();
            assertTrue(stdout.awaitCloseEntered());
            assertTrue(stderr.awaitCloseEntered());

            stdout.releaseReadFailure();
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }
            assertEquals(null, uncaughtPumpFailure.get());

            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            assertTrue(stdout.awaitCloseWorkerStopped());
            assertTrue(stderr.awaitCloseWorkerStopped());
            ExecutionException cleanupFailure = assertThrows(
                    ExecutionException.class,
                    () -> rawSession.physicalOutputCleanup().get(1, TimeUnit.SECONDS));
            assertSame(stdoutCloseFailure, cleanupFailure.getCause());

            assertIdentitySuppressedOnce(pumpError, stdoutCloseFailure);
            assertIdentitySuppressedOnce(pumpError, stderrCloseFailure);
            assertEquals(2, pumpError.getSuppressed().length);
        } finally {
            stdout.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            lineSession.close();
            rawSession.close();
        }
    }

    @Test
    void responseLimitRemainsPrimaryWhenStderrDecoderFailsLater() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated(true);
    }

    @Test
    void stderrDecoderErrorRemainsPrimaryWhenResponseLimitFailsLater() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated(false);
    }

    @Test
    void overflowPrefixIsNeverPublishedWhenSameDecodeCallEndsMalformed() throws Exception {
        OverflowThenMalformedCharset charset = new OverflowThenMalformedCharset();
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                new ByteArrayInputStream(new byte[] {'x', 'y'}),
                InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession =
                new DefaultLineSession(rawSession, options(charset), ZeroReadBackoff.exponential())) {
            try {
                assertTrue(charset.awaitBeforeMalformed());
                assertEquals(0, lineSession.pendingStdoutLineCount());
            } finally {
                charset.releaseMalformed();
            }

            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
            assertEquals(0, lineSession.pendingStdoutLineCount());
            assertFalse(failure.transcript().text().contains("ok"));
            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    @Test
    void zeroLengthPumpsBackOffAndStopAfterCloseForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : InputStream.nullInputStream();
            InputStream stderr = zeroStdout ? InputStream.nullInputStream() : zeroStream;
            ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultLineSession lineSession =
                    new DefaultLineSession(rawSession, LineSessionSettings.defaults(), backoff);
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads(), "the pump must enter backoff before attempting another read");

                try {
                    lineSession.close();
                } finally {
                    backoff.release();
                }
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive(), "line pump thread must terminate after close");
                assertEquals(1, zeroStream.reads(), "close during backoff must prevent another read");
                assertFalse(process.isAlive());
            } finally {
                try {
                    backoff.release();
                } finally {
                    lineSession.close();
                }
            }
        }
    }

    @Test
    void interruptedZeroLengthPumpRestoresInterruptStatusBeforeStopping() throws Exception {
        ZeroForeverInputStream stdout = new ZeroForeverInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            assertTrue(stdout.awaitFirstRead());
            Thread readerThread = stdout.readerThread();

            readerThread.interrupt();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(readerThread.isAlive(), "interrupted line pump thread must terminate");
            assertTrue(readerThread.isInterrupted(), "line pump must restore its interrupted status");
            assertFalse(
                    lineSession.transcript().malformed(),
                    "interrupting zero-read backoff must not fabricate malformed output");
        }
    }

    private static void assertResponseLimitAndFatalErrorAreArbitrated(boolean responseFailureFirst) throws Exception {
        AssertionError fatalError = new AssertionError("fatal stderr decoder failure");
        RacingLineDecoderCharset charset = new RacingLineDecoderCharset(fatalError);
        GatedByteInputStream stdout = new GatedByteInputStream((byte) 'x');
        GatedByteInputStream stderr = new GatedByteInputStream((byte) '!');
        CountingOutputStream stdin = new CountingOutputStream();
        AtomicReference<LineSessionException> observedResponseFailure = new AtomicReference<>();
        CountDownLatch responseFailureCaught = new CountDownLatch(1);
        CountDownLatch responseLineClaimed = new CountDownLatch(1);
        CountDownLatch allowResponseLimitCheck = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        LineSessionSettings options = options(charset).withMaxResponseChars(1).withResponseDecoder(reader -> {
            try {
                reader.readLine();
                throw new AssertionError("response limit was not enforced");
            } catch (LineSessionException failure) {
                observedResponseFailure.set(failure);
                responseFailureCaught.countDown();
                awaitUninterruptibly(allowCallbackReturn);
                return List.of("fallback");
            }
        });
        ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
        DefaultSession rawSession = session(process);
        DefaultLineSession lineSession = new DefaultLineSession(
                rawSession,
                options,
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                (limiter, threadPrefix, deadlineNanos, handoff, task) ->
                        BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task),
                transition -> {},
                System::nanoTime,
                SerializedRequestGate.Waiter.timed(),
                transition -> {
                    responseLineClaimed.countDown();
                    if (!responseFailureFirst) {
                        awaitUninterruptibly(allowResponseLimitCheck);
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() ->
                    lineSession.requestEncoded("request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5))));
            assertTrue(stdin.awaitWrite(), "the request must be active before either output failure");

            stdout.releaseByte();
            assertTrue(charset.awaitResponseDecoder(), "stdout decoder did not reach its controlled boundary");
            charset.releaseResponseDecoder();
            if (responseFailureFirst) {
                assertTrue(
                        responseFailureCaught.await(1, TimeUnit.SECONDS),
                        "response limit must occupy the terminal outcome before stderr fails");
            } else {
                assertTrue(
                        responseLineClaimed.await(1, TimeUnit.SECONDS),
                        "the in-flight read must claim its line before the fatal outcome");
            }

            stderr.releaseByte();
            assertTrue(charset.awaitFatalDecoder(), "stderr decoder did not reach its controlled boundary");
            charset.releaseFatalDecoder();
            if (!responseFailureFirst) {
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
                allowResponseLimitCheck.countDown();
                assertTrue(
                        responseFailureCaught.await(1, TimeUnit.SECONDS),
                        "the already-started read must report its later response limit");
            }

            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            allowCallbackReturn.countDown();

            Throwable thrown = request.get(2, TimeUnit.SECONDS);
            LineSessionException responseFailure = observedResponseFailure.get();
            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
            if (responseFailureFirst) {
                assertSame(responseFailure, thrown);
                assertIdentitySuppressedOnce(responseFailure, fatalError);
            } else {
                assertSame(fatalError, thrown);
                assertIdentitySuppressedOnce(fatalError, responseFailure);
                assertFailureGraphDoesNotContain(responseFailure, fatalError);
            }

            int writesAfterFailure = stdin.writeCalls();
            Throwable followUp = captureFailure(() ->
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            if (responseFailureFirst) {
                assertTrue(followUp instanceof LineSessionException);
                assertEquals(
                        LineSessionException.Reason.RESPONSE_TOO_LARGE, ((LineSessionException) followUp).reason());
            } else {
                assertSame(fatalError, followUp);
            }
            assertEquals(writesAfterFailure, stdin.writeCalls());
        } finally {
            stdout.releaseByte();
            stderr.releaseByte();
            charset.releaseResponseDecoder();
            charset.releaseFatalDecoder();
            allowResponseLimitCheck.countDown();
            allowCallbackReturn.countDown();
            try {
                lineSession.close();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertIdentitySuppressedOnce(Throwable primary, Throwable expected) {
        int matches = 0;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected) {
                matches++;
            }
        }
        assertEquals(1, matches);
    }

    private static void assertFailureGraphDoesNotContain(Throwable root, Throwable forbidden) {
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        ArrayList<Throwable> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.remove(pending.size() - 1);
            assertTrue(current != forbidden, "failure graph must not contain a suppression cycle");
            if (visited.put(current, Boolean.TRUE) != null) {
                continue;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            pending.addAll(List.of(current.getSuppressed()));
        }
    }

    private static LineSessionSettings options(Charset charset) {
        return LineSessionSettings.defaults().withCharsetPolicy(CharsetPolicy.report(charset));
    }

    private static DefaultSession session(Process process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8);
    }

    private static DefaultSession session(Process process, BoundedCloseDispatcher closeDispatcher) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "line-cleanup-test", CommandEcho.empty()),
                () -> {},
                closeDispatcher,
                DefaultSession.WatcherStarter.threading());
    }

    private static BoundedCloseDispatcher outputStartFailingDispatcher(int capacity) {
        return new BoundedCloseDispatcher(2, capacity - 2, capacity, (name, task) -> {
            if (name.contains("stdout-close") || name.contains("stderr-close")) {
                throw new IllegalStateException("output close starter failed: " + name);
            }
            return io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
    }

    private static void closeIgnoringTerminal(DefaultLineSession session) {
        try {
            session.close();
        } catch (RuntimeException | Error ignored) {
            // The fixture deliberately makes every helper output close report a terminal failure.
        }
    }

    private static final class DecoderCreationFailureCharset extends Charset {

        private final int failingCreation;
        private final Throwable failure;
        private int decoderCreations;

        private DecoderCreationFailureCharset(int failingCreation, Throwable failure) {
            super(
                    "X-Procwright-Line-Decoder-Creation-" + failingCreation + "-"
                            + failure.getClass().getSimpleName(),
                    new String[0]);
            this.failingCreation = failingCreation;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            decoderCreations++;
            if (decoderCreations == failingCreation) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int decoderCreations() {
            return decoderCreations;
        }
    }

    private static CharsetDecoder passthroughDecoder(Charset charset) {
        return new CharsetDecoder(charset, 1, 1) {
            @Override
            protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                while (input.hasRemaining() && output.hasRemaining()) {
                    output.put((char) Byte.toUnsignedInt(input.get()));
                }
                return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
            }
        };
    }

    private static final class LatchingFatalDecoderCharset extends Charset {

        private final AssertionError failure;
        private final CountDownLatch beforeFailure = new CountDownLatch(1);
        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        private LatchingFatalDecoderCharset(AssertionError failure) {
            super("X-Procwright-Line-Fatal-Decoder", new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    input.get();
                    beforeFailure.countDown();
                    awaitUninterruptibly(releaseFailure);
                    throw failure;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitBeforeFailure() throws InterruptedException {
            return beforeFailure.await(1, TimeUnit.SECONDS);
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static final class RacingLineDecoderCharset extends Charset {

        private final AssertionError fatalError;
        private final AtomicInteger decoderCreations = new AtomicInteger();
        private final CountDownLatch responseDecoderEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResponseDecoder = new CountDownLatch(1);
        private final CountDownLatch fatalDecoderEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFatalDecoder = new CountDownLatch(1);

        private RacingLineDecoderCharset(AssertionError fatalError) {
            super("X-Procwright-Line-First-Outcome-Race", new String[0]);
            this.fatalError = fatalError;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            int creation = decoderCreations.incrementAndGet();
            if (creation == 1) {
                return delayedResponseDecoder();
            }
            if (creation == 2) {
                return delayedFatalDecoder();
            }
            throw new AssertionError("unexpected decoder creation " + creation);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private CharsetDecoder delayedResponseDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                private boolean emitted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining() || emitted) {
                        return CoderResult.UNDERFLOW;
                    }
                    input.get();
                    responseDecoderEntered.countDown();
                    awaitUninterruptibly(releaseResponseDecoder);
                    output.put('o').put('k').put('\n');
                    emitted = true;
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        private CharsetDecoder delayedFatalDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    input.get();
                    fatalDecoderEntered.countDown();
                    awaitUninterruptibly(releaseFatalDecoder);
                    throw fatalError;
                }
            };
        }

        private boolean awaitResponseDecoder() throws InterruptedException {
            return responseDecoderEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseResponseDecoder() {
            releaseResponseDecoder.countDown();
        }

        private boolean awaitFatalDecoder() throws InterruptedException {
            return fatalDecoderEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseFatalDecoder() {
            releaseFatalDecoder.countDown();
        }
    }

    private static final class OverflowThenMalformedCharset extends Charset {

        private final CountDownLatch beforeMalformed = new CountDownLatch(1);
        private final CountDownLatch releaseMalformed = new CountDownLatch(1);

        private OverflowThenMalformedCharset() {
            super("X-Procwright-Line-Transactional-Overflow-Malformed", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                private boolean overflowed;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!overflowed) {
                        if (!input.hasRemaining()) {
                            return CoderResult.UNDERFLOW;
                        }
                        input.get();
                        char[] line = {'o', 'k', '\n'};
                        int index = 0;
                        while (output.hasRemaining()) {
                            output.put(line[index++ % line.length]);
                        }
                        overflowed = true;
                        return CoderResult.OVERFLOW;
                    }
                    beforeMalformed.countDown();
                    awaitUninterruptibly(releaseMalformed);
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitBeforeMalformed() throws InterruptedException {
            return beforeMalformed.await(1, TimeUnit.SECONDS);
        }

        private void releaseMalformed() {
            releaseMalformed.countDown();
        }
    }

    private static final class PrefixThenThrowingOutputStream extends OutputStream {

        private final Throwable failure;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private int writeCalls;

        private PrefixThenThrowingOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writeCalls++;
            written.write(bytes, offset, Math.min(2, length));
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        private int writeCalls() {
            return writeCalls;
        }

        private String writtenText() {
            return written.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class CountingOutputStream extends OutputStream {

        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public void write(int value) {
            writeCalls.incrementAndGet();
            firstWrite.countDown();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            firstWrite.countDown();
        }

        private boolean awaitWrite() throws InterruptedException {
            return firstWrite.await(1, TimeUnit.SECONDS);
        }

        private int writeCalls() {
            return writeCalls.get();
        }
    }

    private static final class ReplyingOutputStream extends OutputStream {

        private static final byte[] RESPONSE = "ok\n".getBytes(StandardCharsets.UTF_8);

        private final ResponseInputStream responses;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger writeCalls = new AtomicInteger();

        private ReplyingOutputStream(ResponseInputStream responses) {
            this.responses = responses;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            written.write(bytes, offset, length);
            responses.publish(RESPONSE);
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private int writeCalls() {
            return writeCalls.get();
        }

        private synchronized String writtenText() {
            return written.toString(StandardCharsets.UTF_8);
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingReplyOutputStream extends OutputStream {

        private static final byte[] RESPONSE = "ok\n".getBytes(StandardCharsets.UTF_8);

        private final ResponseInputStream responses;
        private final CountDownLatch callbackStarted;
        private final CountDownLatch releaseCallback;
        private final CountDownLatch closed = new CountDownLatch(1);

        private BlockingReplyOutputStream(
                ResponseInputStream responses, CountDownLatch callbackStarted, CountDownLatch releaseCallback) {
            this.responses = responses;
            this.callbackStarted = callbackStarted;
            this.releaseCallback = releaseCallback;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            callbackStarted.countDown();
            awaitUninterruptibly(releaseCallback);
            responses.publish(RESPONSE);
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }
    }

    private static final class ResponseInputStream extends InputStream {

        private final ArrayDeque<Byte> bytes = new ArrayDeque<>();
        private boolean closed;

        @Override
        public synchronized int read() {
            awaitData();
            return bytes.isEmpty() ? -1 : Byte.toUnsignedInt(bytes.removeFirst());
        }

        @Override
        public synchronized int read(byte[] destination, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, destination.length);
            if (length == 0) {
                return 0;
            }
            awaitData();
            if (bytes.isEmpty()) {
                return -1;
            }
            int count = Math.min(length, bytes.size());
            for (int index = 0; index < count; index++) {
                destination[offset + index] = bytes.removeFirst();
            }
            return count;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        private synchronized void publish(byte[] response) {
            for (byte value : response) {
                bytes.addLast(value);
            }
            notifyAll();
        }

        private void awaitData() {
            boolean interrupted = false;
            while (bytes.isEmpty() && !closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class BlockingAfterFirstByteOutputStream extends OutputStream {

        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final CountDownLatch firstByte = new CountDownLatch(1);
        private final CountDownLatch releaseWriter = new CountDownLatch(1);
        private final CountDownLatch writerStopped = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            if (length > 0) {
                synchronized (written) {
                    written.write(bytes[offset]);
                }
            }
            firstByte.countDown();
            try {
                releaseWriter.await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                writerStopped.countDown();
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private boolean awaitFirstByte() throws InterruptedException {
            return firstByte.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitWriterStopped() throws InterruptedException {
            return writerStopped.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }

        private byte[] writtenBytes() {
            synchronized (written) {
                return written.toByteArray();
            }
        }

        private boolean wasInterrupted() {
            return interrupted.get();
        }

        private int writeCalls() {
            return writeCalls.get();
        }

        private void releaseWriter() {
            releaseWriter.countDown();
        }
    }

    private static final class GatedByteInputStream extends InputStream {

        private final byte value;
        private final CountDownLatch releaseByte = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();

        private GatedByteInputStream(byte value) {
            this.value = value;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseByte);
            return delivered.compareAndSet(false, true) ? Byte.toUnsignedInt(value) : -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            int next = read();
            if (next < 0) {
                return -1;
            }
            bytes[offset] = (byte) next;
            return 1;
        }

        private void releaseByte() {
            releaseByte.countDown();
        }
    }

    private static final class GatedErrorInputStream extends InputStream {

        private final Error failure;
        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        private GatedErrorInputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseFailure);
            throw failure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static final class ControlledRequestLockWaiter implements SerializedRequestGate.Waiter {

        private final CountDownLatch contended = new CountDownLatch(1);
        private final CountDownLatch expired = new CountDownLatch(1);

        @Override
        public boolean acquire(java.util.concurrent.locks.ReentrantLock lock, long remainingNanos)
                throws InterruptedException {
            if (lock.tryLock()) {
                return true;
            }
            contended.countDown();
            expired.await();
            return false;
        }

        private boolean awaitContended() throws InterruptedException {
            return contended.await(1, TimeUnit.SECONDS);
        }

        private void expire() {
            expired.countDown();
        }
    }

    private static final class ControlledPumpFailureInputStream extends InputStream {

        private final Error readFailure;
        private final Error closeFailure;
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private volatile Thread closeThread;

        private ControlledPumpFailureInputStream(Error readFailure, Error closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            if (readFailure == null) {
                return -1;
            }
            readEntered.countDown();
            awaitUninterruptibly(releaseRead);
            throw readFailure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closeThread = Thread.currentThread();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            throw closeFailure;
        }

        private boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseReadFailure() {
            releaseRead.countDown();
        }

        private boolean awaitCloseEntered() throws InterruptedException {
            return closeEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseCloseFailure() {
            releaseClose.countDown();
        }

        private boolean awaitCloseWorkerStopped() throws InterruptedException {
            Thread worker = closeThread;
            if (worker == null) {
                return false;
            }
            worker.join(TimeUnit.SECONDS.toMillis(1));
            return !worker.isAlive();
        }
    }

    private static final class BlockingUntilClosedInputStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() {
            awaitUninterruptibly(closed);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public int read() {
            reads.incrementAndGet();
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            reads.incrementAndGet();
            return -1;
        }

        private int reads() {
            return reads.get();
        }
    }

    private static final class ZeroForeverInputStream extends InputStream {

        private final CountDownLatch firstRead = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();
        private volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            // Deliberately ignore close so the pump must observe its owning session state.
        }

        private void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
            firstRead.countDown();
        }

        private boolean awaitFirstRead() throws InterruptedException {
            return firstRead.await(1, TimeUnit.SECONDS);
        }

        private int reads() {
            return reads.get();
        }

        private Thread readerThread() {
            return readerThread;
        }
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BlockingPhysicalCloseInputStream extends InputStream {

        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
        }
    }

    private static final class ControllableProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final OutputStream stdin;
        private final InputStream stdout;
        private final InputStream stderr;

        private ControllableProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private void complete(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException exception) {
                return false;
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer exitCode = exit.getNow(null);
            if (exitCode == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            alive.set(false);
            exit.complete(143);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}

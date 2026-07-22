/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ProcessTreeScannerTestSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class DefaultProtocolSessionTest {

    @Test
    void publicExitWaitsForPhysicalOutputCleanup() throws Exception {
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), noOpAdapter(), ProtocolSessionSettings.defaults());
        try {
            process.exitNaturally(0);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            assertFalse(protocol.physicalOutputCleanup().isDone());
            assertFalse(protocol.onExit().isDone(), "public protocol exit must wait for physical output cleanup");

            stdout.releaseClose.countDown();
            assertEquals(
                    0, protocol.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertTrue(protocol.physicalOutputCleanup().isDone());
        } finally {
            stdout.releaseClose.countDown();
            process.exitNaturally(143);
            protocol.close();
        }
    }

    @Test
    void blockingPublicExitContinuationObservesReleasedPhysicalCloseCapacity() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean cleanupWasComplete = new AtomicBoolean();
        CompletableFuture<Void> continuation = protocol.onExit().thenRun(() -> {
            cleanupWasComplete.set(protocol.physicalOutputCleanup().isDone()
                    && dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0);
            continuationEntered.countDown();
            awaitUninterruptibly(releaseContinuation);
        });
        FutureTask<Void> closeTask = new FutureTask<>(() -> {
            protocol.close();
            return null;
        });
        Thread closeThread = new Thread(closeTask, "procwright-protocol-blocking-continuation-test");
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
    }

    @Test
    void abandonedProtocolRuntimeAndErrorAreReportedOnceAfterCallbackCapacityIsReleased() throws Exception {
        for (Throwable lateFailure : List.of(
                new IllegalStateException("late protocol runtime failure"),
                new AssertionError("late protocol error"))) {
            assertAbandonedProtocolFailureIsIsolated(lateFailure);
        }
    }

    private static void assertAbandonedProtocolFailureIsIsolated(Throwable lateFailure) throws Exception {
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
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitUninterruptibly(releaseDecoder);
                if (lateFailure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw (Error) lateFailure;
            }
        };
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(new ControllableProcess(
                        OutputStream.nullOutputStream(),
                        new BlockingUntilClosedInputStream(),
                        InputStream.nullInputStream())),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofMillis(40)));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = caller.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));

            ProtocolSessionException timeout =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            assertEquals(initialCapacity - 1, BoundedTaskRunner.PROTOCOL_CALLBACKS.availablePermits());

            releaseDecoder.countDown();
            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(eventuallyProtocolCapacity(initialCapacity));
            assertEquals(1, handlerCalls.get());
            releaseHandler.countDown();
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(1, handlerCalls.get());
        } finally {
            releaseDecoder.countDown();
            releaseHandler.countDown();
            protocol.close();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        assertTrue(eventuallyProtocolCapacity(initialCapacity));
    }

    private static boolean eventuallyProtocolCapacity(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (BoundedTaskRunner.PROTOCOL_CALLBACKS.availablePermits() != expected) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static boolean awaitSubscriberCount(DefaultSession.ProcessExitObservation observation, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (observation.subscriberCount() != expected) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
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
    void protocolWriterIsThreadConfinedAndExpiresWithItsAdapterInvocation() {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicReference<ProtocolWriter> retained = new AtomicReference<>();
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (firstWrite.getAndSet(false)) {
                    retained.set(writer);
                    assertRejectedFromForeignThread(() -> writer.writeLine("foreign"));
                    assertRejectedFromForeignThread(() -> writer.write(new byte[] {0, 1}, 0, 1));
                    assertRejectedFromForeignThread(writer::remainingByteCapacity);
                    assertRejectedFromForeignThread(() -> writer.ensureByteCapacity(1));
                }
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "response";
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());

        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults())) {
            assertEquals("response", protocol.request("first"));

            assertThrows(IllegalStateException.class, () -> retained.get().writeLine("late"));
            assertThrows(IllegalStateException.class, retained.get()::remainingByteCapacity);
            assertThrows(IllegalStateException.class, () -> retained.get().ensureByteCapacity(1));
            assertEquals("response", protocol.request("second"));
            assertEquals("first\nsecond\n", stdin.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void protocolWriterPreflightsCapacityAndWritesArraySlicesWithoutChargingUnusedBytes() {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                assertEquals(4, writer.remainingByteCapacity());
                writer.ensureByteCapacity(2);
                writer.write(new byte[] {9, 1, 2, 9}, 1, 2);
                assertEquals(2, writer.remainingByteCapacity());
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "response";
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());

        try (DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process), adapter, ProtocolSessionSettings.defaults().withMaxRequestBytes(4))) {
            assertEquals("response", protocol.request("ignored"));
            assertArrayEquals(new byte[] {1, 2}, stdin.toByteArray());
        }
    }

    @Test
    void protocolWriterRejectsOversizedPreflightBeforeStdinMutation() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.ensureByteCapacity(5);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                throw new AssertionError("oversized request must not reach response decoding");
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        try (DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process), adapter, ProtocolSessionSettings.defaults().withMaxRequestBytes(4))) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
            assertEquals(0, stdin.size());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    @Test
    void protocolReaderIsThreadConfinedAndExpiresWithItsAdapterInvocation() {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicReference<ProtocolReader> retained = new AtomicReference<>();
        AtomicBoolean firstRead = new AtomicBoolean(true);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                ProtocolReader stdout = readers.stdout();
                if (firstRead.getAndSet(false)) {
                    retained.set(stdout);
                    assertRejectedFromForeignThread(() -> stdout.readLine(16));
                }
                return stdout.readLine(16);
            }
        };
        ControllableProcess process = new ControllableProcess(
                stdin,
                new ByteArrayInputStream("first\nsecond\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());

        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults())) {
            assertEquals("first", protocol.request("one"));

            assertThrows(IllegalStateException.class, () -> retained.get().readLine(16));
            assertEquals("second", protocol.request("two"));
            assertEquals("one\ntwo\n", stdin.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void atomicDecoderSuffixSurvivesRequestReadersAndUsesEachRequestBudget() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return readers.stdout().readLine(1);
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, new ByteArrayInputStream(new byte[] {1}), InputStream.nullInputStream());
        ProtocolSessionSettings settings = options(new AtomicRequestLinesCharset())
                .withOutputBacklogLimit(8)
                .withMaxResponseBytes(1)
                .withMaxResponseChars(2);

        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, settings)) {
            assertEquals("a", protocol.request("first"));
            assertEquals("b", protocol.request("second"));

            ProtocolSessionException eof =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("third"));
            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertEquals(0, stdin.size());
        }
    }

    @ParameterizedTest
    @EnumSource(CallbackExit.class)
    void escapedProtocolWriterExpiresAfterEveryCallbackExitBeforeStdinMutation(CallbackExit exit) throws Exception {
        assertEscapedProtocolCapabilityExpires(exit, true);
    }

    @ParameterizedTest
    @EnumSource(CallbackExit.class)
    void escapedProtocolReaderExpiresAfterEveryCallbackExitBeforeDecoderMutation(CallbackExit exit) throws Exception {
        assertEscapedProtocolCapabilityExpires(exit, false);
    }

    private static void assertEscapedProtocolCapabilityExpires(CallbackExit exit, boolean writerPhase)
            throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicReference<ProtocolWriter> retainedWriter = new AtomicReference<>();
        AtomicReference<ProtocolReader> retainedReader = new AtomicReference<>();
        AtomicReference<ProtocolReader> retainedStderr = new AtomicReference<>();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        RuntimeException runtimeFailure = new IllegalStateException("protocol callback runtime failure");
        AssertionError fatalFailure = new AssertionError("protocol callback fatal failure");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (!writerPhase) {
                    writer.writeLine(request);
                    writer.flush();
                    return;
                }
                retainedWriter.set(writer);
                runEscapingCallback(
                        exit, callbackStarted, releaseCallback, callbackFinished, runtimeFailure, fatalFailure);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (writerPhase) {
                    return "response";
                }
                retainedReader.set(readers.stdout());
                retainedStderr.set(readers.stderr());
                runEscapingCallback(
                        exit, callbackStarted, releaseCallback, callbackFinished, runtimeFailure, fatalFailure);
                return "response";
            }
        };
        ControllableProcess process = new ControllableProcess(
                stdin,
                new ByteArrayInputStream("queued\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> outcome = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request(
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
                    ProtocolSessionException typed = assertInstanceOf(ProtocolSessionException.class, requestFailure);
                    assertEquals(
                            writerPhase
                                    ? ProtocolSessionException.Reason.FAILURE
                                    : ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                            typed.reason());
                    assertSame(runtimeFailure, typed.getCause());
                }
                case ERROR -> assertSame(fatalFailure, requestFailure);
                case TIMEOUT ->
                    assertEquals(
                            ProtocolSessionException.Reason.TIMEOUT,
                            assertInstanceOf(ProtocolSessionException.class, requestFailure)
                                    .reason());
                case CANCELLATION -> {
                    assertEquals(
                            ProtocolSessionException.Reason.FAILURE,
                            assertInstanceOf(ProtocolSessionException.class, requestFailure)
                                    .reason());
                    assertTrue(callerInterruptRestored.get());
                }
            }

            releaseCallback.countDown();
            assertTrue(callbackFinished.await(1, TimeUnit.SECONDS));
            byte[] stdinBeforeEscapedUse = stdin.toByteArray();
            if (writerPhase) {
                assertThrows(
                        IllegalStateException.class, () -> retainedWriter.get().writeLine("late"));
            } else {
                assertThrows(
                        IllegalStateException.class, () -> retainedReader.get().readLine(16));
                assertThrows(
                        IllegalStateException.class, () -> retainedStderr.get().readLine(16));
            }
            assertArrayEquals(stdinBeforeEscapedUse, stdin.toByteArray());
        } finally {
            releaseCallback.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static void runEscapingCallback(
            CallbackExit exit,
            CountDownLatch callbackStarted,
            CountDownLatch releaseCallback,
            CountDownLatch callbackFinished,
            RuntimeException runtimeFailure,
            Error fatalFailure) {
        callbackStarted.countDown();
        try {
            switch (exit) {
                case RUNTIME_EXCEPTION -> throw runtimeFailure;
                case ERROR -> throw fatalFailure;
                case TIMEOUT, CANCELLATION -> awaitUninterruptibly(releaseCallback);
            }
        } finally {
            callbackFinished.countDown();
        }
    }

    @ParameterizedTest
    @EnumSource(Abandonment.class)
    void writerInterruptObservesExpiredCapabilityBeforeStdinMutation(Abandonment abandonment) throws Exception {
        assertInterruptObservesExpiredProtocolCapability(abandonment, true);
    }

    @ParameterizedTest
    @EnumSource(Abandonment.class)
    void readerInterruptObservesExpiredCapabilitiesBeforeOutputMutation(Abandonment abandonment) throws Exception {
        assertInterruptObservesExpiredProtocolCapability(abandonment, false);
    }

    private static void assertInterruptObservesExpiredProtocolCapability(Abandonment abandonment, boolean writerPhase)
            throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch capabilityAttempted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Throwable> firstCapabilityFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondCapabilityFailure = new AtomicReference<>();
        AtomicInteger stdinBytesBeforeUse = new AtomicInteger(-1);
        AtomicInteger stdinBytesAfterUse = new AtomicInteger(-1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (!writerPhase) {
                    writer.writeLine(request);
                    writer.flush();
                    return;
                }
                awaitAbandonmentInterrupt(
                        () -> firstCapabilityFailure.set(captureFailure(() -> writer.writeLine("late"))),
                        callbackStarted,
                        capabilityAttempted,
                        releaseCallback,
                        stdin,
                        stdinBytesBeforeUse,
                        stdinBytesAfterUse);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (writerPhase) {
                    return "response";
                }
                awaitAbandonmentInterrupt(
                        () -> {
                            firstCapabilityFailure.set(
                                    captureFailure(() -> readers.stdout().readLine(16)));
                            secondCapabilityFailure.set(
                                    captureFailure(() -> readers.stderr().readLine(16)));
                        },
                        callbackStarted,
                        capabilityAttempted,
                        releaseCallback,
                        stdin,
                        stdinBytesBeforeUse,
                        stdinBytesAfterUse);
                return "response";
            }
        };
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(new ControllableProcess(
                        stdin,
                        new ByteArrayInputStream("queued\n".getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayInputStream("diagnostic\n".getBytes(StandardCharsets.UTF_8)))),
                adapter,
                ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> outcome = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request(
                        "request", abandonment == Abandonment.TIMEOUT ? Duration.ofMillis(250) : Duration.ofDays(1)));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            switch (abandonment) {
                case TIMEOUT -> {}
                case SESSION_CLOSE -> protocol.close();
                case CALLER_INTERRUPT ->
                    Objects.requireNonNull(callerThread.get(), "callerThread").interrupt();
            }

            assertTrue(capabilityAttempted.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, firstCapabilityFailure.get());
            if (!writerPhase) {
                assertInstanceOf(IllegalStateException.class, secondCapabilityFailure.get());
            }
            assertEquals(stdinBytesBeforeUse.get(), stdinBytesAfterUse.get());

            ProtocolSessionException requestFailure =
                    assertInstanceOf(ProtocolSessionException.class, outcome.get(2, TimeUnit.SECONDS));
            assertEquals(
                    switch (abandonment) {
                        case TIMEOUT -> ProtocolSessionException.Reason.TIMEOUT;
                        case SESSION_CLOSE -> ProtocolSessionException.Reason.CLOSED;
                        case CALLER_INTERRUPT -> ProtocolSessionException.Reason.FAILURE;
                    },
                    requestFailure.reason());
            if (abandonment == Abandonment.SESSION_CLOSE) {
                assertInstanceOf(BoundedTaskRunner.TaskCancelledException.class, requestFailure.getCause());
            }
            assertEquals(abandonment == Abandonment.CALLER_INTERRUPT, callerInterruptRestored.get());
        } finally {
            releaseCallback.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static void awaitAbandonmentInterrupt(
            Runnable capabilityUse,
            CountDownLatch callbackStarted,
            CountDownLatch capabilityAttempted,
            CountDownLatch releaseCallback,
            ByteArrayOutputStream stdin,
            AtomicInteger stdinBytesBeforeUse,
            AtomicInteger stdinBytesAfterUse) {
        callbackStarted.countDown();
        try {
            new CountDownLatch(1).await();
            throw new AssertionError("protocol callback was not interrupted after abandonment");
        } catch (InterruptedException expected) {
            stdinBytesBeforeUse.set(stdin.size());
            capabilityUse.run();
            stdinBytesAfterUse.set(stdin.size());
            capabilityAttempted.countDown();
            awaitUninterruptibly(releaseCallback);
        }
    }

    @Test
    void eagerMandatoryDecoderRuntimeFailuresAreTypedBeforeClaimAndLeaveRawSessionOpen() {
        for (int failingCreation : List.of(1, 2, 3, 4)) {
            for (Throwable cause : List.of(
                    new IllegalArgumentException("decoder creation " + failingCreation + " failed"),
                    new CoderMalfunctionError(new BufferUnderflowException()))) {
                DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
                TrackingInputStream stdout = new TrackingInputStream();
                TrackingInputStream stderr = new TrackingInputStream();
                ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
                DefaultSession rawSession = session(process);
                try {
                    ProtocolSessionException failure = assertThrows(
                            ProtocolSessionException.class,
                            () -> new DefaultProtocolSession<>(rawSession, noOpAdapter(), options(charset)));

                    assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
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
    void repeatedDecoderConstructionFailuresDoNotRetainExitSubscribers() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        DefaultSession.ProcessExitObservation observation = rawSession.processExitObservation();
        int baselineSubscribers = observation.subscriberCount();
        long baselineDeliveries = observation.deliveryCount();
        DefaultProtocolSession<String, Byte> protocol = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (int iteration = 0; iteration < 1_000; iteration++) {
                IllegalArgumentException cause = new IllegalArgumentException("decoder creation failed " + iteration);
                DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(1, cause);

                ProtocolSessionException failure = assertThrows(
                        ProtocolSessionException.class,
                        () -> new DefaultProtocolSession<>(rawSession, noOpAdapter(), options(charset)));

                assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
                assertSame(cause, failure.getCause());
                assertEquals(baselineSubscribers, observation.subscriberCount());
                assertEquals(baselineDeliveries, observation.deliveryCount());
            }

            OutputWaitTransitionProbe transitions = new OutputWaitTransitionProbe();
            protocol = new DefaultProtocolSession<>(
                    rawSession,
                    byteReadingAdapter(),
                    ProtocolSessionSettings.defaults(),
                    ZeroReadBackoff.exponential(),
                    PumpStarter.threading(),
                    transitions);
            DefaultProtocolSession<String, Byte> activeProtocol = protocol;
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> activeProtocol.request("request")));
            assertTrue(transitions.awaitOutputWait());
            assertTrue(stdout.awaitReadEntered());
            assertEquals(baselineSubscribers + 1, observation.subscriberCount());

            process.exitNaturally(17);
            assertTrue(awaitSubscriberCount(observation, baselineSubscribers));
            stdout.releaseEof();
            ProtocolSessionException processExited =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, processExited.reason());
            assertEquals(17, processExited.exitCode().orElseThrow());
            assertEquals(baselineDeliveries + 1, observation.deliveryCount());
        } finally {
            stdout.releaseEof();
            if (protocol != null) {
                protocol.close();
            } else {
                rawSession.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void pumpStartupFailureRacingProcessExitSettlesExitRegistrationOnce() throws Exception {
        ControllableProcess process = new ControllableProcess();
        DefaultSession rawSession = session(process);
        DefaultSession.ProcessExitObservation observation = rawSession.processExitObservation();
        int baselineSubscribers = observation.subscriberCount();
        long baselineDeliveries = observation.deliveryCount();
        AssertionError startupFailure = new AssertionError("pump startup failed");
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        PumpStarter starter = (name, task) -> {
            starterEntered.countDown();
            awaitUninterruptibly(releaseStarter);
            throw startupFailure;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> construction = executor.submit(() -> captureFailure(() -> new DefaultProtocolSession<>(
                    rawSession,
                    noOpAdapter(),
                    ProtocolSessionSettings.defaults(),
                    ZeroReadBackoff.exponential(),
                    starter)));
            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));
            assertEquals(baselineSubscribers + 1, observation.subscriberCount());

            process.exitNaturally(29);
            assertTrue(awaitSubscriberCount(observation, baselineSubscribers));
            releaseStarter.countDown();

            assertSame(startupFailure, construction.get(2, TimeUnit.SECONDS));
            assertEquals(baselineSubscribers, observation.subscriberCount());
            assertEquals(baselineDeliveries + 1, observation.deliveryCount());
            rawSession.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            releaseStarter.countDown();
            process.exitNaturally(143);
            rawSession.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void eagerMandatoryDecoderFatalErrorsPreserveIdentityAndLeaveRawSessionOpen() {
        for (int failingCreation : List.of(1, 2, 3, 4)) {
            AssertionError cause = new AssertionError("fatal decoder creation " + failingCreation);
            DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
            DefaultSession rawSession = session(process);
            try {
                AssertionError thrown = assertThrows(
                        AssertionError.class,
                        () -> new DefaultProtocolSession<>(rawSession, noOpAdapter(), options(charset)));

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
    void fatalTranscriptDecoderErrorFailStopsActiveAndFollowUpRequestsForEitherStream() throws Exception {
        for (boolean fatalStdout : List.of(true, false)) {
            AssertionError fatalError =
                    new AssertionError("fatal protocol " + (fatalStdout ? "stdout" : "stderr") + " decoder failure");
            LatchingFatalTranscriptCharset charset = new LatchingFatalTranscriptCharset(fatalError);
            GatedByteInputStream fatalStream = new GatedByteInputStream((byte) '!');
            BlockingUntilClosedInputStream blockedStdout = new BlockingUntilClosedInputStream();
            InputStream stdout = fatalStdout ? fatalStream : blockedStdout;
            InputStream stderr = fatalStdout ? InputStream.nullInputStream() : fatalStream;
            CountingOutputStream stdin = new CountingOutputStream();
            ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
            DefaultSession rawSession = session(process);
            ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
                @Override
                public void writeRequest(String request, ProtocolWriter writer) {
                    writer.write(new byte[] {1});
                    writer.flush();
                }

                @Override
                public Byte readResponse(ProtocolReaders readers) {
                    return readers.stdout().readByte();
                }
            };
            DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                    rawSession, adapter, options(charset).withTranscriptLimit(32));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
                assertTrue(stdin.awaitWrite(), "the request must be active before transcript decoding fails");
                fatalStream.releaseByte();
                assertTrue(charset.awaitBeforeFailure());
                charset.releaseFailure();

                assertSame(fatalError, request.get(2, TimeUnit.SECONDS));
                protocol.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
                assertTrue(protocol.transcript().text().length() <= 32);
                int writesAfterFailure = stdin.writeCalls();

                AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
                assertSame(fatalError, followUp);
                assertEquals(writesAfterFailure, stdin.writeCalls());
            } finally {
                fatalStream.releaseByte();
                charset.releaseFailure();
                try {
                    protocol.close();
                } finally {
                    blockedStdout.close();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void fatalPumpErrorAfterCloseBecomesTheLaterTerminalObservationAndRetainsCleanupFailures() throws Exception {
        AssertionError pumpError = new AssertionError("late protocol pump failure");
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
        DefaultProtocolSession<String, String> protocolSession = new DefaultProtocolSession<>(
                rawSession, noOpAdapter(), ProtocolSessionSettings.defaults(), ZeroReadBackoff.exponential(), starter);
        try {
            assertTrue(stdout.awaitReadEntered());

            protocolSession.close();
            assertTrue(stdout.awaitCloseEntered());
            assertTrue(stderr.awaitCloseEntered());

            stdout.releaseReadFailure();
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }
            assertNull(uncaughtPumpFailure.get());

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
            AssertionError followUp = assertThrows(AssertionError.class, () -> protocolSession.request("after-close"));
            assertSame(pumpError, followUp);
        } finally {
            stdout.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            protocolSession.close();
            rawSession.close();
        }
    }

    @Test
    void fatalPumpErrorObservedBeforeActiveCloseLinearizationWinsTheRequest() throws Exception {
        AssertionError pumpError = new AssertionError("fatal pump failure during active close");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ControlledPumpFailureInputStream stdout = new ControlledPumpFailureInputStream(pumpError, stdoutCloseFailure);
        ControlledPumpFailureInputStream stderr = new ControlledPumpFailureInputStream(null, stderrCloseFailure);
        CloseFatalTransitionProbe transitions = new CloseFatalTransitionProbe();
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                session(process),
                new ProtocolAdapter<>() {
                    @Override
                    public void writeRequest(String request, ProtocolWriter writer) {
                        writer.flush();
                    }

                    @Override
                    public Byte readResponse(ProtocolReaders readers) {
                        return readers.stdout().readByte();
                    }
                },
                ProtocolSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(stdout.awaitReadEntered());
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(transitions.awaitOutputWait(), "active request did not enter the output wait");

            protocol.close();
            assertTrue(transitions.awaitRequestFailureReturn(), "closed request did not reach its linearization point");
            assertTrue(stdout.awaitCloseEntered());
            assertTrue(stderr.awaitCloseEntered());

            stdout.releaseReadFailure();
            assertTrue(transitions.awaitFatalPromotion(), "fatal pump Error did not supersede ClosedTerminal");
            transitions.releaseRequestFailureReturn();
            assertSame(pumpError, request.get(2, TimeUnit.SECONDS));

            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            assertTrue(stdout.awaitCloseWorkerStopped());
            assertTrue(stderr.awaitCloseWorkerStopped());
            protocol.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
            assertIdentitySuppressedOnce(pumpError, stdoutCloseFailure);
            assertIdentitySuppressedOnce(pumpError, stderrCloseFailure);
            ProtocolSessionException closedFailure = assertInstanceOf(
                    ProtocolSessionException.class,
                    java.util.Arrays.stream(pumpError.getSuppressed())
                            .filter(ProtocolSessionException.class::isInstance)
                            .findFirst()
                            .orElseThrow());
            assertEquals(ProtocolSessionException.Reason.CLOSED, closedFailure.reason());
            assertIdentitySuppressedOnce(pumpError, closedFailure);
            assertFailureGraphDoesNotContain(closedFailure, pumpError);
            assertEquals(3, pumpError.getSuppressed().length);

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
            assertSame(pumpError, followUp);
        } finally {
            stdout.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            transitions.releaseRequestFailureReturn();
            try {
                protocol.close();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolStderrDecoderErrorSupersedesEarlierResponseLimitFailure() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated(true);
    }

    @Test
    void protocolStderrDecoderErrorRemainsPrimaryWhenResponseLimitFailsLater() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated(false);
    }

    @Test
    void directResponseCallbackErrorIsTheTerminalFailureByIdentity() throws Exception {
        AssertionError callbackError = new AssertionError("response callback failed");
        ControllableProcess process = new ControllableProcess();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                throw callbackError;
            }
        };
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults());
        try {
            AssertionError current = assertThrows(AssertionError.class, () -> protocol.request("request"));
            assertSame(callbackError, current);
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
            assertSame(callbackError, followUp);
            assertEquals(0, callbackError.getSuppressed().length);
        } finally {
            protocol.close();
        }
    }

    @Test
    void callbackErrorSupersedesTheTypedFailureCaughtByTheCallback() throws Exception {
        AssertionError callbackError = new AssertionError("callback rejected oversized response");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readLine(1);
                    throw new AssertionError("response limit was not enforced");
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw callbackError;
                }
            }
        };
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                new java.io.ByteArrayInputStream("ab\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults());
        try {
            AssertionError current = assertThrows(AssertionError.class, () -> protocol.request("request"));
            ProtocolSessionException responseFailure = caughtFailure.get();
            assertSame(callbackError, current);
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
            assertIdentitySuppressedOnce(callbackError, responseFailure);
            assertFailureGraphDoesNotContain(responseFailure, callbackError);
            assertEquals(1, callbackError.getSuppressed().length);

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
            assertSame(callbackError, followUp);
        } finally {
            protocol.close();
        }
    }

    @Test
    void callbackErrorCompletingAfterOuterTimeoutWinsBeforeRequestLinearization() throws Exception {
        AssertionError callbackError = new AssertionError("late response callback failure");
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        TimeoutArbitrationProbe transitions = TimeoutArbitrationProbe.blockTerminalSelection();
        EventDrivenTimeoutCallbackRunner callbackRunner = new EventDrivenTimeoutCallbackRunner();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                callbackStarted.countDown();
                awaitUninterruptibly(releaseCallback);
                throw callbackError;
            }
        };
        ControllableProcess process = new ControllableProcess();
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions,
                System::nanoTime,
                callbackRunner);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            callbackRunner.expireDeadline();
            assertTrue(transitions.awaitTerminalSelection(), "outer timeout did not select the typed failure");
            releaseCallback.countDown();
            assertTrue(callbackRunner.awaitDecoderStopped(), "late callback did not terminate");
            transitions.releaseTerminalSelection();

            assertSame(callbackError, request.get(2, TimeUnit.SECONDS));
            assertEquals(1, callbackError.getSuppressed().length);
            ProtocolSessionException timeout = assertInstanceOf(
                    ProtocolSessionException.class, callbackError.getSuppressed()[0]);
            assertCanonicalTimeout(timeout);
            assertFailureGraphDoesNotContain(timeout, callbackError);
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
            assertSame(callbackError, followUp);
        } finally {
            releaseCallback.countDown();
            transitions.releaseOutputTimeoutFailure();
            transitions.releaseTerminalSelection();
            try {
                protocol.close();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                assertTrue(callbackRunner.awaitDecoderStopped());
            }
        }
    }

    @Test
    void nonCooperativeCallbackRetainsSharedCapacityAndNextRequestFailsTypedWithoutStarting() throws Exception {
        CountDownLatch firstCallbackStarted = new CountDownLatch(1);
        SaturatingProtocolCallbackRunner callbackRunner = new SaturatingProtocolCallbackRunner(firstCallbackStarted);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CountDownLatch firstCallbackStopped = new CountDownLatch(1);
        AtomicInteger secondCallbackStarts = new AtomicInteger();
        ProtocolAdapter<String, String> blockingAdapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if ("first".equals(request)) {
                    firstCallbackStarted.countDown();
                    try {
                        awaitUninterruptibly(releaseFirstCallback);
                    } finally {
                        firstCallbackStopped.countDown();
                    }
                } else {
                    secondCallbackStarts.incrementAndGet();
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
        DefaultProtocolSession<String, String> first = protocolWithCallbackRunner(blockingAdapter, callbackRunner);
        DefaultProtocolSession<String, String> second = protocolWithCallbackRunner(blockingAdapter, callbackRunner);
        try {
            ProtocolSessionException firstTimeout =
                    assertThrows(ProtocolSessionException.class, () -> first.request("first"));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, firstTimeout.reason());
            assertTrue(firstCallbackStarted.await(1, TimeUnit.SECONDS));
            assertEquals(0, callbackRunner.availablePermits());

            ProtocolSessionException secondTimeout =
                    assertThrows(ProtocolSessionException.class, () -> second.request("second"));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, secondTimeout.reason());
            assertEquals(0, secondCallbackStarts.get(), "capacity rejection must not start a fallback callback thread");
            assertEquals(0, callbackRunner.availablePermits());

            releaseFirstCallback.countDown();
            assertTrue(firstCallbackStopped.await(1, TimeUnit.SECONDS));

            ProtocolAdapter<String, String> successfulAdapter = new ProtocolAdapter<>() {
                @Override
                public void writeRequest(String request, ProtocolWriter writer) {
                    writer.flush();
                }

                @Override
                public String readResponse(ProtocolReaders readers) {
                    return "recovered";
                }
            };
            try (DefaultProtocolSession<String, String> recovered =
                    protocolWithCallbackRunner(successfulAdapter, callbackRunner)) {
                assertEquals("recovered", recovered.request("third"));
            }
            assertEquals(1, callbackRunner.availablePermits());
        } finally {
            releaseFirstCallback.countDown();
            try {
                first.close();
            } finally {
                second.close();
            }
        }
    }

    @Test
    void textWriterIsFailStoppedAfterPartialDelegateWriteFailure() throws Exception {
        IOException writeFailure = new IOException("partial write failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write("abcd");
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults())) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.BROKEN_PIPE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(2, observed.size());
            assertSame(observed.get(0), observed.get(1));
            assertSame(failure, observed.get(0));
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.BROKEN_PIPE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(writeFailure, followUp.getCause().getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }

    @Test
    void requestEncoderRuntimeFailureIsRecordedAndFailStopsCaughtRetry() throws Exception {
        IllegalArgumentException encoderFailure = new IllegalArgumentException("encoder creation failed");
        EncoderCreationFailureCharset charset = new EncoderCreationFailureCharset(encoderFailure);
        ControllableProcess process = new ControllableProcess();
        DefaultSession rawSession = session(process);
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write(request);
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, options(charset))) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertSame(encoderFailure, failure.getCause());
            assertEquals(2, observed.size());
            assertSame(failure, observed.get(0));
            assertSame(observed.get(0), observed.get(1));
            assertEquals(1, charset.encoderCreations());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(encoderFailure, followUp.getCause().getCause());
        }
    }

    @Test
    void requestEncoderPreflightFailureIsRecordedAndFailStopsCaughtRetry() throws Exception {
        NoProgressEncoderCharset charset = new NoProgressEncoderCharset();
        ControllableProcess process = new ControllableProcess();
        DefaultSession rawSession = session(process);
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write(request);
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, options(charset))) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains("without consuming input or producing output"));
            assertEquals(2, observed.size());
            assertSame(failure, observed.get(0));
            assertSame(observed.get(0), observed.get(1));
            assertEquals(1, charset.encoderCreations());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(failure, followUp.getCause());
        }
    }

    @Test
    void requestEncoderErrorIsRecordedAndRethrownByIdentityAfterCaughtRetry() throws Exception {
        AssertionError encoderFailure = new AssertionError("encoder invariant failed");
        EncoderCreationFailureCharset charset = new EncoderCreationFailureCharset(encoderFailure);
        ControllableProcess process = new ControllableProcess();
        DefaultSession rawSession = session(process);
        List<AssertionError> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (AssertionError error) {
                    observed.add(error);
                }
                writer.write("retry");
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, options(charset))) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> protocol.request("request"));

            assertSame(encoderFailure, thrown);
            assertEquals(List.of(encoderFailure), observed);
            assertEquals(1, charset.encoderCreations());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("again"));
            assertSame(encoderFailure, followUp);
        }
    }

    @Test
    void caughtEncoderErrorIsRethrownAfterWriterCallbackReturns() throws Exception {
        AssertionError encoderFailure = new AssertionError("encoder invariant failed");
        EncoderCreationFailureCharset charset = new EncoderCreationFailureCharset(encoderFailure);
        CountingOutputStream stdin = new CountingOutputStream();
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        AtomicReference<AssertionError> observed = new AtomicReference<>();
        AtomicBoolean responseDecoderCalled = new AtomicBoolean();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (AssertionError error) {
                    observed.set(error);
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                responseDecoderCalled.set(true);
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, options(charset))) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> protocol.request("request"));

            assertSame(encoderFailure, thrown);
            assertSame(encoderFailure, observed.get());
            assertEquals(1, charset.encoderCreations());
            assertEquals(0, stdin.writeCalls());
            assertFalse(responseDecoderCalled.get());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            int writesAfterFailure = stdin.writeCalls();
            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("again"));
            assertSame(encoderFailure, followUp);
            assertEquals(writesAfterFailure, stdin.writeCalls());
        }
    }

    @Test
    void delegateIllegalStateExceptionIsNotTreatedAsClosed() throws Exception {
        assertCaughtRuntimeDelegateFailureIsTerminal(new IllegalStateException("delegate state failed"));
    }

    @Test
    void lifecycleClosedStdinIsTreatedAsClosed() throws Exception {
        ControllableProcess process = new ControllableProcess();
        DefaultSession rawSession = session(process);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.write(request);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults())) {
            rawSession.closeStdin();

            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.CLOSED, failure.reason());
            assertTrue(failure.getCause() instanceof SessionStdinClosedException);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void delegateRuntimeFailuresAreRecordedBeforeCaughtRetry() throws Exception {
        for (RuntimeException failure :
                List.of(new SecurityException("write denied"), new RuntimeException("delegate failed"))) {
            assertCaughtRuntimeDelegateFailureIsTerminal(failure);
        }
    }

    @Test
    void delegateErrorIsRecordedAndRethrownByIdentityAfterCaughtRetry() throws Exception {
        AssertionError writeFailure = new AssertionError("delegate invariant failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        List<AssertionError> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write("abcd");
                } catch (AssertionError error) {
                    observed.add(error);
                }
                writer.write("retry");
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults())) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> protocol.request("ignored"));

            assertSame(writeFailure, thrown);
            assertEquals(List.of(writeFailure), observed);
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("again"));
            assertSame(writeFailure, followUp);
        }
    }

    @Test
    void caughtDelegateErrorIsRethrownAfterWriterCallbackReturns() throws Exception {
        AssertionError writeFailure = new AssertionError("delegate invariant failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        AtomicReference<AssertionError> observed = new AtomicReference<>();
        AtomicBoolean responseDecoderCalled = new AtomicBoolean();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write("abcd");
                } catch (AssertionError error) {
                    observed.set(error);
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                responseDecoderCalled.set(true);
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults())) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> protocol.request("ignored"));

            assertSame(writeFailure, thrown);
            assertSame(writeFailure, observed.get());
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            assertFalse(responseDecoderCalled.get());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            int writesAfterFailure = stdin.writeCalls();
            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("again"));
            assertSame(writeFailure, followUp);
            assertEquals(writesAfterFailure, stdin.writeCalls());
        }
    }

    @Test
    void protocolPoolRetiresWorkerAfterDelegateIllegalStateFailure() {
        IllegalStateException writeFailure = new IllegalStateException("delegate state failed");
        PrefixThenThrowingOutputStream failingStdin = new PrefixThenThrowingOutputStream(writeFailure);
        AtomicInteger workerCreations = new AtomicInteger();
        List<ControllableProcess> processes = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (ProtocolSessionException ignored) {
                    // A caught writer failure must still fail this request and retire its worker.
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        Supplier<ProtocolSession<String, String>> workerFactory = () -> {
            int workerNumber = workerCreations.incrementAndGet();
            OutputStream stdin = workerNumber == 1 ? failingStdin : new ByteArrayOutputStream();
            ControllableProcess process =
                    new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
            processes.add(process);
            return new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults());
        };

        try (DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                workerFactory, WorkerPoolSettings.defaults(worker -> {}, worker -> true))) {
            ProtocolSessionException first = assertThrows(ProtocolSessionException.class, () -> pool.request("first"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, first.reason());
            assertSame(writeFailure, first.getCause());
            assertEquals(1, failingStdin.writeCalls());
            assertEquals(1, workerCreations.get());
            assertFalse(processes.get(0).isAlive());

            assertEquals("fallback", pool.request("second"));
            assertEquals(1, pool.metrics().retired());
            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
            assertEquals(2, workerCreations.get());
            assertTrue(processes.get(1).isAlive());
        }
    }

    @Test
    void protocolPumpIgnoresZeroLengthReadBeforeRealByte() {
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), new ZeroThenByteInputStream((byte) 42), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };

        try (DefaultProtocolSession<String, Byte> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults())) {
            assertEquals((byte) 42, protocol.request("ignored"));
        }
    }

    @Test
    void caughtRepeatedStderrOverflowReadsRetainOneFailureAndCannotReturnSuccess() {
        AtomicReference<ProtocolSessionException> firstObserved = new AtomicReference<>();
        AtomicInteger observedReads = new AtomicInteger();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                for (int attempt = 0; attempt < 10_000; attempt++) {
                    ProtocolSessionException observed =
                            assertThrows(ProtocolSessionException.class, () -> readers.stderr()
                                    .readByte());
                    ProtocolSessionException first =
                            firstObserved.updateAndGet(existing -> existing == null ? observed : existing);
                    assertSame(first, observed);
                    observedReads.incrementAndGet();
                }
                return "must-not-succeed";
            }
        };
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                InputStream.nullInputStream(),
                new java.io.ByteArrayInputStream(new byte[] {1, 2}));
        ProtocolSessionSettings settings = ProtocolSessionSettings.defaults().withOutputBacklogLimit(1);

        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, settings)) {
            ProtocolSessionException requestFailure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(10_000, observedReads.get());
            assertSame(firstObserved.get(), requestFailure);
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, requestFailure.reason());
            assertEquals(0, requestFailure.getSuppressed().length);
            assertEquals(0, requestFailure.getCause().getSuppressed().length);
        }
    }

    @Test
    void stderrOverflowUsesObservedExitCodeBeforePublicSessionExit() throws Exception {
        GatedChunkInputStream stderr = new GatedChunkInputStream(new byte[] {1, 2});
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), InputStream.nullInputStream(), stderr);
        DefaultSession rawSession = session(process);
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch allowRead = new CountDownLatch(1);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitUninterruptibly(allowRead);
                return readers.stderr().readByte();
            }
        };
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                adapter,
                ProtocolSessionSettings.defaults().withOutputBacklogLimit(1).withRequestTimeout(Duration.ofSeconds(5)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));
            stderr.release();
            assertTrue(stderr.awaitEof(), "stderr pump did not retain the overflow marker");
            rawSession.processExitObservation().publish(23);
            assertFalse(rawSession.onExit().isDone());

            allowRead.countDown();
            ProtocolSessionException overflow =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
            assertEquals(23, overflow.exitCode().orElseThrow());
        } finally {
            allowRead.countDown();
            stderr.release();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void selectedOverflowPreventsWriteAdmissionAndSurvivesConcurrentClose() throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream(new byte[] {1, 2});
        LatchingTransitionProbe transitions = new LatchingTransitionProbe();
        AtomicInteger writeCallbacks = new AtomicInteger();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writeCallbacks.incrementAndGet();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "must-not-run";
            }
        };
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process),
                adapter,
                ProtocolSessionSettings.defaults().withOutputBacklogLimit(1).withRequestTimeout(Duration.ofSeconds(5)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(transitions.awaitWriteAdmission());

            stdout.release();
            assertTrue(transitions.awaitTerminalSelection());
            protocol.close();

            transitions.releaseTerminalSelection();
            transitions.releaseWriteAdmission();
            Throwable thrown = request.get(2, TimeUnit.SECONDS);

            ProtocolSessionException overflow = (ProtocolSessionException) thrown;
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
            assertEquals(0, writeCallbacks.get());
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("follow-up"));
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, followUp.reason());
            assertEquals(0, writeCallbacks.get());
        } finally {
            stdout.release();
            transitions.releaseTerminalSelection();
            transitions.releaseWriteAdmission();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void selectedClosePreventsLateOverflowAndWriteAdmission() throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream(new byte[] {1, 2});
        LatchingTransitionProbe transitions = new LatchingTransitionProbe();
        AtomicInteger writeCallbacks = new AtomicInteger();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writeCallbacks.incrementAndGet();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "must-not-run";
            }
        };
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process),
                adapter,
                ProtocolSessionSettings.defaults().withOutputBacklogLimit(1).withRequestTimeout(Duration.ofSeconds(5)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(transitions.awaitWriteAdmission());

            protocol.close();
            stdout.release();
            transitions.releaseWriteAdmission();
            Throwable thrown = request.get(2, TimeUnit.SECONDS);

            ProtocolSessionException closedFailure = (ProtocolSessionException) thrown;
            assertEquals(ProtocolSessionException.Reason.CLOSED, closedFailure.reason());
            assertEquals(0, writeCallbacks.get());
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("follow-up"));
            assertEquals(ProtocolSessionException.Reason.CLOSED, followUp.reason());
            assertEquals(0, writeCallbacks.get());
        } finally {
            stdout.release();
            transitions.releaseTerminalSelection();
            transitions.releaseWriteAdmission();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void zeroForeverStdoutPumpStopsAfterRequestTimeout() throws Exception {
        ZeroForeverInputStream stdout = new ZeroForeverInputStream();
        BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };

        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofMillis(50)),
                backoff);
        try {
            assertTrue(backoff.awaitEntered());
            assertEquals(1, stdout.reads(), "the pump must enter backoff before attempting another read");

            ProtocolSessionException timeout;
            try {
                timeout = assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));
            } finally {
                backoff.release();
            }

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            Thread readerThread = stdout.readerThread();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(readerThread.isAlive(), "protocol pump thread must terminate after request timeout");
            assertEquals(1, stdout.reads(), "timeout during backoff must prevent another read");

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, followUp.reason());
        } finally {
            try {
                backoff.release();
            } finally {
                protocol.close();
            }
        }
    }

    @Test
    void outerAndDecoderTimeoutShareCanonicalFailureWhenOuterPublishesFirst() throws Exception {
        BlockingUntilClosedInputStream stdout = new BlockingUntilClosedInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        TimeoutArbitrationProbe transitions = TimeoutArbitrationProbe.outerPublishesFirst();
        EventDrivenTimeoutCallbackRunner callbackRunner = new EventDrivenTimeoutCallbackRunner(true);
        AtomicReference<ProtocolSessionException> decoderFailure = new AtomicReference<>();
        CountDownLatch decoderFailureObserved = new CountDownLatch(1);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readByte();
                } catch (ProtocolSessionException failure) {
                    decoderFailure.set(failure);
                    decoderFailureObserved.countDown();
                    throw failure;
                }
            }
        };
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions,
                new PastDeadlineAfterWaitNanoTime(),
                callbackRunner);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("ignored")));
            assertTrue(transitions.awaitOutputWait(), "decoder did not enter the protocol output wait");
            callbackRunner.expireDeadline();
            assertTrue(transitions.awaitTerminalSelection(), "outer timeout did not select a terminal outcome");
            assertNotSame(transitions.decoderThread(), transitions.terminalSelector());
            assertNull(decoderFailure.get());
            transitions.releaseTerminalSelection();
            assertTrue(transitions.awaitOutputTimeoutFailure(), "decoder did not reach its timeout publication path");
            transitions.releaseOutputTimeoutFailure();
            assertTrue(decoderFailureObserved.await(1, TimeUnit.SECONDS));

            Throwable observed = request.get(5, TimeUnit.SECONDS);

            ProtocolSessionException timeout = assertInstanceOf(ProtocolSessionException.class, observed);
            assertCanonicalTimeout(timeout);
            assertSame(timeout, decoderFailure.get());
            assertTrue(transitions.decoderThread().isInterrupted());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, followUp.reason());
            assertSame(timeout, followUp.getCause());
        } finally {
            transitions.releaseOutputTimeoutFailure();
            transitions.releaseTerminalSelection();
            protocol.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(callbackRunner.awaitDecoderStopped());
        }
    }

    @Test
    void fatalErrorBeforeTimeoutIsTheCanonicalTerminalFailure() throws Exception {
        assertFatalErrorWinsTimeoutRace(true);
    }

    @Test
    void fatalErrorAfterTimeoutIsTheCanonicalTerminalFailure() throws Exception {
        assertFatalErrorWinsTimeoutRace(false);
    }

    @Test
    void outerAndDecoderTimeoutShareCanonicalFailureWhenDecoderPublishesFirst() throws Exception {
        BlockingUntilClosedInputStream stdout = new BlockingUntilClosedInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        TimeoutArbitrationProbe transitions = TimeoutArbitrationProbe.blockTerminalSelection();
        AtomicReference<ProtocolSessionException> decoderFailure = new AtomicReference<>();
        CountDownLatch decoderFailureObserved = new CountDownLatch(1);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readByte();
                } catch (ProtocolSessionException failure) {
                    decoderFailure.set(failure);
                    decoderFailureObserved.countDown();
                    throw failure;
                }
            }
        };
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofSeconds(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions,
                new PastDeadlineAfterWaitNanoTime());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("ignored")));
            assertTrue(transitions.awaitOutputWait(), "decoder did not enter the protocol output wait");
            transitions.decoderThread().interrupt();
            assertTrue(transitions.awaitTerminalSelection(), "decoder timeout did not select a terminal outcome");
            assertSame(transitions.decoderThread(), transitions.terminalSelector());

            Throwable observed = request.get(5, TimeUnit.SECONDS);

            ProtocolSessionException timeout = assertInstanceOf(ProtocolSessionException.class, observed);
            assertCanonicalTimeout(timeout);
            assertNull(decoderFailure.get());
            transitions.releaseTerminalSelection();
            assertTrue(decoderFailureObserved.await(1, TimeUnit.SECONDS));
            assertSame(timeout, decoderFailure.get());
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, followUp.reason());
            assertSame(timeout, followUp.getCause());
        } finally {
            transitions.releaseOutputTimeoutFailure();
            transitions.releaseTerminalSelection();
            protocol.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerInterruptBeforeDeadlineHasCanonicalFailureAndRestoresCallerStatus() throws Exception {
        BlockingUntilClosedInputStream stdout = new BlockingUntilClosedInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        OutputWaitTransitionProbe transitions = new OutputWaitTransitionProbe();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        AtomicReference<ProtocolSessionException> decoderFailure = new AtomicReference<>();
        CountDownLatch decoderFailureObserved = new CountDownLatch(1);
        IllegalStateException callbackFailure = new IllegalStateException("decoder failed after cancellation");
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readByte();
                } catch (ProtocolSessionException failure) {
                    decoderFailure.set(failure);
                    decoderFailureObserved.countDown();
                    throw callbackFailure;
                }
            }
        };
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofMinutes(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request("ignored"));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(transitions.awaitOutputWait(), "decoder did not enter the protocol output wait");
            callerThread.get().interrupt();

            ProtocolSessionException interrupted =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.FAILURE, interrupted.reason());
            assertEquals("Interrupted while decoding protocol response", interrupted.getMessage());
            assertInstanceOf(InterruptedException.class, interrupted.getCause());
            assertTrue(callerInterruptRestored.get());
            assertTrue(decoderFailureObserved.await(1, TimeUnit.SECONDS));
            assertSame(interrupted, decoderFailure.get());
            assertTrue(transitions.awaitLateCallbackFailure());
            assertIdentitySuppressedOnce(interrupted, callbackFailure);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(interrupted, followUp.getCause());
        } finally {
            protocol.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerInterruptDuringWriterSelectsCanonicalFailureBeforeCallbackFailure() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        IllegalStateException callbackFailure = new IllegalStateException("writer failed after interruption");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writerStarted.countDown();
                try {
                    releaseWriter.await();
                } catch (InterruptedException interruption) {
                    callbackInterrupted.countDown();
                    throw callbackFailure;
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
        ControllableProcess process = new ControllableProcess();
        LateCallbackTransitionProbe transitions = new LateCallbackTransitionProbe();
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request("request"));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(writerStarted.await(1, TimeUnit.SECONDS));

            callerThread.get().interrupt();
            ProtocolSessionException interrupted =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.FAILURE, interrupted.reason());
            assertEquals("Interrupted while writing protocol request", interrupted.getMessage());
            assertInstanceOf(InterruptedException.class, interrupted.getCause());
            assertTrue(callerInterruptRestored.get());
            assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(transitions.awaitLateCallbackFailure());
            assertIdentitySuppressedOnce(interrupted, callbackFailure);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("retry"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(interrupted, followUp.getCause());
        } finally {
            releaseWriter.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void serializedWaiterTimeoutBeforeAdmissionLeavesSessionReusableAndWritesNothing() throws Exception {
        assertRecoverableSerializedWaiterFailure(false);
    }

    @Test
    void serializedWaiterInterruptionBeforeAdmissionLeavesSessionReusableAndRestoresInterrupt() throws Exception {
        assertRecoverableSerializedWaiterFailure(true);
    }

    private static void assertRecoverableSerializedWaiterFailure(boolean interrupt) throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        AtomicInteger responses = new AtomicInteger();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (responses.getAndIncrement() == 0) {
                    firstResponseStarted.countDown();
                    awaitUninterruptibly(releaseFirstResponse);
                }
                return "response";
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        ControlledRequestLockWaiter lockWaiter = new ControlledRequestLockWaiter();
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                DefaultProtocolSession.TransitionProbe.none(),
                System::nanoTime,
                DefaultProtocolSession.ProtocolCallbackRunner.bounded(),
                lockWaiter);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
        try {
            Future<String> active = executor.submit(() -> protocol.request("active"));
            assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));
            Future<Throwable> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request("not-admitted"));
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(lockWaiter.awaitContended(), "second request did not enter the serialization wait");

            if (interrupt) {
                Objects.requireNonNull(waiterThread.get(), "waiterThread").interrupt();
            } else {
                lockWaiter.expire();
            }
            ProtocolSessionException failure =
                    assertInstanceOf(ProtocolSessionException.class, waiter.get(2, TimeUnit.SECONDS));

            assertEquals(
                    interrupt ? ProtocolSessionException.Reason.FAILURE : ProtocolSessionException.Reason.TIMEOUT,
                    failure.reason());
            if (interrupt) {
                assertEquals("Interrupted while waiting to start protocol request", failure.getMessage());
                assertInstanceOf(InterruptedException.class, failure.getCause());
            } else {
                assertEquals("Protocol request timed out", failure.getMessage());
                assertNull(failure.getCause());
            }
            assertEquals(interrupt, waiterInterruptRestored.get());
            assertTrue(process.isAlive());
            assertFalse(protocol.onExit().isDone());
            assertEquals("active\n", stdin.toString(StandardCharsets.UTF_8));

            releaseFirstResponse.countDown();
            assertEquals("response", active.get(2, TimeUnit.SECONDS));
            assertEquals("response", protocol.request("retry"));
            assertEquals("active\nretry\n", stdin.toString(StandardCharsets.UTF_8));
            assertTrue(process.isAlive());
            assertFalse(protocol.onExit().isDone());
        } finally {
            releaseFirstResponse.countDown();
            lockWaiter.expire();
            protocol.close();
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
    void fatalErrorSelectedBeforeSerializedWaiterLockTimeoutWinsByIdentity() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(true, false);
    }

    @Test
    void closedSelectedBeforeSerializedWaiterLockTimeoutWins() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(false, false);
    }

    private static void assertSelectedTerminalWinsSerializedWaiterFailure(boolean fatal, boolean interrupt)
            throws Exception {
        AssertionError fatalError = new AssertionError("fatal output failure selected while request waits");
        InputStream stdout = fatal ? new GatedErrorInputStream(fatalError) : new BlockingUntilClosedInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        SerializedWaiterTransitionProbe transitions = new SerializedWaiterTransitionProbe();
        ControlledRequestLockWaiter lockWaiter = new ControlledRequestLockWaiter();
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                session(process),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions,
                System::nanoTime,
                DefaultProtocolSession.ProtocolCallbackRunner.bounded(),
                lockWaiter);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> active = executor.submit(() -> captureFailure(() -> protocol.request("active")));
            assertTrue(transitions.awaitOutputWait(), "active request did not acquire the serialization lock");
            Future<Throwable> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request("waiter"));
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(lockWaiter.awaitContended(), "second request did not enter the serialization wait");

            if (fatal) {
                ((GatedErrorInputStream) stdout).releaseFailure();
                assertTrue(transitions.awaitTerminalSelection(), "fatal Error was not selected");
            } else {
                protocol.close();
            }
            assertTrue(
                    transitions.awaitRequestFailureReturn(),
                    "active request did not retain the serialization lock at final arbitration");
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
                ProtocolSessionException closed = assertInstanceOf(ProtocolSessionException.class, waiterFailure);
                assertEquals(ProtocolSessionException.Reason.CLOSED, closed.reason());
            }
            assertEquals(interrupt, waiterInterruptRestored.get());

            transitions.releaseRequestFailureReturn();
            Throwable activeFailure = active.get(2, TimeUnit.SECONDS);
            if (fatal) {
                assertSame(fatalError, activeFailure);
            } else {
                assertEquals(
                        ProtocolSessionException.Reason.CLOSED,
                        assertInstanceOf(ProtocolSessionException.class, activeFailure)
                                .reason());
            }
        } finally {
            lockWaiter.expire();
            if (stdout instanceof GatedErrorInputStream gated) {
                gated.releaseFailure();
            }
            transitions.releaseRequestFailureReturn();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeWriterCancellationSelectsClosedBeforeCallbackFailure() throws Exception {
        assertActiveCallbackCancellation(true);
    }

    @Test
    void activeDecoderCancellationSelectsClosedBeforeCallbackFailure() throws Exception {
        assertActiveCallbackCancellation(false);
    }

    private static void assertActiveCallbackCancellation(boolean writerPhase) throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        IllegalStateException callbackFailure =
                new IllegalStateException((writerPhase ? "writer" : "decoder") + " failed after cancellation");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (writerPhase) {
                    awaitCancellation();
                } else {
                    writer.flush();
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (!writerPhase) {
                    awaitCancellation();
                }
                return "unused";
            }

            private void awaitCancellation() {
                callbackStarted.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException interruption) {
                    callbackInterrupted.countDown();
                    throw callbackFailure;
                }
            }
        };
        ControllableProcess process = new ControllableProcess();
        LateCallbackTransitionProbe transitions = new LateCallbackTransitionProbe();
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            protocol.close();
            ProtocolSessionException closed =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.CLOSED, closed.reason());
            assertInstanceOf(BoundedTaskRunner.TaskCancelledException.class, closed.getCause());
            assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(transitions.awaitLateCallbackFailure());
            assertIdentitySuppressedOnce(closed, callbackFailure);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("retry"));
            assertEquals(ProtocolSessionException.Reason.CLOSED, followUp.reason());
        } finally {
            releaseCallback.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void zeroForeverStderrPumpStopsAfterClose() throws Exception {
        ZeroForeverInputStream stderr = new ZeroForeverInputStream();
        BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), InputStream.nullInputStream(), stderr);
        DefaultSession rawSession = session(process);
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, noOpAdapter(), ProtocolSessionSettings.defaults(), backoff);
        try {
            assertTrue(backoff.awaitEntered());
            assertEquals(1, stderr.reads(), "the pump must enter backoff before attempting another read");

            try {
                protocol.close();
            } finally {
                backoff.release();
            }

            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            Thread readerThread = stderr.readerThread();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(readerThread.isAlive(), "protocol pump thread must terminate after close");
            assertEquals(1, stderr.reads(), "close during backoff must prevent another read");
        } finally {
            try {
                backoff.release();
            } finally {
                protocol.close();
            }
        }
    }

    @Test
    void stdoutEofWhileProcessIsAliveIsClassifiedAsEofWithoutGraceDelay() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        OutputWaitTransitionProbe transitions = new OutputWaitTransitionProbe();
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                session(process),
                byteReadingAdapter(),
                ProtocolSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(transitions.awaitOutputWait());
            assertTrue(stdout.awaitReadEntered());

            stdout.releaseEof();
            ProtocolSessionException eof =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            assertTrue(eof.exitCode().isEmpty());
        } finally {
            stdout.releaseEof();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void processExitBeforeStdoutEofRemainsEofWhenCachedExitPublicationIsDelayed() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        CountDownLatch releaseExitWatcher = new CountDownLatch(1);
        AtomicReference<Thread> exitWatcher = new AtomicReference<>();
        DefaultSession.WatcherStarter watcherStarter = (name, task) -> {
            Thread thread = new Thread(
                    () -> {
                        awaitUninterruptibly(releaseExitWatcher);
                        task.run();
                    },
                    name);
            thread.setDaemon(true);
            exitWatcher.set(thread);
            thread.start();
            return thread;
        };
        DefaultSession rawSession = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "protocol-eof-test", CommandEcho.empty()),
                () -> {},
                new BoundedCloseDispatcher(2, 2, 4),
                watcherStarter);
        OutputWaitTransitionProbe transitions = new OutputWaitTransitionProbe();
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                byteReadingAdapter(),
                ProtocolSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(transitions.awaitOutputWait());
            assertTrue(stdout.awaitReadEntered());

            process.exitNaturally(17);
            assertFalse(rawSession.onExit().isDone(), "the exit observer must still be withheld");
            stdout.releaseEof();
            ProtocolSessionException eof =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            assertTrue(eof.exitCode().isEmpty());
        } finally {
            stdout.releaseEof();
            releaseExitWatcher.countDown();
            protocol.close();
            Thread watcher = exitWatcher.get();
            if (watcher != null) {
                watcher.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(watcher.isAlive());
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void naturalExitCodeIsObservedBeforeOutputCleanupPublishesExit() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        CountDownLatch exitPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseExitPublication = new CountDownLatch(1);
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.blockOnceOn(
                DiagnosticsSettings.disabled().withListener(ignored -> {}),
                "protocol-exit-observation-test",
                DiagnosticEventType.PROCESS_EXITED,
                exitPublicationEntered,
                releaseExitPublication);
        DefaultSession rawSession = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics,
                () -> {},
                new BoundedCloseDispatcher(2, 2, 4),
                DefaultSession.WatcherStarter.threading());
        OutputWaitTransitionProbe transitions = new OutputWaitTransitionProbe();
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                byteReadingAdapter(),
                ProtocolSessionSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(transitions.awaitOutputWait());
            assertTrue(stdout.awaitReadEntered());

            process.exitNaturally(17);
            assertTrue(exitPublicationEntered.await(1, TimeUnit.SECONDS));
            assertFalse(rawSession.onExit().isDone());
            assertFalse(protocol.onExit().isDone());

            stdout.releaseEof();
            ProtocolSessionException processExited =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, processExited.reason());
            assertEquals(17, processExited.exitCode().orElseThrow());
        } finally {
            stdout.releaseEof();
            releaseExitPublication.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void scannerSaturationDuringEofEnrichmentRetainsEof() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        process.blockLivenessQueries();
        Process guarded = ProcessTreeScannerTestSupport.guard(process, 1, Duration.ofSeconds(2));
        ProtocolAdapter<String, Byte> readOnlyAdapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {}

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };
        DefaultProtocolSession<String, Byte> protocol =
                new DefaultProtocolSession<>(session(guarded), readOnlyAdapter, ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(process.awaitLivenessQuery(), "exit watcher did not saturate the scanner");
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));

            stdout.releaseEof();
            ProtocolSessionException eof =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            assertTrue(eof.exitCode().isEmpty());
            assertEquals(0, process.exitValueCalls());
        } finally {
            stdout.releaseEof();
            process.releaseLivenessQueries();
            process.exitNaturally(143);
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static ProtocolAdapter<String, Byte> byteReadingAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };
    }

    private static void assertFatalErrorWinsTimeoutRace(boolean fatalErrorFirst) throws Exception {
        AssertionError fatalError = new AssertionError("fatal protocol output failure");
        LatchingFatalTranscriptCharset charset = new LatchingFatalTranscriptCharset(fatalError);
        GatedByteInputStream stdout = new GatedByteInputStream((byte) '!');
        BlockingUntilClosedInputStream stderr = new BlockingUntilClosedInputStream();
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        DefaultSession rawSession = session(process);
        TimeoutArbitrationProbe transitions = fatalErrorFirst
                ? TimeoutArbitrationProbe.blockTerminalSelection()
                : TimeoutArbitrationProbe.outerPublishesFirstAndBlocksRequestReturn();
        EventDrivenTimeoutCallbackRunner callbackRunner = new EventDrivenTimeoutCallbackRunner(fatalErrorFirst);
        AtomicReference<ProtocolSessionException> decoderTimeout = new AtomicReference<>();
        CountDownLatch decoderTimeoutObserved = new CountDownLatch(1);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readByte();
                } catch (ProtocolSessionException failure) {
                    decoderTimeout.set(failure);
                    decoderTimeoutObserved.countDown();
                    throw failure;
                }
            }
        };
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession,
                adapter,
                options(charset).withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                transitions,
                new PastDeadlineAfterWaitNanoTime(),
                callbackRunner);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("ignored")));
            assertTrue(transitions.awaitOutputWait(), "decoder did not enter the protocol output wait");

            if (fatalErrorFirst) {
                stdout.releaseByte();
                assertTrue(charset.awaitBeforeFailure(), "stdout pump did not reach the fatal decoder boundary");
                charset.releaseFailure();
                assertTrue(transitions.awaitTerminalSelection(), "fatal Error did not select the terminal outcome");
                assertNotSame(transitions.decoderThread(), transitions.terminalSelector());
                callbackRunner.expireDeadline();
            } else {
                callbackRunner.expireDeadline();
                assertTrue(transitions.awaitTerminalSelection(), "timeout did not select the terminal outcome");
                assertNotSame(transitions.decoderThread(), transitions.terminalSelector());
                transitions.releaseTerminalSelection();
                // The decoder can observe the selected timeout through either its interrupted wait or the
                // terminal queue event. Only the former crosses the output-timeout probe.
                transitions.releaseOutputTimeoutFailure();
            }

            assertTrue(decoderTimeoutObserved.await(1, TimeUnit.SECONDS), "decoder did not observe its timeout");
            ProtocolSessionException timeout = decoderTimeout.get();
            assertCanonicalTimeout(timeout);

            if (!fatalErrorFirst) {
                assertTrue(
                        transitions.awaitRequestFailureReturn(),
                        "request did not reach its failure linearization point");
                stdout.releaseByte();
                assertTrue(charset.awaitBeforeFailure(), "stdout pump did not reach the fatal decoder boundary");
                charset.releaseFailure();
                assertTrue(transitions.awaitFatalPromotion(), "fatal Error did not replace the timeout outcome");
                protocol.onExit().get(1, TimeUnit.SECONDS);
            }
            transitions.releaseRequestFailureReturn();
            transitions.releaseTerminalSelection();

            Throwable observed = request.get(2, TimeUnit.SECONDS);
            assertSame(fatalError, observed);
            assertIdentitySuppressedOnce(fatalError, timeout);
            assertFailureGraphDoesNotContain(timeout, fatalError);
            assertEquals(1, fatalError.getSuppressed().length);

            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertTrue(callbackRunner.awaitDecoderStopped());
            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("again"));
            assertSame(fatalError, followUp);
        } finally {
            stdout.releaseByte();
            charset.releaseFailure();
            transitions.releaseOutputTimeoutFailure();
            transitions.releaseRequestFailureReturn();
            transitions.releaseTerminalSelection();
            try {
                protocol.close();
                stdout.close();
                stderr.close();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                assertTrue(callbackRunner.awaitDecoderStopped());
            }
        }
    }

    private static void assertResponseLimitAndFatalErrorAreArbitrated(boolean responseFailureFirst) throws Exception {
        AssertionError fatalError = new AssertionError("fatal protocol stderr decoder failure");
        RacingProtocolDecoderCharset charset = new RacingProtocolDecoderCharset(fatalError);
        GatedByteInputStream stdout = new GatedByteInputStream((byte) 'x');
        GatedByteInputStream stderr = new GatedByteInputStream((byte) '!');
        CountingOutputStream stdin = new CountingOutputStream();
        AtomicReference<ProtocolSessionException> observedResponseFailure = new AtomicReference<>();
        CountDownLatch responseFailureCaught = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.write(new byte[] {1});
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readLine(1);
                    throw new AssertionError("response limit was not enforced");
                } catch (ProtocolSessionException failure) {
                    observedResponseFailure.set(failure);
                    responseFailureCaught.countDown();
                    awaitUninterruptibly(allowCallbackReturn);
                    return "fallback";
                }
            }
        };
        ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
        DefaultSession rawSession = session(process);
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, options(charset));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(stdin.awaitWrite(), "the request must be active before either output failure");

            stdout.releaseByte();
            assertTrue(charset.awaitResponseDecoder(), "response decoder did not reach its controlled boundary");
            if (responseFailureFirst) {
                charset.releaseResponseDecoder();
                assertTrue(
                        responseFailureCaught.await(1, TimeUnit.SECONDS),
                        "response limit must occupy the terminal outcome before stderr fails");
            }

            stderr.releaseByte();
            assertTrue(charset.awaitFatalDecoder(), "stderr decoder did not reach its controlled boundary");
            charset.releaseFatalDecoder();
            if (!responseFailureFirst) {
                protocol.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
                charset.releaseResponseDecoder();
                assertTrue(
                        responseFailureCaught.await(1, TimeUnit.SECONDS),
                        "the later response limit must still be observed by the adapter");
            }

            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            allowCallbackReturn.countDown();

            Throwable thrown = request.get(2, TimeUnit.SECONDS);
            ProtocolSessionException responseFailure = observedResponseFailure.get();
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
            assertSame(fatalError, thrown);
            assertIdentitySuppressedOnce(fatalError, responseFailure);
            assertFailureGraphDoesNotContain(responseFailure, fatalError);

            int writesAfterFailure = stdin.writeCalls();
            Throwable followUp = captureFailure(() -> protocol.request("retry"));
            assertSame(fatalError, followUp);
            assertEquals(writesAfterFailure, stdin.writeCalls());
        } finally {
            stdout.releaseByte();
            stderr.releaseByte();
            charset.releaseResponseDecoder();
            charset.releaseFatalDecoder();
            allowCallbackReturn.countDown();
            try {
                protocol.close();
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
            assertNotSame(forbidden, current, "failure graph must not contain a suppression cycle");
            if (visited.put(current, Boolean.TRUE) != null) {
                continue;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            pending.addAll(List.of(current.getSuppressed()));
        }
    }

    private static void assertCaughtRuntimeDelegateFailureIsTerminal(RuntimeException writeFailure) throws Exception {
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write("abcd");
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults())) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(2, observed.size());
            assertSame(observed.get(0), observed.get(1));
            assertSame(failure, observed.get(0));
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
        }
    }

    private static ProtocolSessionSettings options(Charset charset) {
        return ProtocolSessionSettings.defaults().withCharsetPolicy(CharsetPolicy.report(charset));
    }

    private static <I, O> DefaultProtocolSession<I, O> protocolWithCallbackRunner(
            ProtocolAdapter<I, O> adapter, DefaultProtocolSession.ProtocolCallbackRunner callbackRunner) {
        return new DefaultProtocolSession<>(
                session(new ControllableProcess()),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                DefaultProtocolSession.TransitionProbe.none(),
                System::nanoTime,
                callbackRunner);
    }

    private static ProtocolAdapter<String, String> noOpAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
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
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "protocol-cleanup-test", CommandEcho.empty()),
                () -> {},
                closeDispatcher,
                DefaultSession.WatcherStarter.threading());
    }

    private static final class AtomicRequestLinesCharset extends Charset {

        private AtomicRequestLinesCharset() {
            super("X-Procwright-Atomic-Request-Lines", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 4) {
                private boolean emitted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining() || emitted) {
                        return CoderResult.UNDERFLOW;
                    }
                    if (output.remaining() < 4) {
                        return CoderResult.OVERFLOW;
                    }
                    input.get();
                    output.put("a\nb\n");
                    emitted = true;
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class DecoderCreationFailureCharset extends Charset {

        private final int failingCreation;
        private final Throwable failure;
        private int decoderCreations;

        private DecoderCreationFailureCharset(int failingCreation, Throwable failure) {
            super(
                    "X-Procwright-Protocol-Decoder-Creation-" + failingCreation + "-"
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

    private static final class LatchingFatalTranscriptCharset extends Charset {

        private final AssertionError failure;
        private final AtomicInteger decoderCreations = new AtomicInteger();
        private final CountDownLatch beforeFailure = new CountDownLatch(1);
        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        private LatchingFatalTranscriptCharset(AssertionError failure) {
            super("X-Procwright-Protocol-Fatal-Transcript-Decoder", new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (decoderCreations.getAndIncrement() >= 2) {
                return passthroughDecoder(this);
            }
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

    private static final class RacingProtocolDecoderCharset extends Charset {

        private final AssertionError fatalError;
        private final AtomicInteger decoderCreations = new AtomicInteger();
        private final CountDownLatch responseDecoderEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResponseDecoder = new CountDownLatch(1);
        private final CountDownLatch fatalDecoderEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFatalDecoder = new CountDownLatch(1);

        private RacingProtocolDecoderCharset(AssertionError fatalError) {
            super("X-Procwright-Protocol-First-Outcome-Race", new String[0]);
            this.fatalError = fatalError;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            int creation = decoderCreations.incrementAndGet();
            return switch (creation) {
                case 1, 4 -> passthroughDecoder(this);
                case 2 -> delayedFatalDecoder();
                case 3 -> delayedResponseDecoder();
                default -> throw new AssertionError("unexpected decoder creation " + creation);
            };
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

    private static final class EncoderCreationFailureCharset extends Charset {

        private final Throwable failure;
        private int encoderCreations;

        private EncoderCreationFailureCharset(Throwable failure) {
            super("X-Procwright-Protocol-Encoder-Creation-" + failure.getClass().getSimpleName(), new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations++;
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }

        private int encoderCreations() {
            return encoderCreations;
        }
    }

    private static final class NoProgressEncoderCharset extends Charset {

        private int encoderCreations;

        private NoProgressEncoderCharset() {
            super("X-Procwright-Protocol-No-Progress-Encoder", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations++;
            return new CharsetEncoder(this, 1, 1) {
                @Override
                protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
                    return CoderResult.OVERFLOW;
                }
            };
        }

        private int encoderCreations() {
            return encoderCreations;
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

    private static final class GatedEofInputStream extends InputStream {

        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseEof = new CountDownLatch(1);

        @Override
        public int read() {
            readEntered.countDown();
            awaitUninterruptibly(releaseEof);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        private boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseEof() {
            releaseEof.countDown();
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

    private static final class GatedChunkInputStream extends InputStream {

        private final byte[] chunk;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch eof = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();

        private GatedChunkInputStream(byte[] chunk) {
            this.chunk = chunk.clone();
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            awaitUninterruptibly(release);
            if (!delivered.compareAndSet(false, true)) {
                eof.countDown();
                return -1;
            }
            int count = Math.min(length, chunk.length);
            System.arraycopy(chunk, 0, bytes, offset, count);
            return count;
        }

        private void release() {
            release.countDown();
        }

        private boolean awaitEof() throws InterruptedException {
            return eof.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class LatchingTransitionProbe implements DefaultProtocolSession.TransitionProbe {

        private final CountDownLatch writeAdmission = new CountDownLatch(1);
        private final CountDownLatch releaseWriteAdmission = new CountDownLatch(1);
        private final CountDownLatch terminalSelection = new CountDownLatch(1);
        private final CountDownLatch releaseTerminalSelection = new CountDownLatch(1);

        @Override
        public void beforeWriteAdmission() {
            writeAdmission.countDown();
            awaitUninterruptibly(releaseWriteAdmission);
        }

        @Override
        public void afterTerminalSelection() {
            terminalSelection.countDown();
            awaitUninterruptibly(releaseTerminalSelection);
        }

        private boolean awaitWriteAdmission() throws InterruptedException {
            return writeAdmission.await(1, TimeUnit.SECONDS);
        }

        private void releaseWriteAdmission() {
            releaseWriteAdmission.countDown();
        }

        private boolean awaitTerminalSelection() throws InterruptedException {
            return terminalSelection.await(1, TimeUnit.SECONDS);
        }

        private void releaseTerminalSelection() {
            releaseTerminalSelection.countDown();
        }
    }

    private static final class LateCallbackTransitionProbe implements DefaultProtocolSession.TransitionProbe {

        private final CountDownLatch lateCallbackFailure = new CountDownLatch(1);

        @Override
        public void beforeWriteAdmission() {}

        @Override
        public void afterTerminalSelection() {}

        @Override
        public void afterLateCallbackFailure() {
            lateCallbackFailure.countDown();
        }

        private boolean awaitLateCallbackFailure() throws InterruptedException {
            return lateCallbackFailure.await(2, TimeUnit.SECONDS);
        }
    }

    private static final class OutputWaitTransitionProbe implements DefaultProtocolSession.TransitionProbe {

        private final CountDownLatch outputWaitEntered = new CountDownLatch(1);
        private final CountDownLatch lateCallbackFailure = new CountDownLatch(1);
        private volatile Thread decoderThread;

        @Override
        public void beforeWriteAdmission() {}

        @Override
        public void afterTerminalSelection() {}

        @Override
        public void beforeOutputWait() {
            decoderThread = Thread.currentThread();
            outputWaitEntered.countDown();
        }

        @Override
        public void afterLateCallbackFailure() {
            lateCallbackFailure.countDown();
        }

        private boolean awaitOutputWait() throws InterruptedException {
            return outputWaitEntered.await(2, TimeUnit.SECONDS);
        }

        private Thread decoderThread() {
            return decoderThread;
        }

        private boolean awaitLateCallbackFailure() throws InterruptedException {
            return lateCallbackFailure.await(2, TimeUnit.SECONDS);
        }
    }

    private static final class CloseFatalTransitionProbe implements DefaultProtocolSession.TransitionProbe {

        private final CountDownLatch outputWaitEntered = new CountDownLatch(1);
        private final CountDownLatch requestFailureReturn = new CountDownLatch(1);
        private final CountDownLatch releaseRequestFailureReturn = new CountDownLatch(1);
        private final CountDownLatch fatalPromotion = new CountDownLatch(1);

        @Override
        public void beforeWriteAdmission() {}

        @Override
        public void afterTerminalSelection() {}

        @Override
        public void beforeOutputWait() {
            outputWaitEntered.countDown();
        }

        @Override
        public void beforeRequestFailureReturn() {
            requestFailureReturn.countDown();
            awaitUninterruptibly(releaseRequestFailureReturn);
        }

        @Override
        public void afterFatalPromotion() {
            fatalPromotion.countDown();
        }

        private boolean awaitOutputWait() throws InterruptedException {
            return outputWaitEntered.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitRequestFailureReturn() throws InterruptedException {
            return requestFailureReturn.await(1, TimeUnit.SECONDS);
        }

        private void releaseRequestFailureReturn() {
            releaseRequestFailureReturn.countDown();
        }

        private boolean awaitFatalPromotion() throws InterruptedException {
            return fatalPromotion.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class SerializedWaiterTransitionProbe implements DefaultProtocolSession.TransitionProbe {

        private final CountDownLatch outputWait = new CountDownLatch(1);
        private final CountDownLatch terminalSelection = new CountDownLatch(1);
        private final CountDownLatch requestFailureReturn = new CountDownLatch(1);
        private final CountDownLatch releaseRequestFailureReturn = new CountDownLatch(1);

        @Override
        public void beforeWriteAdmission() {}

        @Override
        public void afterTerminalSelection() {
            terminalSelection.countDown();
        }

        @Override
        public void beforeOutputWait() {
            outputWait.countDown();
        }

        @Override
        public void beforeRequestFailureReturn() {
            requestFailureReturn.countDown();
            awaitUninterruptibly(releaseRequestFailureReturn);
        }

        private boolean awaitOutputWait() throws InterruptedException {
            return outputWait.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitTerminalSelection() throws InterruptedException {
            return terminalSelection.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitRequestFailureReturn() throws InterruptedException {
            return requestFailureReturn.await(1, TimeUnit.SECONDS);
        }

        private void releaseRequestFailureReturn() {
            releaseRequestFailureReturn.countDown();
        }
    }

    private static final class ControlledRequestLockWaiter implements DefaultProtocolSession.RequestLockWaiter {

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

    private static final class TimeoutArbitrationProbe implements DefaultProtocolSession.TransitionProbe {

        private final boolean blockOutputTimeoutFailure;
        private final boolean blockTerminalSelection;
        private final boolean blockRequestFailureReturn;
        private final CountDownLatch outputWaitEntered = new CountDownLatch(1);
        private final CountDownLatch outputTimeoutFailure = new CountDownLatch(1);
        private final CountDownLatch releaseOutputTimeoutFailure = new CountDownLatch(1);
        private final CountDownLatch terminalSelection = new CountDownLatch(1);
        private final CountDownLatch releaseTerminalSelection = new CountDownLatch(1);
        private final CountDownLatch requestFailureReturn = new CountDownLatch(1);
        private final CountDownLatch releaseRequestFailureReturn = new CountDownLatch(1);
        private final CountDownLatch fatalPromotion = new CountDownLatch(1);
        private volatile Thread decoderThread;
        private volatile Thread terminalSelector;

        private TimeoutArbitrationProbe(
                boolean blockOutputTimeoutFailure, boolean blockTerminalSelection, boolean blockRequestFailureReturn) {
            this.blockOutputTimeoutFailure = blockOutputTimeoutFailure;
            this.blockTerminalSelection = blockTerminalSelection;
            this.blockRequestFailureReturn = blockRequestFailureReturn;
        }

        private static TimeoutArbitrationProbe outerPublishesFirst() {
            return new TimeoutArbitrationProbe(true, true, false);
        }

        private static TimeoutArbitrationProbe outerPublishesFirstAndBlocksRequestReturn() {
            return new TimeoutArbitrationProbe(true, true, true);
        }

        private static TimeoutArbitrationProbe blockTerminalSelection() {
            return new TimeoutArbitrationProbe(false, true, false);
        }

        @Override
        public void beforeWriteAdmission() {}

        @Override
        public void afterTerminalSelection() {
            terminalSelector = Thread.currentThread();
            terminalSelection.countDown();
            if (blockTerminalSelection) {
                awaitUninterruptibly(releaseTerminalSelection);
            }
        }

        @Override
        public void beforeOutputWait() {
            decoderThread = Thread.currentThread();
            outputWaitEntered.countDown();
        }

        @Override
        public void beforeOutputTimeoutFailure() {
            outputTimeoutFailure.countDown();
            if (blockOutputTimeoutFailure) {
                awaitUninterruptibly(releaseOutputTimeoutFailure);
            }
        }

        @Override
        public void beforeRequestFailureReturn() {
            requestFailureReturn.countDown();
            if (blockRequestFailureReturn) {
                awaitUninterruptibly(releaseRequestFailureReturn);
            }
        }

        @Override
        public void afterFatalPromotion() {
            fatalPromotion.countDown();
        }

        private boolean awaitOutputWait() throws InterruptedException {
            return outputWaitEntered.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitOutputTimeoutFailure() throws InterruptedException {
            return outputTimeoutFailure.await(2, TimeUnit.SECONDS);
        }

        private void releaseOutputTimeoutFailure() {
            releaseOutputTimeoutFailure.countDown();
        }

        private boolean awaitRequestFailureReturn() throws InterruptedException {
            return requestFailureReturn.await(2, TimeUnit.SECONDS);
        }

        private void releaseRequestFailureReturn() {
            releaseRequestFailureReturn.countDown();
        }

        private boolean awaitFatalPromotion() throws InterruptedException {
            return fatalPromotion.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitTerminalSelection() throws InterruptedException {
            return terminalSelection.await(2, TimeUnit.SECONDS);
        }

        private void releaseTerminalSelection() {
            releaseTerminalSelection.countDown();
        }

        private Thread decoderThread() {
            return decoderThread;
        }

        private Thread terminalSelector() {
            return terminalSelector;
        }
    }

    private static final class PastDeadlineAfterWaitNanoTime implements java.util.function.LongSupplier {

        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public long getAsLong() {
            long now = System.nanoTime();
            return reads.getAndIncrement() == 0 ? now - Duration.ofHours(1).toNanos() : Long.MAX_VALUE;
        }
    }

    private static final class EventDrivenTimeoutCallbackRunner
            implements DefaultProtocolSession.ProtocolCallbackRunner {

        private final CompletableFuture<Void> deadlineElapsed = new CompletableFuture<>();
        private final CountDownLatch decoderStopped = new CountDownLatch(1);
        private final boolean awaitDecoderAfterExpiry;

        private EventDrivenTimeoutCallbackRunner() {
            this(false);
        }

        private EventDrivenTimeoutCallbackRunner(boolean awaitDecoderAfterExpiry) {
            this.awaitDecoderAfterExpiry = awaitDecoderAfterExpiry;
        }

        @Override
        public <T> T run(
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.CancellationSignal cancellation,
                BoundedTaskRunner.LateFailureHandler lateFailureHandler,
                BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
                BoundedTaskRunner.Task<T> task)
                throws TimeoutException, InterruptedException, ExecutionException,
                        BoundedTaskRunner.TaskCancelledException {
            if (!threadPrefix.startsWith("procwright-protocol-decoder-")) {
                return DefaultProtocolSession.ProtocolCallbackRunner.bounded()
                        .run(threadPrefix, deadlineNanos, cancellation, lateFailureHandler, abandonmentHandler, task);
            }

            CompletableFuture<CallbackOutcome<T>> completion = new CompletableFuture<>();
            LateCallbackFailurePublication lateFailure = new LateCallbackFailurePublication(lateFailureHandler);
            Thread decoder = new Thread(
                    () -> {
                        try {
                            completion.complete(CallbackOutcome.completed(task.run()));
                        } catch (Throwable failure) {
                            lateFailure.record(Thread.currentThread(), failure);
                            completion.complete(CallbackOutcome.failed(failure));
                        } finally {
                            decoderStopped.countDown();
                        }
                    },
                    threadPrefix + "event-driven-test");
            decoder.setDaemon(true);
            decoder.start();

            CompletableFuture<Boolean> completed = completion.thenApply(ignored -> false);
            CompletableFuture<Boolean> expired = deadlineElapsed.thenApply(ignored -> true);
            boolean timedOut;
            try {
                timedOut = (Boolean) CompletableFuture.anyOf(completed, expired).get();
            } catch (ExecutionException impossible) {
                throw new AssertionError("test callback race completed exceptionally", impossible);
            }
            if (timedOut) {
                TimeoutException timeout = new TimeoutException("event-driven protocol callback deadline elapsed");
                abandonmentHandler.beforeInterrupt(timeout);
                lateFailure.abandon();
                decoder.interrupt();
                if (awaitDecoderAfterExpiry) {
                    completion.get();
                }
                throw timeout;
            }

            CallbackOutcome<T> outcome = completion.getNow(null);
            if (outcome.failure() != null) {
                throw new ExecutionException(outcome.failure());
            }
            return outcome.value();
        }

        private void expireDeadline() {
            deadlineElapsed.complete(null);
        }

        private boolean awaitDecoderStopped() throws InterruptedException {
            return decoderStopped.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class SaturatingProtocolCallbackRunner
            implements DefaultProtocolSession.ProtocolCallbackRunner {

        private final BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch firstCallbackStarted;

        private SaturatingProtocolCallbackRunner(CountDownLatch firstCallbackStarted) {
            this.firstCallbackStarted = Objects.requireNonNull(firstCallbackStarted, "firstCallbackStarted");
        }

        @Override
        public <T> T run(
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.CancellationSignal cancellation,
                BoundedTaskRunner.LateFailureHandler lateFailureHandler,
                BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
                BoundedTaskRunner.Task<T> task)
                throws TimeoutException, InterruptedException, ExecutionException {
            int invocation = invocations.incrementAndGet();
            java.util.function.LongSupplier nanoTime =
                    switch (invocation) {
                        case 1 -> new DeadlineAfterCallbackStartNanoTime(deadlineNanos, firstCallbackStarted);
                        case 2 -> () -> deadlineNanos;
                        default -> System::nanoTime;
                    };
            return BoundedTaskRunner.runTracked(
                    limiter,
                    threadPrefix,
                    deadlineNanos,
                    new BoundedTaskRunner.TaskHandoff(),
                    (prefix, callback) -> {
                        Thread thread = new Thread(callback, prefix + "saturation-test");
                        thread.setDaemon(true);
                        return thread;
                    },
                    nanoTime,
                    task);
        }

        private int availablePermits() {
            return limiter.availablePermits();
        }
    }

    private static final class DeadlineAfterCallbackStartNanoTime implements java.util.function.LongSupplier {

        private final long deadlineNanos;
        private final CountDownLatch callbackStarted;
        private final AtomicInteger reads = new AtomicInteger();

        private DeadlineAfterCallbackStartNanoTime(long deadlineNanos, CountDownLatch callbackStarted) {
            this.deadlineNanos = deadlineNanos;
            this.callbackStarted = callbackStarted;
        }

        @Override
        public long getAsLong() {
            if (reads.incrementAndGet() < 3) {
                return 0L;
            }
            awaitUninterruptibly(callbackStarted);
            return deadlineNanos;
        }
    }

    private static final class LateCallbackFailurePublication {

        private final BoundedTaskRunner.LateFailureHandler handler;
        private final AtomicReference<Thread> callbackThread = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();

        private LateCallbackFailurePublication(BoundedTaskRunner.LateFailureHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        private void record(Thread thread, Throwable taskFailure) {
            callbackThread.set(thread);
            failure.set(taskFailure);
            publishIfReady();
        }

        private void abandon() {
            abandoned.set(true);
            publishIfReady();
        }

        private void publishIfReady() {
            Thread thread = callbackThread.get();
            Throwable taskFailure = failure.get();
            if (abandoned.get() && thread != null && taskFailure != null && published.compareAndSet(false, true)) {
                handler.handle(thread, taskFailure);
            }
        }
    }

    private record CallbackOutcome<T>(T value, Throwable failure) {

        private static <T> CallbackOutcome<T> completed(T value) {
            return new CallbackOutcome<>(value, null);
        }

        private static <T> CallbackOutcome<T> failed(Throwable failure) {
            return new CallbackOutcome<>(null, Objects.requireNonNull(failure, "failure"));
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

    private static final class ZeroForeverInputStream extends InputStream {

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
        }

        private int reads() {
            return reads.get();
        }

        private Thread readerThread() {
            return readerThread;
        }
    }

    private static final class ZeroThenByteInputStream extends InputStream {

        private final byte value;
        private int reads;

        private ZeroThenByteInputStream(byte value) {
            this.value = value;
        }

        @Override
        public int read() {
            if (reads++ == 0) {
                return value & 0xff;
            }
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (reads++ == 0) {
                return 0;
            }
            if (reads == 2) {
                bytes[offset] = value;
                return 1;
            }
            return -1;
        }
    }

    private static final class ControllableProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final OutputStream stdin;
        private final InputStream stdout;
        private final InputStream stderr;
        private final AtomicInteger exitValueCalls = new AtomicInteger();
        private volatile CountDownLatch livenessQueryEntered;
        private volatile CountDownLatch releaseLivenessQuery;

        private ControllableProcess() {
            this(OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        }

        private ControllableProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
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
            exitValueCalls.incrementAndGet();
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
            CountDownLatch entered = livenessQueryEntered;
            CountDownLatch release = releaseLivenessQuery;
            if (entered != null && release != null) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
            return alive.get();
        }

        private void blockLivenessQueries() {
            livenessQueryEntered = new CountDownLatch(1);
            releaseLivenessQuery = new CountDownLatch(1);
        }

        private boolean awaitLivenessQuery() throws InterruptedException {
            CountDownLatch entered = livenessQueryEntered;
            return entered != null && entered.await(1, TimeUnit.SECONDS);
        }

        private void releaseLivenessQueries() {
            CountDownLatch release = releaseLivenessQuery;
            if (release != null) {
                release.countDown();
            }
        }

        private int exitValueCalls() {
            return exitValueCalls.get();
        }

        private void exitNaturally(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
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

    private static void assertRejectedFromForeignThread(Runnable operation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign =
                new Thread(() -> failure.set(captureFailure(operation)), "procwright-protocol-capability-scope-test");
        foreign.setDaemon(true);
        foreign.start();
        try {
            foreign.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining the capability-scope test", exception);
        }
        assertFalse(foreign.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    private static void assertCanonicalTimeout(ProtocolSessionException timeout) {
        assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
        assertEquals("Protocol request timed out", timeout.getMessage());
        TimeoutException cause = assertInstanceOf(TimeoutException.class, timeout.getCause());
        assertEquals("Protocol request deadline elapsed", cause.getMessage());
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
}

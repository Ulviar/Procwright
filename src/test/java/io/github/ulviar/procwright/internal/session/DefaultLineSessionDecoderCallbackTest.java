/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class DefaultLineSessionDecoderCallbackTest extends DefaultLineSessionDecoderCallbackTestSupport {

    @Test
    void abandonedDecoderFailureDoesNotChangeTimeoutAndReleasesProtocolCapacity() throws Exception {
        for (Throwable lateFailure : List.of(
                new IllegalStateException("late line decoder runtime failure"),
                new AssertionError("late line decoder error"))) {
            assertAbandonedDecoderFailureIsIsolated(lateFailure);
        }
    }

    private static void assertAbandonedDecoderFailureIsIsolated(Throwable lateFailure) throws Exception {
        int initialCapacity = BoundedTaskLimits.PROTOCOL_CALLBACKS.availablePermits();
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
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
            assertEquals(initialCapacity - 1, BoundedTaskLimits.PROTOCOL_CALLBACKS.availablePermits());

            releaseDecoder.countDown();
            assertTrue(eventually(() -> BoundedTaskLimits.PROTOCOL_CALLBACKS.availablePermits() == initialCapacity));
            LineSessionException persisted = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.request("after-timeout", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.TIMEOUT, persisted.reason());
        } finally {
            releaseDecoder.countDown();
            lineSession.close();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> BoundedTaskLimits.PROTOCOL_CALLBACKS.availablePermits() == initialCapacity));
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
                LineSessionTestDependencies.withNanoTime(() -> nanoTime.getAndSet(50)));
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

            assertThrows(IllegalStateException.class, retained.get()::readLine);

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
}

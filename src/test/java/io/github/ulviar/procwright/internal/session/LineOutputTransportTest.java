/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.openSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LineOutputTransportTest {

    @Test
    void backlogOverflowWakesAWaitingRequest() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults().withStdoutBacklogChars(1);
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        LineSessionState.Request request = state.beginRequest();
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                new ByteArrayInputStream("oversized\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());
        DefaultSession session = openSession(process);
        LineOutputTransport transport = transport(options, state, session);
        AtomicReference<LineOutputTransport.Event> returned = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                returned.set(
                        transport.take(System.nanoTime() + Duration.ofSeconds(5).toNanos(), request));
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });

        try {
            waiter.start();
            assertTrue(awaitState(waiter, Thread.State.TIMED_WAITING));
            transport.start(PumpStarter.threading());
            waiter.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(waiter.isAlive());
            assertNull(thrown.get());
            LineOutputTransport.FailureEvent failure =
                    assertInstanceOf(LineOutputTransport.FailureEvent.class, returned.get());
            assertEquals(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, failure.reason());
        } finally {
            waiter.interrupt();
            transport.closeReaders();
            process.complete(0);
            session.close();
            waiter.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    void interruptionWinsAnEventQueuedBeforeTheWaiterReacquiresTheEventLock() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        LineSessionState.Request request = state.beginRequest();
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession session = openSession(process);
        LineOutputTransport transport = transport(options, state, session);
        AtomicReference<LineOutputTransport.Event> returned = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                returned.set(
                        transport.take(System.nanoTime() + Duration.ofSeconds(5).toNanos(), request));
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });

        try {
            waiter.start();
            assertTrue(awaitState(waiter, Thread.State.TIMED_WAITING));
            synchronized (eventLock(transport)) {
                waiter.interrupt();
                assertTrue(awaitState(waiter, Thread.State.BLOCKED));
                transport.closeReaders();
            }
            waiter.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(waiter.isAlive());
            assertNull(returned.get());
            LineSessionException failure = assertInstanceOf(LineSessionException.class, thrown.get());
            assertInstanceOf(InterruptedException.class, failure.getCause());
        } finally {
            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(1));
            process.complete(0);
            session.close();
        }
    }

    @Test
    void losingTerminalFailureDoesNotHoldTheEventQueueLockOrMutateTheWinner() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession session = openSession(process);
        LineOutputTransport transport = transport(options, state, session);
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            transport.publishFailure(LineSessionException.Reason.FAILURE, "secondary", secondary);
            transport.closeReaders();
        } finally {
            process.complete(0);
            session.close();
        }

        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, secondary.getSuppressed().length);
    }

    @Test
    void losingFailureConsumerCannotHoldTheEventQueueLock() throws Exception {
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), ignored -> {
            consumerEntered.countDown();
            awaitUninterruptibly(releaseConsumer);
        });
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        LineSessionState.Request request = state.beginRequest();
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession session = openSession(process);
        LineOutputTransport transport = transport(LineSessionSettings.defaults(), state, session);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publication = executor.submit(
                    () -> transport.publishFailure(LineSessionException.Reason.FAILURE, "secondary", secondary));
            assertTrue(consumerEntered.await(1, TimeUnit.SECONDS));

            Future<LineOutputTransport.Event> read = executor.submit(() ->
                    transport.take(System.nanoTime() + Duration.ofSeconds(1).toNanos(), request));
            assertSame(
                    primary,
                    assertInstanceOf(LineOutputTransport.FailureEvent.class, read.get(1, TimeUnit.SECONDS))
                            .failure());

            releaseConsumer.countDown();
            publication.get(1, TimeUnit.SECONDS);
        } finally {
            releaseConsumer.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            process.complete(0);
            session.close();
        }
    }

    @Test
    void backlogOverflowReleasesTheEventQueueBeforeRoutingTheLosingFailure() throws Exception {
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), ignored -> {
            consumerEntered.countDown();
            awaitUninterruptibly(releaseConsumer);
        });
        state.recordTerminalFailure(
                LineSessionException.Reason.FAILURE, "primary", new IllegalStateException("primary"));
        LineSessionState.Request request = state.beginRequest();
        LineSessionSettings options = LineSessionSettings.defaults().withStdoutBacklogChars(1);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                new ByteArrayInputStream("overflow\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());
        DefaultSession session = openSession(process);
        LineOutputTransport transport = transport(options, state, session);
        try {
            transport.start(PumpStarter.threading());
            assertTrue(consumerEntered.await(1, TimeUnit.SECONDS));

            LineOutputTransport.FailureEvent event = assertInstanceOf(
                    LineOutputTransport.FailureEvent.class,
                    transport.take(System.nanoTime() + Duration.ofSeconds(1).toNanos(), request));

            assertEquals(LineSessionException.Reason.FAILURE, event.reason());
            assertEquals("primary", event.message());
        } finally {
            releaseConsumer.countDown();
            transport.closeReaders();
            process.complete(0);
            session.close();
        }
    }

    @Test
    void failureEventDoesNotWaitForOrMutateAnEarlierTerminalFailure() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        LineSessionState.Request request = state.beginRequest();
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession session = openSession(process);
        LineOutputTransport transport = transport(options, state, session);
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            transport.publishFailure(LineSessionException.Reason.FAILURE, "secondary", secondary);
            LineOutputTransport.Event event =
                    transport.take(System.nanoTime() + Duration.ofSeconds(1).toNanos(), request);
            assertSame(
                    primary,
                    assertInstanceOf(LineOutputTransport.FailureEvent.class, event)
                            .failure());
        } finally {
            process.complete(0);
            session.close();
        }

        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, secondary.getSuppressed().length);
    }

    private static LineOutputTransport transport(
            LineSessionSettings options, LineSessionState state, DefaultSession session) {
        return new LineOutputTransport(
                options,
                state,
                ZeroReadBackoff.exponential(),
                new OutputPumpCoordinator(session, "line-output-test"),
                new BoundedTranscriptBuffer(options.transcriptLimit()),
                new AtomicBoolean(),
                decoder(options),
                decoder(options),
                new NoOpFailureHandler());
    }

    private static IncrementalTextDecoder decoder(LineSessionSettings options) {
        return new IncrementalTextDecoder(
                options.charsetPolicy(), IncrementalTextDecoder.pendingByteLimitFor(options.maxLineChars()));
    }

    private static Object eventLock(LineOutputTransport transport) throws ReflectiveOperationException {
        Field field = LineOutputTransport.class.getDeclaredField("eventLock");
        field.setAccessible(true);
        return field.get(transport);
    }

    private static boolean awaitState(Thread thread, Thread.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() - deadline < 0) {
            if (thread.getState() == expected) {
                return true;
            }
            Thread.onSpinWait();
        }
        return thread.getState() == expected;
    }

    private static final class NoOpFailureHandler implements LineOutputTransport.FailureHandler {

        @Override
        public void failRuntime(LineSessionException.Reason reason, String message, RuntimeException failure) {}

        @Override
        public void failFatal(Error error) {}

        @Override
        public void closeQuietly(Throwable failure) {}
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ResponseInputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
        ResponseInputStream stdout = new ResponseInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        TransportHarness harness = openStartedTransport(process, options, state);
        DefaultSession session = harness.session();
        LineOutputTransport transport = harness.transport();
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
            stdout.publish("oversized\n".getBytes(StandardCharsets.UTF_8));
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
        LineOutputTransport transport = transport(options, state);
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
        }
    }

    @Test
    void publishingSelectedTerminalDoesNotInspectItsFailureGraph() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        IllegalStateException primary = new IllegalStateException("primary");
        LineSessionState.TerminalSnapshot selected =
                state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        LineOutputTransport transport = transport(options, state);
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            transport.publishTerminal(selected);
            transport.closeReaders();
        }

        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void selectedFailureEventDoesNotWaitForOrMutateItsCause() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        IllegalStateException primary = new IllegalStateException("primary");
        LineSessionState.TerminalSnapshot selected =
                state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        LineSessionState.Request request = state.beginRequest();
        LineOutputTransport transport = transport(options, state);
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            transport.publishTerminal(selected);
            LineOutputTransport.Event event =
                    transport.take(System.nanoTime() + Duration.ofSeconds(1).toNanos(), request);
            assertSame(
                    primary,
                    assertInstanceOf(LineOutputTransport.FailureEvent.class, event)
                            .failure());
        }

        assertEquals(0, primary.getSuppressed().length);
    }

    private static LineOutputTransport transport(LineSessionSettings options, LineSessionState state) {
        return new LineOutputTransport(
                options,
                state,
                ZeroReadBackoff.exponential(),
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

    private static TransportHarness openStartedTransport(
            Process process, LineSessionSettings options, LineSessionState state) {
        return SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                io.github.ulviar.procwright.command.ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                io.github.ulviar.procwright.internal.DiagnosticEmitter.of(
                        io.github.ulviar.procwright.internal.DiagnosticsSettings.disabled(),
                        "line-output-transport-test",
                        io.github.ulviar.procwright.diagnostics.CommandEcho.empty()),
                SessionOutputMode.LINE,
                session -> {
                    LineOutputTransport transport = transport(options, state);
                    OutputPumpCoordinator pumps = new OutputPumpCoordinator(session, SessionOutputMode.LINE);
                    transport.start(PumpStarter.threading(), pumps);
                    return new TransportHarness(session, transport);
                },
                io.github.ulviar.procwright.internal.BoundedCloseDispatcher.shared(),
                DefaultSession.WatcherStarter.threading());
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

    private record TransportHarness(DefaultSession session, LineOutputTransport transport) {}
}

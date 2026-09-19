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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LineOutputTransportTest {

    @Test
    void increasingOnlyResponseCharsAcceptsALineBeyondTheFormerTransportLimit() throws Exception {
        int characters = 1024 * 1024 + 1;
        String line = "x".repeat(characters);
        LineSessionSettings options = LineSessionSettings.defaults().withMaxResponseChars(characters);

        assertFullBurst(options, new CompletedBurstInputStream(line + "\r\n"), List.of(line));
    }

    @Test
    void increasingOnlyResponseLinesAcceptsABurstBeyondTheFormerBacklogLimit() throws Exception {
        int lines = 1025;
        LineSessionSettings options = LineSessionSettings.defaults().withMaxResponseLines(lines);

        assertFullBurst(options, new CompletedBurstInputStream("\n".repeat(lines)), Collections.nCopies(lines, ""));
    }

    @Test
    void splitCrLfDoesNotChargeTheTerminatorToTheResponseCharacterLimit() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults().withMaxResponseChars(1);

        assertFullBurst(options, new CompletedBurstInputStream("x\r\n", 1), List.of("x"));
    }

    @Test
    void pendingResponseBeyondEitherBudgetFailsBeforeAnyRead() throws Exception {
        LineSessionSettings options =
                LineSessionSettings.defaults().withMaxResponseChars(3).withMaxResponseLines(2);

        assertBurstFailure(options, "abc\nx\n", LineSessionException.Reason.RESPONSE_TOO_LARGE);
        assertBurstFailure(options, "a\n\n\n", LineSessionException.Reason.RESPONSE_TOO_LARGE);
    }

    @Test
    void unterminatedLineAndTrailingCarriageReturnObeyTheResponseLimit() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults().withMaxResponseChars(3);

        assertBurstFailure(options, "xxxx", LineSessionException.Reason.RESPONSE_TOO_LARGE);
        assertBurstFailure(options, "xxx\r", LineSessionException.Reason.RESPONSE_TOO_LARGE);
    }

    @Test
    void responseLimitOverflowWakesAWaitingRequest() throws Exception {
        LineSessionSettings options = LineSessionSettings.defaults().withMaxResponseChars(1);
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
            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
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

    private static void assertFullBurst(
            LineSessionSettings options, CompletedBurstInputStream stdout, List<String> expectedLines)
            throws Exception {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        LineSessionState.Request request = state.beginRequest();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        TransportHarness harness = openStartedTransport(process, options, state);
        try {
            assertTrue(
                    harness.stdoutFinished().await(5, TimeUnit.SECONDS),
                    "output pump did not finish the complete burst");
            assertNull(state.terminal());
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            for (String expectedLine : expectedLines) {
                assertEquals(
                        expectedLine,
                        assertInstanceOf(
                                        LineOutputTransport.LineEvent.class,
                                        harness.transport().take(deadline, request))
                                .value());
            }
            assertSame(
                    LineOutputTransport.EofEvent.INSTANCE, harness.transport().take(deadline, request));
        } finally {
            harness.transport().closeReaders();
            process.complete(0);
            harness.session().close();
        }
    }

    private static void assertBurstFailure(
            LineSessionSettings options, String output, LineSessionException.Reason reason) throws Exception {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        CompletedBurstInputStream stdout = new CompletedBurstInputStream(output);
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        TransportHarness harness = openStartedTransport(process, options, state);
        try {
            assertTrue(
                    harness.stdoutFinished().await(1, TimeUnit.SECONDS),
                    "output pump did not stop at the response limit");
            assertEquals(
                    reason,
                    assertInstanceOf(LineSessionState.FailureSnapshot.class, state.terminal())
                            .reason());
        } finally {
            harness.transport().closeReaders();
            process.complete(0);
            harness.session().close();
        }
    }

    private static IncrementalTextDecoder decoder(LineSessionSettings options) {
        return new IncrementalTextDecoder(
                options.charsetPolicy(), IncrementalTextDecoder.pendingByteLimitFor(options.maxResponseChars()));
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
                    CountDownLatch stdoutFinished = new CountDownLatch(1);
                    PumpStarter starter =
                            (namePrefix, task) -> PumpStarter.threading().start(namePrefix, () -> {
                                try {
                                    task.run();
                                } finally {
                                    if (namePrefix.equals("procwright-line-stdout-")) {
                                        stdoutFinished.countDown();
                                    }
                                }
                            });
                    transport.start(starter, pumps);
                    return new TransportHarness(session, transport, stdoutFinished);
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

    private static final class CompletedBurstInputStream extends ByteArrayInputStream {

        private final int chunkSize;

        private CompletedBurstInputStream(String output) {
            this(output, Integer.MAX_VALUE);
        }

        private CompletedBurstInputStream(String output, int chunkSize) {
            super(output.getBytes(StandardCharsets.UTF_8));
            this.chunkSize = chunkSize;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunkSize));
        }
    }

    private record TransportHarness(
            DefaultSession session, LineOutputTransport transport, CountDownLatch stdoutFinished) {}
}

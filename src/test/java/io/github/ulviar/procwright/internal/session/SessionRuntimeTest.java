/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class SessionRuntimeTest {

    @Test
    void processStartedDiagnosticFailureDoesNotAbortCustomPtySession() throws Exception {
        AssertionError diagnosticFailure = new AssertionError("PROCESS_STARTED construction failed");
        TrackingProcess process = new TrackingProcess();
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DiagnosticsSettings settings = DiagnosticsSettings.disabled().withListener(events::add);
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.failOnceOn(
                settings, "session-open-test", DiagnosticEventType.PROCESS_STARTED, diagnosticFailure);

        try (DefaultSession session = SessionRuntime.open(sessionPlan(process), diagnostics)) {
            process.exitNaturally();
            assertEquals(
                    137, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
        assertTrue(process.stdin.awaitClose());
        assertEquals(1, process.pidCalls());
        assertEquals(1, process.stdin.closeCalls());
        assertTrue(events.stream().noneMatch(event -> event.type() == DiagnosticEventType.PROCESS_FAILED));
    }

    @Test
    void shutdownDiagnosticFailureDoesNotAlterExplicitClose() throws Exception {
        AssertionError diagnosticFailure = new AssertionError("SHUTDOWN_REQUESTED failed");
        TrackingProcess process = new TrackingProcess();
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.failOnceOn(
                DiagnosticsSettings.disabled().withListener(event -> {}),
                "session-close-test",
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                diagnosticFailure);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics);

        session.close();

        assertEquals(137, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertEquals(1, process.stdin.closeCalls());
    }

    @Test
    void concreteHelperFactoryInstallsOutputPumpsBeforeTheExitWatcherCanRun() throws Exception {
        TrackingProcess process = new TrackingProcess();
        process.exitNaturally();

        LineSession session = SessionRuntime.openLineSession(
                sessionPlan(process),
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "helper-start-test", CommandEcho.empty()),
                LineSessionSettings.defaults());
        try {
            session.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(eventually(() -> process.stdout.closeCalls() == 1 && process.stderr.closeCalls() == 1));
        } finally {
            session.close();
        }
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return condition.getAsBoolean();
    }

    static SessionExecutionPlan sessionPlan(Process process) {
        PtyProvider provider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "controlled test PTY";
            }

            @Override
            public Process start(PtyRequest request) {
                return process;
            }
        };
        return new SessionExecutionPlan(
                new LaunchPlan(
                        List.of("controlled-pty"),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        Map.of(),
                        OutputMode.SEPARATE,
                        TerminalPolicy.REQUIRED),
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                provider,
                TerminalSize.defaults());
    }

    static final class TrackingProcess extends Process {

        final CloseTrackingOutputStream stdin = new CloseTrackingOutputStream();
        final CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        final CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger pidCalls = new AtomicInteger();
        private final CountDownLatch destroyed = new CountDownLatch(1);

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
        public int waitFor() {
            alive.set(false);
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            alive.set(false);
            destroyed.countDown();
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
        public long pid() {
            pidCalls.incrementAndGet();
            return 4242;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        int pidCalls() {
            return pidCalls.get();
        }

        boolean awaitDestroyed() throws InterruptedException {
            return destroyed.await(1, TimeUnit.SECONDS);
        }

        void exitNaturally() {
            alive.set(false);
        }
    }

    static final class CloseTrackingOutputStream extends OutputStream {

        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        int closeCalls() {
            return closes.get();
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    static final class CloseTrackingInputStream extends InputStream {

        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }

        int closeCalls() {
            return closes.get();
        }
    }
}

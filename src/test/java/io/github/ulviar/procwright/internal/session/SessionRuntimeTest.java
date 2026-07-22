/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchMode;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
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
    void processStartedDiagnosticFailureCleansUpCustomPtyProcessExactlyOnce() throws Exception {
        AssertionError diagnosticFailure = new AssertionError("PROCESS_STARTED construction failed");
        TrackingProcess process = new TrackingProcess();
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch processFailed = new CountDownLatch(1);
        DiagnosticsSettings settings = DiagnosticsSettings.disabled().withListener(event -> {
            events.add(event);
            if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                processFailed.countDown();
            }
        });
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.failOnceOn(
                settings, "session-open-test", DiagnosticEventType.PROCESS_STARTED, diagnosticFailure);

        AssertionError thrown =
                assertThrows(AssertionError.class, () -> SessionRuntime.open(sessionPlan(process), diagnostics));

        assertSame(diagnosticFailure, thrown);
        assertTrue(process.awaitDestroyed());
        assertTrue(process.stdin.awaitClose());
        assertTrue(processFailed.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
        assertEquals(1, process.pidCalls());
        assertEquals(1, process.stdin.closeCalls());
        assertEquals(1, process.stdout.closeCalls());
        assertEquals(1, process.stderr.closeCalls());
        assertEquals(
                List.of(DiagnosticEventType.PROCESS_FAILED),
                events.stream().map(DiagnosticEvent::type).toList());
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
                        LaunchMode.DIRECT,
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

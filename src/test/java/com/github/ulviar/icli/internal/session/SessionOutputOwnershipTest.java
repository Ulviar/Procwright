package com.github.ulviar.icli.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.ulviar.icli.command.CommandInput;
import com.github.ulviar.icli.command.EnvironmentPolicy;
import com.github.ulviar.icli.command.OutputMode;
import com.github.ulviar.icli.command.ShutdownPolicy;
import com.github.ulviar.icli.diagnostics.CommandEcho;
import com.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import com.github.ulviar.icli.internal.DiagnosticEmitter;
import com.github.ulviar.icli.internal.LaunchMode;
import com.github.ulviar.icli.internal.LaunchPlan;
import com.github.ulviar.icli.internal.SessionExecutionPlan;
import com.github.ulviar.icli.internal.StreamExecutionPlan;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.Session;
import com.github.ulviar.icli.session.SessionExit;
import com.github.ulviar.icli.session.StreamExit;
import com.github.ulviar.icli.session.StreamStdinPolicy;
import com.github.ulviar.icli.terminal.PtyProvider;
import com.github.ulviar.icli.terminal.TerminalPolicy;
import com.github.ulviar.icli.terminal.TerminalSignal;
import com.github.ulviar.icli.terminal.TerminalSize;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class SessionOutputOwnershipTest {

    @Test
    void helperClaimFailsAfterPublicOutputReadSelectsRawMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[] {'x'}))) {
            assertEquals('x', session.stdout().read());

            assertThrows(IllegalStateException.class, session::expect);
        }
    }

    @Test
    void helperClaimFailsAfterPublicOutputCloseSelectsRawMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[0]))) {
            session.stdout().close();

            assertThrows(IllegalStateException.class, session::expect);
        }
    }

    @Test
    void helperClaimFailsWhilePublicOutputReadIsInFlight() throws Exception {
        BlockingInputStream stdout = new BlockingInputStream();
        try (Session session = sessionWith(stdout)) {
            CompletableFuture<Integer> read = new CompletableFuture<>();
            Thread.ofVirtual().name("icli-test-blocking-public-output-read").start(() -> {
                try {
                    read.complete(session.stdout().read());
                } catch (Throwable throwable) {
                    read.completeExceptionally(throwable);
                }
            });
            stdout.awaitReadStarted();

            assertThrows(IllegalStateException.class, session::expect);

            stdout.release();
            assertEquals(-1, read.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void expectFactoryRejectsCustomSessionImplementations() {
        Session session = new CustomSession();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, session::expect);

        assertEquals("Session must be an iCLI-created handle", exception.getMessage());
    }

    @Test
    void rawOutputStreamsCannotBeReadAfterLineSessionClaimsOutputOwnership() throws Exception {
        DefaultSession rawSession = defaultSessionWith(new ByteArrayInputStream(new byte[0]));

        try (DefaultLineSession session = new DefaultLineSession(rawSession, LineSessionOptions.defaults())) {
            assertThrows(IllegalStateException.class, rawSession.stdout()::read);
            assertThrows(IllegalStateException.class, rawSession.stderr()::read);
        }
    }

    @Test
    void rawOutputStreamsCannotBeReadAfterStreamSessionClaimsOutputOwnership() throws Exception {
        DefaultSession rawSession = defaultSessionWith(new ByteArrayInputStream(new byte[0]));

        try (DefaultStreamSession session =
                new DefaultStreamSession(rawSession, streamPlan(Duration.ZERO), diagnostics())) {
            assertThrows(IllegalStateException.class, rawSession.stdout()::read);
            assertThrows(IllegalStateException.class, rawSession.stderr()::read);
        }
    }

    @Test
    void streamTimeoutWatcherStopsAfterEarlyProcessExit() throws Exception {
        StubProcess process = new StubProcess(new ByteArrayInputStream("done\n".getBytes(StandardCharsets.UTF_8)));
        DefaultSession rawSession = defaultSessionWith(process);

        try (DefaultStreamSession session =
                new DefaultStreamSession(rawSession, streamPlan(Duration.ofSeconds(Long.MAX_VALUE)), diagnostics())) {
            process.completeExit(0);

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            session.timeoutWatcherStopped().get(2, TimeUnit.SECONDS);
        }
    }

    private static Session sessionWith(InputStream stdout) {
        return defaultSessionWith(stdout);
    }

    private static DefaultSession defaultSessionWith(InputStream stdout) {
        return defaultSessionWith(new StubProcess(stdout));
    }

    private static DefaultSession defaultSessionWith(StubProcess process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    private static StreamExecutionPlan streamPlan(Duration timeout) {
        LaunchPlan launchPlan = new LaunchPlan(
                LaunchMode.DIRECT,
                List.of("stub"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.DISABLED);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                PtyProvider.unavailable(),
                TerminalSize.defaults());
        return new StreamExecutionPlan(
                sessionPlan, timeout, StreamStdinPolicy.KEEP_OPEN, 1024, chunk -> {}, DiagnosticsOptions.defaults());
    }

    private static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsOptions.defaults(), "session-test", CommandEcho.empty());
    }

    private static final class CustomSession implements Session {

        @Override
        public InputStream stdout() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream stderr() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream stdin() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void send(String text) {}

        @Override
        public void sendLine(String line) {}

        @Override
        public void send(CommandInput input) {}

        @Override
        public void sendSignal(TerminalSignal signal) {}

        @Override
        public void closeStdin() {}

        @Override
        public CompletableFuture<SessionExit> onExit() {
            return CompletableFuture.completedFuture(new SessionExit(OptionalInt.of(0), false));
        }

        @Override
        public void close() {}
    }

    private static final class BlockingInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while blocking test input stream", exception);
            }
            if (closed.get()) {
                throw new IOException("Stream closed");
            }
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
            release.countDown();
        }

        private void awaitReadStarted() throws InterruptedException {
            if (!readStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Public output read did not start");
            }
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class StubProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final OutputStream stdin = OutputStream.nullOutputStream();
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private StubProcess(InputStream stdout) {
            this.stdout = stdout;
        }

        private void completeExit(int exitCode) {
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
                throw new IllegalStateException("Stub process failed", exception);
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
                throw new IllegalStateException("Stub process failed", exception);
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("Process is still alive");
            }
            return value;
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
        public long pid() {
            return 1L;
        }
    }
}

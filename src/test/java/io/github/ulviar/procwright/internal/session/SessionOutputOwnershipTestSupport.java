/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

abstract class SessionOutputOwnershipTestSupport {

    protected static void awaitProcessTerminal(DefaultSession session) throws InterruptedException {
        CountDownLatch terminal = new CountDownLatch(1);
        session.observeTermination((result, failure) -> terminal.countDown());
        assertTrue(terminal.await(2, TimeUnit.SECONDS), "process terminal signal did not complete");
    }

    protected static DefaultSession defaultSessionWith(InputStream stdout) {
        return defaultSessionWith(new StubProcess(stdout));
    }

    protected static DefaultSession defaultSessionWith(StubProcess process) {
        return SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    protected static <T> OpenedHelper<T> openHelper(
            StubProcess process, SessionOutputMode outputMode, Function<DefaultSession, T> helperFactory) {
        AtomicReference<DefaultSession> rawSession = new AtomicReference<>();
        T helper = SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                outputMode,
                session -> {
                    rawSession.set(session);
                    return helperFactory.apply(session);
                });
        return new OpenedHelper<>(rawSession.get(), helper);
    }

    protected static StreamExecutionPlan streamPlan(Duration timeout) {
        LaunchPlan launchPlan = new LaunchPlan(
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
        return new StreamExecutionPlan(sessionPlan, timeout, 1024, chunk -> {}, DiagnosticsSettings.disabled());
    }

    protected static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty());
    }

    protected record OpenedHelper<T>(DefaultSession rawSession, T helper) {}

    protected static final class StubProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final OutputStream stdin = OutputStream.nullOutputStream();
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final Runnable onDestroy;

        protected StubProcess(InputStream stdout) {
            this(stdout, new ByteArrayInputStream(new byte[0]));
        }

        protected StubProcess(InputStream stdout, InputStream stderr) {
            this(stdout, stderr, () -> {});
        }

        protected StubProcess(InputStream stdout, InputStream stderr, Runnable onDestroy) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.onDestroy = onDestroy;
        }

        protected void completeExit(int exitCode) {
            exit.complete(exitCode);
            alive.set(false);
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
            onDestroy.run();
            exit.complete(143);
            alive.set(false);
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

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}

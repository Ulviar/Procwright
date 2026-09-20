/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

abstract class DefaultStreamSessionTestSupport {

    static DefaultStreamSession openStream(Process process, StreamExecutionPlan plan) {
        return openStream(process, plan, diagnostics());
    }

    static DefaultStreamSession openStream(
            Process process, StreamExecutionPlan plan, DiagnosticEmitter eventDiagnostics) {
        return openStream(process, plan, eventDiagnostics, DefaultStreamSession.Dependencies.defaults());
    }

    static DefaultStreamSession openStream(
            Process process,
            StreamExecutionPlan plan,
            DiagnosticEmitter eventDiagnostics,
            DefaultStreamSession.Dependencies dependencies) {
        return openStream(process, plan, eventDiagnostics, dependencies, ignored -> {});
    }

    static DefaultStreamSession openStream(
            Process process,
            StreamExecutionPlan plan,
            DiagnosticEmitter eventDiagnostics,
            DefaultStreamSession.Dependencies dependencies,
            Consumer<? super DefaultSession> sessionObserver) {
        return SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                eventDiagnostics,
                SessionOutputMode.STREAM,
                session -> {
                    sessionObserver.accept(session);
                    return new DefaultStreamSession(session, plan, eventDiagnostics, dependencies);
                });
    }

    static DefaultStreamSession openStream(
            Process process,
            StreamExecutionPlan plan,
            DiagnosticEmitter eventDiagnostics,
            BoundedCloseDispatcher closeDispatcher) {
        return SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                eventDiagnostics,
                SessionOutputMode.STREAM,
                session -> new DefaultStreamSession(session, plan, eventDiagnostics),
                closeDispatcher,
                DefaultSession.WatcherStarter.threading());
    }

    static StreamExecutionPlan plan(StreamListener listener) {
        return plan(listener, Duration.ZERO);
    }

    static StreamExecutionPlan plan(StreamListener listener, Duration timeout) {
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
        return new StreamExecutionPlan(sessionPlan, timeout, 1024, listener, DiagnosticsSettings.disabled());
    }

    static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "stream-delivery-test", CommandEcho.empty());
    }

    static final class ControllableProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();

        ControllableProcess(InputStream stdout, InputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
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
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException ignored) {
                return false;
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {
            complete(143);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        void complete(int exitCode) {
            exit.complete(exitCode);
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.internal.ReadinessSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/** Namespace for immutable raw interactive-session drafts. */
public final class InteractiveScenario {

    private InteractiveScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return new ImmutableDraft(
                runtime, SessionSettings.defaults(runtime.launchSettings()), ReadinessSettings.defaults());
    }

    /**
     * Persistent write-only configuration for opening raw interactive sessions.
     *
     * <p>The Draft retains its PTY provider, readiness probe, diagnostic listener, and transcript sink. Concurrent
     * {@link #open()} calls can invoke each supplied instance concurrently from independent sessions. Retained instances
     * must be thread-safe; otherwise, use separate Draft branches with separate callback or provider instances.
     */
    public interface Draft {
        /**
         * Appends one process argument.
         *
         * @param argument argument to append
         * @return updated draft
         */
        Draft withArg(String argument);

        /**
         * Appends process arguments after copying the caller array.
         *
         * @param arguments arguments to append
         * @return updated draft
         */
        Draft withArgs(String... arguments);

        /**
         * Appends process arguments after copying the caller collection.
         *
         * @param arguments arguments to append
         * @return updated draft
         */
        Draft withArgs(Collection<String> arguments);

        /**
         * Sets the process working directory.
         *
         * @param workingDirectory working directory
         * @return updated draft
         */
        Draft withWorkingDirectory(Path workingDirectory);

        /**
         * Adds or replaces one child environment variable.
         *
         * @param name variable name
         * @param value variable value
         * @return updated draft
         */
        Draft withEnvironment(String name, String value);

        /**
         * Selects parent environment inheritance.
         *
         * @return updated draft
         */
        Draft withInheritedEnvironment();

        /**
         * Selects an initially empty child environment.
         *
         * @return updated draft
         */
        Draft withCleanEnvironment();

        /**
         * Sets process shutdown escalation.
         *
         * @param shutdownPolicy shutdown policy
         * @return updated draft
         */
        Draft withShutdown(ShutdownPolicy shutdownPolicy);

        /**
         * Sets the caller-visible idle timeout; zero disables it.
         *
         * @param idleTimeout non-negative idle timeout
         * @return updated draft
         */
        Draft withIdleTimeout(Duration idleTimeout);

        /**
         * Sets the charset used by text send helpers.
         *
         * @param charset text charset
         * @return updated draft
         */
        Draft withCharset(Charset charset);

        /**
         * Selects whether a terminal is disabled, preferred, or required.
         *
         * @param terminalPolicy terminal policy
         * @return updated draft
         */
        Draft withTerminal(TerminalPolicy terminalPolicy);

        /**
         * Sets the PTY provider used when a terminal is requested.
         *
         * @param ptyProvider PTY provider
         * @return updated draft
         */
        Draft withPtyProvider(PtyProvider ptyProvider);

        /**
         * Sets the requested terminal dimensions.
         *
         * @param terminalSize terminal size
         * @return updated draft
         */
        Draft withTerminalSize(TerminalSize terminalSize);

        /**
         * Sets a probe that must complete before {@link #open()} returns.
         *
         * <p>The Draft retains the probe. Each open invokes it once for its new session and waits for completion;
         * concurrent opens can invoke the same probe instance concurrently.
         *
         * @param readinessProbe readiness probe
         * @return updated draft
         */
        Draft withReadiness(Consumer<Session> readinessProbe);

        /**
         * Sets the readiness probe timeout.
         *
         * @param readinessTimeout positive timeout
         * @return updated draft
         */
        Draft withReadinessTimeout(Duration readinessTimeout);

        /**
         * Observes lifecycle diagnostics.
         *
         * @param listener diagnostic listener
         * @return updated draft
         */
        Draft withDiagnosticListener(DiagnosticListener listener);

        /**
         * Receives bounded diagnostic transcript snapshots.
         *
         * @param transcriptSink transcript sink
         * @return updated draft
         */
        Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink);

        /**
         * Starts a new process, runs readiness, and returns its live session.
         *
         * @return newly opened session
         */
        Session open();
    }

    private record ImmutableDraft(
            ScenarioRuntime runtime, SessionSettings settings, ReadinessSettings<Session> readiness) implements Draft {

        private ImmutableDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(readiness, "readiness");
        }

        @Override
        public Draft withArg(String argument) {
            return withSettings(settings.withLaunch(settings.launch().withArg(argument)));
        }

        @Override
        public Draft withArgs(String... arguments) {
            return withSettings(settings.withLaunch(settings.launch().withArgs(arguments)));
        }

        @Override
        public Draft withArgs(Collection<String> arguments) {
            return withSettings(settings.withLaunch(settings.launch().withArgs(arguments)));
        }

        @Override
        public Draft withWorkingDirectory(Path workingDirectory) {
            return withSettings(settings.withLaunch(settings.launch().withWorkingDirectory(workingDirectory)));
        }

        @Override
        public Draft withEnvironment(String name, String value) {
            return withSettings(settings.withLaunch(settings.launch().withEnvironment(name, value)));
        }

        @Override
        public Draft withInheritedEnvironment() {
            return withSettings(
                    settings.withLaunch(settings.launch().withEnvironmentPolicy(EnvironmentPolicy.INHERIT)));
        }

        @Override
        public Draft withCleanEnvironment() {
            return withSettings(settings.withLaunch(settings.launch().withEnvironmentPolicy(EnvironmentPolicy.CLEAN)));
        }

        @Override
        public Draft withShutdown(ShutdownPolicy shutdownPolicy) {
            return withSettings(settings.withShutdownPolicy(shutdownPolicy));
        }

        @Override
        public Draft withIdleTimeout(Duration idleTimeout) {
            return withSettings(settings.withIdleTimeout(idleTimeout));
        }

        @Override
        public Draft withCharset(Charset charset) {
            return withSettings(settings.withCharset(charset));
        }

        @Override
        public Draft withTerminal(TerminalPolicy terminalPolicy) {
            return withSettings(settings.withTerminal(settings.terminal().withPolicy(terminalPolicy)));
        }

        @Override
        public Draft withPtyProvider(PtyProvider ptyProvider) {
            return withSettings(settings.withTerminal(settings.terminal().withProvider(ptyProvider)));
        }

        @Override
        public Draft withTerminalSize(TerminalSize terminalSize) {
            return withSettings(settings.withTerminal(settings.terminal().withSize(terminalSize)));
        }

        @Override
        public Draft withReadiness(Consumer<Session> readinessProbe) {
            return new ImmutableDraft(runtime, settings, readiness.withProbe(readinessProbe));
        }

        @Override
        public Draft withReadinessTimeout(Duration readinessTimeout) {
            return new ImmutableDraft(runtime, settings, readiness.withTimeout(readinessTimeout));
        }

        @Override
        public Draft withDiagnosticListener(DiagnosticListener listener) {
            return withSettings(settings.withDiagnostics(settings.diagnostics().withListener(listener)));
        }

        @Override
        public Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink) {
            return withSettings(settings.withDiagnostics(settings.diagnostics().withTranscriptSink(transcriptSink)));
        }

        @Override
        public Session open() {
            return runtime.interactive(settings, readiness);
        }

        private Draft withSettings(SessionSettings updated) {
            return new ImmutableDraft(runtime, updated, readiness);
        }
    }
}

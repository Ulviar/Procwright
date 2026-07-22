/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.internal.StreamSettings;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.session.StreamSession;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

/** Namespace for immutable listen-only stream drafts. */
public final class StreamScenario {

    private StreamScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return new ImmutableDraft(runtime, StreamSettings.defaults(runtime.launchSettings()));
    }

    /**
     * Persistent write-only configuration for opening listen-only streams.
     *
     * <p>The scenario closes process stdin when it starts. Use {@link CommandService#interactive()} when the caller
     * needs to write stdin.
     *
     * <p>The Draft retains its output listener, diagnostic listener, and transcript sink. One opened stream session
     * invokes its output listener synchronously and serializes stdout and stderr chunks. Concurrent {@link #open()} calls
     * can still invoke the same retained instances concurrently from independent sessions. Retained instances must be
     * thread-safe; otherwise, use separate Draft branches with separate callback instances.
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
         * Sets the absolute stream timeout; zero disables it.
         *
         * @param timeout non-negative timeout
         * @return updated draft
         */
        Draft withTimeout(Duration timeout);

        /**
         * Sets the charset used to decode output chunks.
         *
         * @param charset output charset
         * @return updated draft
         */
        Draft withCharset(Charset charset);

        /**
         * Sets the retained diagnostic character limit.
         *
         * @param diagnosticLimit positive character limit
         * @return updated draft
         */
        Draft withDiagnosticLimit(int diagnosticLimit);

        /**
         * Sets the callback that receives stdout and stderr chunks.
         *
         * <p>The Draft retains the listener. Calls are serialized within one stream session, but concurrent opens can
         * invoke the same listener instance concurrently.
         *
         * @param listener output listener
         * @return updated draft
         */
        Draft onOutput(StreamListener listener);

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
         * Starts a new process and returns its live output stream handle.
         *
         * @return newly opened stream session
         */
        StreamSession open();
    }

    private record ImmutableDraft(ScenarioRuntime runtime, StreamSettings settings) implements Draft {

        private ImmutableDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(settings, "settings");
        }

        @Override
        public Draft withArg(String argument) {
            return copy(settings.withLaunch(settings.launch().withArg(argument)));
        }

        @Override
        public Draft withArgs(String... arguments) {
            return copy(settings.withLaunch(settings.launch().withArgs(arguments)));
        }

        @Override
        public Draft withArgs(Collection<String> arguments) {
            return copy(settings.withLaunch(settings.launch().withArgs(arguments)));
        }

        @Override
        public Draft withWorkingDirectory(Path workingDirectory) {
            return copy(settings.withLaunch(settings.launch().withWorkingDirectory(workingDirectory)));
        }

        @Override
        public Draft withEnvironment(String name, String value) {
            return copy(settings.withLaunch(settings.launch().withEnvironment(name, value)));
        }

        @Override
        public Draft withInheritedEnvironment() {
            return copy(settings.withLaunch(settings.launch().withEnvironmentPolicy(EnvironmentPolicy.INHERIT)));
        }

        @Override
        public Draft withCleanEnvironment() {
            return copy(settings.withLaunch(settings.launch().withEnvironmentPolicy(EnvironmentPolicy.CLEAN)));
        }

        @Override
        public Draft withShutdown(ShutdownPolicy shutdownPolicy) {
            return copy(settings.withShutdownPolicy(shutdownPolicy));
        }

        @Override
        public Draft withTimeout(Duration timeout) {
            return copy(settings.withTimeout(timeout));
        }

        @Override
        public Draft withCharset(Charset charset) {
            return copy(settings.withCharset(charset));
        }

        @Override
        public Draft withDiagnosticLimit(int diagnosticLimit) {
            return copy(settings.withDiagnosticLimit(diagnosticLimit));
        }

        @Override
        public Draft onOutput(StreamListener listener) {
            return copy(settings.withListener(listener));
        }

        @Override
        public Draft withDiagnosticListener(DiagnosticListener listener) {
            return copy(settings.withDiagnostics(settings.diagnostics().withListener(listener)));
        }

        @Override
        public Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink) {
            return copy(settings.withDiagnostics(settings.diagnostics().withTranscriptSink(transcriptSink)));
        }

        @Override
        public StreamSession open() {
            return runtime.listen(settings);
        }

        private Draft copy(StreamSettings updated) {
            return new ImmutableDraft(runtime, updated);
        }
    }
}

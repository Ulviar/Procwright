/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.internal.RunSettings;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

/** Namespace for immutable one-shot command drafts. */
public final class RunScenario {

    private RunScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return new ImmutableDraft(runtime, RunSettings.defaults(runtime.launchSettings()));
    }

    /**
     * Persistent write-only configuration for finite process executions.
     *
     * <p>The Draft retains its diagnostic listener and transcript sink. Concurrent {@link #execute()} calls on the same
     * Draft can invoke each supplied instance concurrently from independent executions. Shared diagnostics recipients
     * must be thread-safe; otherwise, use separate Draft branches with separate recipient instances.
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
         * Sets how stdout and stderr are retained.
         *
         * @param capturePolicy capture policy
         * @return updated draft
         */
        Draft withCapture(CapturePolicy capturePolicy);

        /**
         * Sets process shutdown escalation.
         *
         * @param shutdownPolicy shutdown policy
         * @return updated draft
         */
        Draft withShutdown(ShutdownPolicy shutdownPolicy);

        /**
         * Sets the execution timeout; zero disables it.
         *
         * @param timeout non-negative timeout
         * @return updated draft
         */
        Draft withTimeout(Duration timeout);

        /**
         * Selects forgiving output decoding with the given charset.
         *
         * @param charset output charset
         * @return updated draft
         */
        Draft withCharset(Charset charset);

        /**
         * Sets output charset and malformed-input behavior.
         *
         * @param charsetPolicy charset policy
         * @return updated draft
         */
        Draft withCharsetPolicy(CharsetPolicy charsetPolicy);

        /**
         * Selects separate or merged output handling.
         *
         * @param outputMode output mode
         * @return updated draft
         */
        Draft withOutput(OutputMode outputMode);

        /**
         * Supplies UTF-8 text on stdin and closes stdin afterwards.
         *
         * @param input input text
         * @return updated draft
         */
        Draft withInput(String input);

        /**
         * Supplies encoded text on stdin and closes stdin afterwards.
         *
         * @param input input text
         * @param charset input charset
         * @return updated draft
         */
        Draft withInput(String input, Charset charset);

        /**
         * Sets an in-memory or file-backed stdin source.
         *
         * @param input command input
         * @return updated draft
         */
        Draft withInput(CommandInput input);

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
         * Starts a new process and waits for its finite result.
         *
         * @return command result
         */
        CommandResult execute();
    }

    private record ImmutableDraft(ScenarioRuntime runtime, RunSettings settings) implements Draft {

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
        public Draft withCapture(CapturePolicy capturePolicy) {
            return copy(settings.withCapturePolicy(capturePolicy));
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
            return copy(settings.withCharsetPolicy(CharsetPolicy.replace(charset)));
        }

        @Override
        public Draft withCharsetPolicy(CharsetPolicy charsetPolicy) {
            return copy(settings.withCharsetPolicy(charsetPolicy));
        }

        @Override
        public Draft withOutput(OutputMode outputMode) {
            return copy(settings.withOutputMode(outputMode));
        }

        @Override
        public Draft withInput(String input) {
            return copy(settings.withInput(CommandInput.utf8(input)));
        }

        @Override
        public Draft withInput(String input, Charset charset) {
            return copy(settings.withInput(CommandInput.text(input, charset)));
        }

        @Override
        public Draft withInput(CommandInput input) {
            return copy(settings.withInput(input));
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
        public CommandResult execute() {
            return runtime.run(settings);
        }

        private Draft copy(RunSettings updated) {
            return new ImmutableDraft(runtime, updated);
        }
    }
}

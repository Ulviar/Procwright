/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
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

/**
 * Configuration API for finite command executions.
 *
 * <p>Obtain a {@link Draft} from {@link CommandService#run()}, configure it, then call {@link Draft#execute()}.
 */
public final class RunScenario {

    private RunScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return new ImmutableDraft(runtime, RunSettings.defaults(runtime.commandSpec()));
    }

    /**
     * Immutable configuration for finite process executions, obtained from {@link CommandService#run()}.
     *
     * <p>Each {@code with*} call returns a new draft without changing this one or starting a process. Launch settings
     * begin with the service's command specification. Each {@link #execute()} starts an independent process.
     *
     * <p>Defaults: 30-second execution timeout, the first 1 MiB retained from each output stream, separate stdout and
     * stderr, UTF-8 replacement decoding, and closed stdin unless input is supplied. See the individual settings for
     * shutdown and diagnostic defaults.
     *
     * <p>The Draft retains its diagnostic listener and transcript sink. Concurrent {@link #execute()} calls on the same
     * Draft can invoke each supplied instance concurrently from independent executions. Shared diagnostics recipients
     * must be thread-safe; otherwise, use separate Draft branches with separate recipient instances.
     */
    public interface Draft {
        /**
         * Appends one argument after the base command's arguments. The string is passed as one argv element;
         * spaces, quotes, wildcard characters, and shell operators are not interpreted.
         *
         * @param argument argument to append; may be empty, but must not contain NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if the argument contains NUL or the base command is a shell command
         */
        Draft withArg(String argument);

        /**
         * Appends arguments in array order, after copying the array. Each element is passed literally as one
         * argv element. An empty array adds nothing.
         *
         * @param arguments arguments to append; elements must be non-null and contain no NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if an argument contains NUL, or nonempty arguments are appended to a shell command
         */
        Draft withArgs(String... arguments);

        /**
         * Appends arguments in iteration order, after copying the collection. Each element is passed literally
         * as one argv element. An empty collection adds nothing.
         *
         * @param arguments arguments to append; elements must be non-null and contain no NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if an argument contains NUL, or nonempty arguments are appended to a shell command
         */
        Draft withArgs(Collection<String> arguments);

        /**
         * Sets the child process's working directory. This does not change the application's working directory;
         * the operating system validates the directory when the process starts.
         *
         * @param workingDirectory child working directory; otherwise inherited from the base command
         * @return updated immutable draft
         */
        Draft withWorkingDirectory(Path workingDirectory);

        /**
         * Adds or replaces a child environment entry. Explicit entries are applied after the selected inherited
         * or clean environment, independently of the order of configuration calls.
         *
         * @param name nonblank variable name containing neither NUL nor {@code =}
         * @param value variable value; may be empty, but must not contain NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if the name or value violates these constraints
         */
        Draft withEnvironment(String name, String value);

        /**
         * Inherits the parent environment and then applies all explicitly configured entries.
         * This is the default for a new command specification.
         *
         * @return updated immutable draft
         */
        Draft withInheritedEnvironment();

        /**
         * Starts from an empty child environment and then applies all explicitly configured entries.
         * This does not remove entries already configured on the command or draft.
         *
         * @return updated immutable draft
         */
        Draft withCleanEnvironment();

        /**
         * Selects in-memory retention, file redirection, or discarding of stdout and stderr.
         * The default retains the first 1 MiB of each stream and continues draining excess output.
         * File policies must agree with {@link #withOutput(OutputMode)}; this combination is checked at execution.
         *
         * @param capturePolicy output retention policy
         * @return updated immutable draft
         */
        Draft withCapture(CapturePolicy capturePolicy);

        /**
         * Sets the graceful and forceful shutdown waits. The default waits up to two seconds after requesting
         * graceful termination, then up to five seconds after requesting forceful termination.
         * Shutdown can extend the time taken to return from an operation whose own timeout has elapsed.
         *
         * @param shutdownPolicy shutdown escalation and wait budgets
         * @return updated immutable draft
         */
        Draft withShutdown(ShutdownPolicy shutdownPolicy);

        /**
         * Sets the absolute execution timeout; zero disables it. The default is 30 seconds.
         * The deadline covers process work, including I/O, rather than an interval between output chunks.
         * Shutdown has separate wait budgets and can delay completion beyond this timeout.
         *
         * @param timeout non-negative execution timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code timeout} is negative
         */
        Draft withTimeout(Duration timeout);

        /**
         * Selects replacement decoding for captured output using the supplied charset. The default is UTF-8.
         * This does not change stdin encoding; use {@link #withInput(String, Charset)} for text input.
         *
         * @param charset output decoding charset
         * @return updated immutable draft
         */
        Draft withCharset(Charset charset);

        /**
         * Selects how captured bytes are decoded. The default replaces malformed or unmappable UTF-8 input.
         * With strict reporting, decoding failure throws a
         * {@link io.github.ulviar.procwright.command.CommandExecutionException} whose diagnostic result retains the bytes.
         *
         * @param charsetPolicy output decoding policy; does not change stdin encoding
         * @return updated immutable draft
         */
        Draft withCharsetPolicy(CharsetPolicy charsetPolicy);

        /**
         * Keeps stdout and stderr separate (the default), or merges stderr into stdout before capture.
         * Merged output is returned in stdout; stderr is empty. A file capture policy must have the matching
         * single-file or two-file form; an incompatible pair is rejected by {@link #execute()} before launch.
         *
         * @param outputMode separate or merged output routing
         * @return updated immutable draft
         */
        Draft withOutput(OutputMode outputMode);

        /**
         * Supplies UTF-8 text on stdin, without adding a line separator, then closes stdin.
         * Each execution uses the same immutable input. Without input, stdin is closed after launch.
         *
         * @param input input text, including any line separators required by the command
         * @return updated immutable draft
         */
        Draft withInput(String input);

        /**
         * Encodes text with the supplied charset and supplies those bytes on stdin, then closes stdin.
         * No line separator is added. Encoding occurs during configuration; output charset settings do not affect it.
         *
         * @param input input text, including any line separators required by the command
         * @param charset input encoding charset
         * @return updated immutable draft
         */
        Draft withInput(String input, Charset charset);

        /**
         * Supplies an in-memory byte snapshot or redirects stdin from a file, then closes stdin at end of input.
         * File contents are read when the command runs, not snapshotted during configuration. Without input,
         * stdin is closed after launch. See {@link CommandInput} for snapshot and file lifetime rules.
         *
         * @param input command input source
         * @return updated immutable draft
         */
        Draft withInput(CommandInput input);

        /**
         * Observes structured lifecycle events through an asynchronous, best-effort listener.
         * Events contain command metadata, not stdout or stderr. Delivery need not finish before the operation returns.
         * See {@link DiagnosticListener} for concurrency and failure isolation.
         *
         * @param listener retained event listener; the default ignores events
         * @return updated immutable draft
         */
        Draft withDiagnosticListener(DiagnosticListener listener);

        /**
         * Records structured lifecycle events through an asynchronous, best-effort sink.
         * The sink receives {@link io.github.ulviar.procwright.diagnostics.DiagnosticEvent} values, not captured process
         * output. Delivery is independent of the diagnostic listener and need not finish before the operation returns.
         *
         * @param transcriptSink retained event sink; the default ignores events
         * @return updated immutable draft
         */
        Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink);

        /**
         * Starts an independent process and waits for its result, including output collection and timeout cleanup.
         * A non-zero exit code or an execution timeout normally returns a {@link CommandResult}; check
         * {@link CommandResult#succeeded()} and {@link CommandResult#timedOut()}. Use
         * {@link CommandResult#toException()} when the application wants to throw for an unsuccessful result.
         *
         * <p>Launch, I/O, supervision, or cleanup failures throw an execution exception. Output decoding failures
         * use {@link io.github.ulviar.procwright.command.CommandExecutionException.Reason#DECODE_ERROR} and retain a
         * result with captured bytes. Caller interruption starts cleanup, throws an execution exception, and
         * preserves the thread's interrupt status. Shutdown may take longer than the execution timeout.
         *
         * @return completed result, including unsuccessful exits and ordinary execution timeouts
         * @throws IllegalArgumentException if output routing and capture targets are incompatible or invalid
         * @throws io.github.ulviar.procwright.command.CommandExecutionException if launch, I/O, decoding, supervision,
         *     or cleanup fails
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
            return copy(settings.withLaunch(settings.launch().withInheritedEnvironment()));
        }

        @Override
        public Draft withCleanEnvironment() {
            return copy(settings.withLaunch(settings.launch().withCleanEnvironment()));
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

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

/**
 * Configuration API for processes whose decoded output is consumed as it arrives.
 *
 * <p>Obtain a {@link Draft} from {@link CommandService#listen()} and register an output listener before opening it.
 */
public final class StreamScenario {

    private StreamScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return new ImmutableDraft(runtime, StreamSettings.defaults(runtime.commandSpec()));
    }

    /**
     * Immutable configuration for live process output, obtained from {@link CommandService#listen()}.
     *
     * <p>Each {@code with*} call returns a new draft without changing this one or starting a process. Launch settings
     * begin with the service's command specification. Defaults are no absolute timeout, UTF-8 output, 65,536 UTF-16
     * code units of combined retained diagnostics, and a no-op output listener.
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
         * Sets the graceful and forceful shutdown waits. The default waits up to two seconds after requesting
         * graceful termination, then up to five seconds after requesting forceful termination.
         * Shutdown can extend the time taken to return from an operation whose own timeout has elapsed.
         *
         * @param shutdownPolicy shutdown escalation and wait budgets
         * @return updated immutable draft
         */
        Draft withShutdown(ShutdownPolicy shutdownPolicy);

        /**
         * Sets the absolute stream timeout; zero disables it. The default is zero (disabled).
         * The deadline covers process work, including I/O, rather than an interval between output chunks.
         * Shutdown has separate wait budgets and can delay completion beyond this timeout.
         *
         * @param timeout non-negative stream timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code timeout} is negative
         */
        Draft withTimeout(Duration timeout);

        /**
         * Decodes stdout and stderr with the supplied charset, replacing malformed or unmappable input.
         * The default is UTF-8. Chunks preserve text content, including line separators and terminal control sequences;
         * a chunk is not necessarily one line.
         *
         * @param charset output decoding charset
         * @return updated immutable draft
         */
        Draft withCharset(Charset charset);

        /**
         * Bounds the retained diagnostic tail shared by stdout and stderr, including stream labels.
         * The default is 65,536 UTF-16 code units. Older diagnostic text is discarded when the limit is exceeded;
         * this does not limit output delivered to the listener.
         *
         * @param diagnosticLimit positive number of UTF-16 code units
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code diagnosticLimit} is not positive
         */
        Draft withDiagnosticLimit(int diagnosticLimit);

        /**
         * Sets the listener for decoded stdout and stderr chunks. The default ignores output.
         * A session serializes synchronous listener calls across both streams; a slow listener applies backpressure.
         * Chunks can split lines and include carriage returns or terminal control sequences. See {@link StreamListener}
         * for completion and failure behavior.
         *
         * <p>The draft retains the listener. Concurrent opens can invoke the same listener from different sessions.
         *
         * @param listener retained output listener
         * @return updated immutable draft
         */
        Draft onOutput(StreamListener listener);

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
         * Starts a process, closes its stdin, and begins delivering output to the configured listener.
         * Callbacks may run before this method returns. The caller owns the returned handle and must close it,
         * preferably with try-with-resources. Natural completion and asynchronous failures are observed through
         * {@link StreamSession#onExit()}; see {@link StreamSession} for output delivery and close guarantees.
         *
         * @return newly opened stream session
         * @throws io.github.ulviar.procwright.command.CommandExecutionException if process launch or setup fails
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
            return copy(settings.withLaunch(settings.launch().withInheritedEnvironment()));
        }

        @Override
        public Draft withCleanEnvironment() {
            return copy(settings.withLaunch(settings.launch().withCleanEnvironment()));
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

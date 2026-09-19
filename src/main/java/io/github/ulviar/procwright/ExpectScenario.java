/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.ReadinessSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectTranscriptValues;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configuration API for matching prompts and sending replies to a process.
 *
 * <p>Obtain a {@link Draft} from {@link InteractiveScenario.Entry#expect()}; opening it returns an owned
 * {@link io.github.ulviar.procwright.session.Expect} handle.
 */
public final class ExpectScenario {

    private ExpectScenario() {}

    static Draft draft(ScenarioRuntime runtime, SessionSettings session) {
        return new ImmutableDraft(runtime, session, ExpectSettings.defaults(), ReadinessSettings.defaults());
    }

    /**
     * Immutable configuration for prompt automation, obtained from {@link InteractiveScenario.Entry#expect()}.
     *
     * <p>Defaults are a five-second match timeout, 65,536 UTF-16 code units each for the match buffer and transcript,
     * UTF-8 input and output, no idle timeout or readiness probe, and terminal transport disabled. ANSI stripping is
     * disabled; caller action values are redacted in the transcript, but process output is not automatically redacted.
     *
     * <p>Readiness probes, PTY providers, and diagnostic callbacks are retained by reference. Concurrent opens can
     * invoke the same instance concurrently; shared instances must be thread-safe.
     *
     * <p>Each {@code with*} method returns a new draft. The draft starts no process and may be reused concurrently;
     * {@link #open()} starts one process whose output belongs to the returned {@link Expect}.
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
         * Sets the inactivity timeout for the underlying process session. Zero disables it, which is the default.
         * Successful reads and writes through the session's streams refresh activity; this is not a total process
         * lifetime or request deadline. Expiry starts process shutdown.
         *
         * @param idleTimeout non-negative inactivity timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code idleTimeout} is negative
         */
        Draft withIdleTimeout(Duration idleTimeout);

        /**
         * Sets the input charset and, unless separately overridden, the output decoding charset.
         * The default is UTF-8. A previous {@link #withOutputCharset(Charset)} remains in effect regardless
         * of configuration order. Malformed or unmappable output is replaced.
         *
         * @param charset text input charset and default output charset
         * @return updated immutable draft
         */
        Draft withCharset(Charset charset);

        /**
         * Overrides stdout and stderr decoding without changing input encoding.
         * This explicit override remains in effect after later {@link #withCharset(Charset)} calls.
         * By default output follows the input charset. Malformed or unmappable output is replaced.
         *
         * @param outputCharset output decoding charset
         * @return updated immutable draft
         */
        Draft withOutputCharset(Charset outputCharset);

        /**
         * Selects pipe transport or a pseudo-terminal. The default is {@link TerminalPolicy#DISABLED}.
         * {@link TerminalPolicy#AUTO} permits pipe fallback when the provider is unavailable;
         * {@link TerminalPolicy#REQUIRED} fails the open instead. See {@link PtyProvider} for platform requirements.
         *
         * @param terminalPolicy terminal requirement
         * @return updated immutable draft
         */
        Draft withTerminal(TerminalPolicy terminalPolicy);

        /**
         * Sets the provider consulted for {@link TerminalPolicy#AUTO} or {@link TerminalPolicy#REQUIRED}.
         * The default is {@link PtyProvider#system()}. Setting a provider does not itself enable terminal transport.
         * A shared provider must support concurrent opens and follow the timing contract of {@link PtyProvider}.
         *
         * @param ptyProvider retained terminal provider
         * @return updated immutable draft
         */
        Draft withPtyProvider(PtyProvider ptyProvider);

        /**
         * Sets the dimensions requested when a pseudo-terminal is opened. The default is 80 columns by 24 rows.
         * This is an initial size request, not a resize operation on an existing session; pipe transport ignores it.
         *
         * @param terminalSize requested terminal dimensions
         * @return updated immutable draft
         */
        Draft withTerminalSize(TerminalSize terminalSize);

        /**
         * Sets the probe that must succeed before {@link #open()} returns. No probe is configured by default.
         * The probe receives the new handle and may perform its startup conversation. Its I/O consumes real output;
         * use the handle's normal protocol operations. Failure closes the new process. Configure its wait with
         * {@link #withReadinessTimeout(Duration)}.
         *
         * <p>The retained probe runs once per session or pool worker on a task thread. Concurrent launches may
         * invoke it concurrently; state shared between invocations must be thread-safe.
         *
         * @param readinessProbe probe operating on the newly opened handle
         * @return updated immutable draft
         */
        Draft withReadiness(Consumer<Expect> readinessProbe);

        /**
         * Sets the wait for a configured readiness probe. The default is five seconds.
         * This setting has no effect without a probe and does not bound process launch itself. Timeout closes the
         * new session; a probe that ignores interruption may continue after the open fails.
         *
         * @param readinessTimeout strictly positive probe timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code readinessTimeout} is zero or negative
         */
        Draft withReadinessTimeout(Duration readinessTimeout);

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
         * Sets the default timeout for each prompt match; the default is five seconds.
         * This bounds waiting for the serialized matcher slot and matching output, not the entire session or a send.
         * An individual Expect operation may override it. See {@link Expect} for retryable and terminal timeouts.
         *
         * @param timeout strictly positive match timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code timeout} is zero or negative
         */
        Draft withTimeout(Duration timeout);

        /**
         * Bounds the retained diagnostic tail, including action and stream labels. The default is 65,536 UTF-16
         * code units. Older text is discarded when full. This does not set the buffer used for matching.
         *
         * @param transcriptLimit positive number of UTF-16 code units
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code transcriptLimit} is not positive
         */
        Draft withTranscriptLimit(int transcriptLimit);

        /**
         * Bounds the retained stdout suffix available to prompt matchers. The default is 65,536 UTF-16 code units.
         * Old output is evicted when full, even if it has not matched. This buffer is independent of diagnostic
         * transcript retention; increasing the transcript limit does not preserve more searchable output.
         *
         * @param matchBufferLimit positive number of UTF-16 code units
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code matchBufferLimit} is not positive
         */
        Draft withMatchBufferLimit(int matchBufferLimit);

        /**
         * Removes 7-bit ANSI CSI sequences starting with {@code ESC [} before matching and transcript retention.
         * Stripping is disabled by default. This is not a terminal emulator: carriage returns, backspaces, and
         * other escape families do not redraw a screen. See {@link Expect} for matching semantics.
         *
         * @return updated immutable draft
         */
        Draft withAnsiControlSequenceStripping();

        /**
         * Controls retention of caller-supplied action values such as sent text and match patterns.
         * The default is {@link ExpectTranscriptValues#REDACTED}. Process output is not automatically redacted,
         * so a child that echoes a secret can still expose it in a transcript or match result.
         *
         * @param transcriptValues action-value retention policy
         * @return updated immutable draft
         */
        Draft withTranscriptValues(ExpectTranscriptValues transcriptValues);

        /**
         * Starts an independent process and returns its handle after the configured readiness probe succeeds.
         * Procwright owns output reading; do not attach another reader to the child streams. The caller must close the handle, preferably with try-with-resources.
         *
         * <p>Readiness runs once on a task thread. Failure or timeout closes the newly opened process before the
         * failure is reported; a probe that ignores interruption can outlive the failed open. The readiness
         * timeout does not bound process launch. See {@link Expect} for operation and close semantics.
         *
         * @return newly opened handle owned by the caller
         * @throws io.github.ulviar.procwright.command.CommandExecutionException if launch or setup fails, or readiness
         *     fails ({@code READINESS_FAILED}) or times out ({@code READINESS_TIMEOUT})
         */
        Expect open();
    }

    private record ImmutableDraft(
            ScenarioRuntime runtime,
            SessionSettings session,
            ExpectSettings expect,
            ReadinessSettings<Expect> readiness)
            implements Draft {

        private ImmutableDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(expect, "expect");
            Objects.requireNonNull(readiness, "readiness");
        }

        @Override
        public Draft withArg(String argument) {
            return withSession(session.withLaunch(session.launch().withArg(argument)));
        }

        @Override
        public Draft withArgs(String... arguments) {
            return withSession(session.withLaunch(session.launch().withArgs(arguments)));
        }

        @Override
        public Draft withArgs(Collection<String> arguments) {
            return withSession(session.withLaunch(session.launch().withArgs(arguments)));
        }

        @Override
        public Draft withWorkingDirectory(Path workingDirectory) {
            return withSession(session.withLaunch(session.launch().withWorkingDirectory(workingDirectory)));
        }

        @Override
        public Draft withEnvironment(String name, String value) {
            return withSession(session.withLaunch(session.launch().withEnvironment(name, value)));
        }

        @Override
        public Draft withInheritedEnvironment() {
            return withSession(session.withLaunch(session.launch().withInheritedEnvironment()));
        }

        @Override
        public Draft withCleanEnvironment() {
            return withSession(session.withLaunch(session.launch().withCleanEnvironment()));
        }

        @Override
        public Draft withShutdown(ShutdownPolicy shutdownPolicy) {
            return withSession(session.withShutdownPolicy(shutdownPolicy));
        }

        @Override
        public Draft withIdleTimeout(Duration idleTimeout) {
            return withSession(session.withIdleTimeout(idleTimeout));
        }

        @Override
        public Draft withCharset(Charset charset) {
            return withSession(session.withCharset(charset));
        }

        @Override
        public Draft withOutputCharset(Charset outputCharset) {
            return withExpect(expect.withOutputCharset(outputCharset));
        }

        @Override
        public Draft withTerminal(TerminalPolicy terminalPolicy) {
            return withSession(session.withTerminal(session.terminal().withPolicy(terminalPolicy)));
        }

        @Override
        public Draft withPtyProvider(PtyProvider ptyProvider) {
            return withSession(session.withTerminal(session.terminal().withProvider(ptyProvider)));
        }

        @Override
        public Draft withTerminalSize(TerminalSize terminalSize) {
            return withSession(session.withTerminal(session.terminal().withSize(terminalSize)));
        }

        @Override
        public Draft withReadiness(Consumer<Expect> readinessProbe) {
            return new ImmutableDraft(runtime, session, expect, readiness.withProbe(readinessProbe));
        }

        @Override
        public Draft withReadinessTimeout(Duration readinessTimeout) {
            return new ImmutableDraft(runtime, session, expect, readiness.withTimeout(readinessTimeout));
        }

        @Override
        public Draft withDiagnosticListener(DiagnosticListener listener) {
            return withSession(session.withDiagnostics(session.diagnostics().withListener(listener)));
        }

        @Override
        public Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink) {
            return withSession(session.withDiagnostics(session.diagnostics().withTranscriptSink(transcriptSink)));
        }

        @Override
        public Draft withTimeout(Duration timeout) {
            return withExpect(expect.withTimeout(timeout));
        }

        @Override
        public Draft withTranscriptLimit(int transcriptLimit) {
            return withExpect(expect.withTranscriptLimit(transcriptLimit));
        }

        @Override
        public Draft withMatchBufferLimit(int matchBufferLimit) {
            return withExpect(expect.withMatchBufferLimit(matchBufferLimit));
        }

        @Override
        public Draft withAnsiControlSequenceStripping() {
            return withExpect(expect.withAnsiControlSequenceStripping());
        }

        @Override
        public Draft withTranscriptValues(ExpectTranscriptValues transcriptValues) {
            return withExpect(expect.withTranscriptValues(transcriptValues));
        }

        @Override
        public Expect open() {
            return runtime.openExpect(session, expect, readiness);
        }

        private Draft withSession(SessionSettings updated) {
            return new ImmutableDraft(runtime, updated, expect, readiness);
        }

        private Draft withExpect(ExpectSettings updated) {
            return new ImmutableDraft(runtime, session, updated, readiness);
        }
    }
}

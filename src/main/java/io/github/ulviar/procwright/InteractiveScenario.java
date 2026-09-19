/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

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

/** Interactive scenario family with a pre-launch choice between raw I/O and Expect automation. */
public final class InteractiveScenario {

    private InteractiveScenario() {}

    static Entry draft(ScenarioRuntime runtime) {
        return new ImmutableDraft(
                runtime, SessionSettings.defaults(runtime.commandSpec()), ReadinessSettings.defaults());
    }

    /**
     * Initial interactive branch. Choose Expect before applying raw-session configuration.
     *
     * <p>Any inherited {@code with*} method returns {@link Draft}, which intentionally no longer exposes
     * {@link #expect()}. This makes the process-output owner a scenario choice instead of a runtime race.
     */
    public interface Entry extends Draft {

        /**
         * Selects prompt automation with process output owned by the returned Expect handle.
         *
         * @return a new Expect draft initialized with interactive-process defaults
         */
        ExpectScenario.Draft expect();
    }

    /**
     * Immutable configuration for raw interactive sessions, obtained from {@link CommandService#interactive()}.
     *
     * <p>Each {@code with*} call returns a new draft without changing this one or starting a process. Launch settings
     * begin with the service's command specification. Defaults are UTF-8 text input, no idle timeout, no readiness
     * probe, and terminal transport disabled. The caller owns reading both stdout and stderr; see {@link Session}.
     *
     * <p>The Draft retains its PTY provider, readiness probe, diagnostic listener, and transcript sink. Concurrent
     * {@link #open()} calls can invoke each supplied instance concurrently from independent sessions. Retained instances
     * must be thread-safe; otherwise, use separate Draft branches with separate callback or provider instances.
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
         * Sets the charset used by the session's text send helpers. The default is UTF-8.
         * Raw stdout and stderr remain byte streams; the caller chooses how to decode them.
         *
         * @param charset input encoding charset
         * @return updated immutable draft
         */
        Draft withCharset(Charset charset);

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
        Draft withReadiness(Consumer<Session> readinessProbe);

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
         * Starts an independent process and returns its handle after the configured readiness probe succeeds.
         * The caller must consume both stdout and stderr; Procwright does not drain raw output. The caller must close the handle, preferably with try-with-resources.
         *
         * <p>Readiness runs once on a task thread. Failure or timeout closes the newly opened process before the
         * failure is reported; a probe that ignores interruption can outlive the failed open. The readiness
         * timeout does not bound process launch. See {@link Session} for operation and close semantics.
         *
         * @return newly opened handle owned by the caller
         * @throws io.github.ulviar.procwright.command.CommandExecutionException if launch or setup fails, or readiness
         *     fails ({@code READINESS_FAILED}) or times out ({@code READINESS_TIMEOUT})
         */
        Session open();
    }

    private record ImmutableDraft(
            ScenarioRuntime runtime, SessionSettings settings, ReadinessSettings<Session> readiness) implements Entry {

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
            return withSettings(settings.withLaunch(settings.launch().withInheritedEnvironment()));
        }

        @Override
        public Draft withCleanEnvironment() {
            return withSettings(settings.withLaunch(settings.launch().withCleanEnvironment()));
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

        @Override
        public ExpectScenario.Draft expect() {
            return ExpectScenario.draft(runtime, settings);
        }

        private Draft withSettings(SessionSettings updated) {
            return new ImmutableDraft(runtime, updated, readiness);
        }
    }
}

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

/** Namespace for immutable prompt-automation drafts. */
public final class ExpectScenario {

    private ExpectScenario() {}

    static Draft draft(ScenarioRuntime runtime, SessionSettings session) {
        return new ImmutableDraft(runtime, session, ExpectSettings.defaults(), ReadinessSettings.defaults());
    }

    /**
     * Persistent configuration for opening independent Expect processes.
     *
     * <p>Each {@code with*} method returns a new draft. The draft starts no process and may be reused concurrently;
     * {@link #open()} starts one process whose output belongs to the returned {@link Expect}.
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
         * Sets the process idle timeout; zero disables it.
         *
         * @param idleTimeout non-negative idle timeout
         * @return updated draft
         */
        Draft withIdleTimeout(Duration idleTimeout);

        /**
         * Sets the charset used for both text input and output decoding.
         *
         * @param charset text charset
         * @return updated draft
         */
        Draft withCharset(Charset charset);

        /**
         * Overrides only stdout and stderr decoding while retaining the input charset.
         *
         * @param outputCharset process output charset
         * @return updated draft
         */
        Draft withOutputCharset(Charset outputCharset);

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
         * @param readinessProbe readiness probe operating through Expect
         * @return updated draft
         */
        Draft withReadiness(Consumer<Expect> readinessProbe);

        /**
         * Sets the maximum time allowed for the readiness probe.
         *
         * @param readinessTimeout positive readiness timeout
         * @return updated draft
         */
        Draft withReadinessTimeout(Duration readinessTimeout);

        /**
         * Observes process lifecycle diagnostics.
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
         * Sets the default deadline for each prompt match.
         *
         * @param timeout positive match timeout
         * @return updated draft
         */
        Draft withTimeout(Duration timeout);

        /**
         * Sets the retained diagnostic transcript limit.
         *
         * @param transcriptLimit positive character limit
         * @return updated draft
         */
        Draft withTranscriptLimit(int transcriptLimit);

        /**
         * Sets the retained output available to prompt matchers.
         *
         * @param matchBufferLimit positive character limit
         * @return updated draft
         */
        Draft withMatchBufferLimit(int matchBufferLimit);

        /**
         * Removes supported ANSI CSI sequences before matching and transcript retention.
         *
         * @return updated draft
         */
        Draft withAnsiControlSequenceStripping();

        /**
         * Selects whether caller-provided action values are redacted in transcripts.
         *
         * @param transcriptValues transcript value policy
         * @return updated draft
         */
        Draft withTranscriptValues(ExpectTranscriptValues transcriptValues);

        /**
         * Starts a new process, opens its output pumps, runs readiness, and returns the Expect handle.
         *
         * @return newly opened Expect handle
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

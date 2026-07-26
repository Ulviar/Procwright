/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ReadinessSettings;
import io.github.ulviar.procwright.internal.SessionScenarioSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class LineSessionDrafts {

    private LineSessionDrafts() {}

    static LineSessionScenario.Draft create(ScenarioRuntime runtime) {
        return new ImmutableDraft(
                runtime,
                new SessionScenarioSettings<>(
                        SessionSettings.defaults(runtime.commandSpec()),
                        ReadinessSettings.defaults(),
                        LineSessionSettings.defaults()));
    }

    private record ImmutableDraft(
            ScenarioRuntime runtime, SessionScenarioSettings<LineSession, LineSessionSettings> settings)
            implements LineSessionScenario.Draft {

        private ImmutableDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(settings, "settings");
        }

        @Override
        public LineSessionScenario.Draft withArg(String argument) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArg(argument)));
        }

        @Override
        public LineSessionScenario.Draft withArgs(String... arguments) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArgs(arguments)));
        }

        @Override
        public LineSessionScenario.Draft withArgs(Collection<String> arguments) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArgs(arguments)));
        }

        @Override
        public LineSessionScenario.Draft withWorkingDirectory(Path workingDirectory) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withWorkingDirectory(workingDirectory)));
        }

        @Override
        public LineSessionScenario.Draft withEnvironment(String name, String value) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withEnvironment(name, value)));
        }

        @Override
        public LineSessionScenario.Draft withInheritedEnvironment() {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withInheritedEnvironment()));
        }

        @Override
        public LineSessionScenario.Draft withCleanEnvironment() {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withCleanEnvironment()));
        }

        @Override
        public LineSessionScenario.Draft withShutdown(ShutdownPolicy shutdownPolicy) {
            return withSession(settings.session().withShutdownPolicy(shutdownPolicy));
        }

        @Override
        public LineSessionScenario.Draft withIdleTimeout(Duration idleTimeout) {
            return withSession(settings.session().withIdleTimeout(idleTimeout));
        }

        @Override
        public LineSessionScenario.Draft withTerminal(TerminalPolicy terminalPolicy) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withPolicy(terminalPolicy)));
        }

        @Override
        public LineSessionScenario.Draft withPtyProvider(PtyProvider ptyProvider) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withProvider(ptyProvider)));
        }

        @Override
        public LineSessionScenario.Draft withTerminalSize(TerminalSize terminalSize) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withSize(terminalSize)));
        }

        @Override
        public LineSessionScenario.Draft withReadiness(Consumer<LineSession> readinessProbe) {
            return withReadiness(settings.readiness().withProbe(readinessProbe));
        }

        @Override
        public LineSessionScenario.Draft withReadinessTimeout(Duration readinessTimeout) {
            return withReadiness(settings.readiness().withTimeout(readinessTimeout));
        }

        @Override
        public LineSessionScenario.Draft withRequestTimeout(Duration requestTimeout) {
            return withProtocol(settings.protocol().withRequestTimeout(requestTimeout));
        }

        @Override
        public LineSessionScenario.Draft withTranscriptLimit(int transcriptLimit) {
            return withProtocol(settings.protocol().withTranscriptLimit(transcriptLimit));
        }

        @Override
        public LineSessionScenario.Draft withStdoutBacklogLines(int stdoutBacklogLines) {
            return withProtocol(settings.protocol().withStdoutBacklogLines(stdoutBacklogLines));
        }

        @Override
        public LineSessionScenario.Draft withStdoutBacklogChars(int stdoutBacklogChars) {
            return withProtocol(settings.protocol().withStdoutBacklogChars(stdoutBacklogChars));
        }

        @Override
        public LineSessionScenario.Draft withMaxLineChars(int maxLineChars) {
            return withProtocol(settings.protocol().withMaxLineChars(maxLineChars));
        }

        @Override
        public LineSessionScenario.Draft withMaxRequestBytes(int maxRequestBytes) {
            return withProtocol(settings.protocol().withMaxRequestBytes(maxRequestBytes));
        }

        @Override
        public LineSessionScenario.Draft withMaxRequestChars(int maxRequestChars) {
            return withProtocol(settings.protocol().withMaxRequestChars(maxRequestChars));
        }

        @Override
        public LineSessionScenario.Draft withMaxResponseLines(int maxResponseLines) {
            return withProtocol(settings.protocol().withMaxResponseLines(maxResponseLines));
        }

        @Override
        public LineSessionScenario.Draft withMaxResponseChars(int maxResponseChars) {
            return withProtocol(settings.protocol().withMaxResponseChars(maxResponseChars));
        }

        @Override
        public LineSessionScenario.Draft withCharset(Charset charset) {
            return withCharsetPolicy(CharsetPolicy.replace(charset));
        }

        @Override
        public LineSessionScenario.Draft withCharsetPolicy(CharsetPolicy charsetPolicy) {
            return withProtocol(
                    settings.protocol().withCharsetPolicy(Objects.requireNonNull(charsetPolicy, "charsetPolicy")));
        }

        @Override
        public LineSessionScenario.Draft withResponseDecoder(ResponseDecoder responseDecoder) {
            return withProtocol(settings.protocol().withResponseDecoder(responseDecoder));
        }

        @Override
        public LineSessionScenario.Draft withDiagnosticListener(DiagnosticListener listener) {
            return withSession(settings.session()
                    .withDiagnostics(settings.session().diagnostics().withListener(listener)));
        }

        @Override
        public LineSessionScenario.Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink) {
            return withSession(settings.session()
                    .withDiagnostics(settings.session().diagnostics().withTranscriptSink(transcriptSink)));
        }

        @Override
        public LineSessionScenario.PoolDraft pooled() {
            WorkerPoolSettings<LineSession> poolSettings = WorkerPoolSettings.defaults();
            return new ImmutablePoolDraft(runtime, settings, poolSettings);
        }

        @Override
        public LineSession open() {
            return runtime.openLineSession(settings);
        }

        private LineSessionScenario.Draft withSession(SessionSettings session) {
            return copy(settings.withSession(session));
        }

        private LineSessionScenario.Draft withReadiness(ReadinessSettings<LineSession> readiness) {
            return copy(settings.withReadiness(readiness));
        }

        private LineSessionScenario.Draft withProtocol(LineSessionSettings protocol) {
            return copy(settings.withProtocol(protocol));
        }

        private LineSessionScenario.Draft copy(SessionScenarioSettings<LineSession, LineSessionSettings> updated) {
            return new ImmutableDraft(runtime, updated);
        }
    }

    private record ImmutablePoolDraft(
            ScenarioRuntime runtime,
            SessionScenarioSettings<LineSession, LineSessionSettings> worker,
            WorkerPoolSettings<LineSession> pool)
            implements LineSessionScenario.PoolDraft {

        private ImmutablePoolDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(pool, "pool");
        }

        @Override
        public LineSessionScenario.PoolDraft withMaxSize(int maxSize) {
            return copy(pool.withMaxSize(maxSize));
        }

        @Override
        public LineSessionScenario.PoolDraft withWarmupSize(int warmupSize) {
            return copy(pool.withWarmupSize(warmupSize));
        }

        @Override
        public LineSessionScenario.PoolDraft withMinIdle(int minIdle) {
            return copy(pool.withMinIdle(minIdle));
        }

        @Override
        public LineSessionScenario.PoolDraft withAcquireTimeout(Duration acquireTimeout) {
            return copy(pool.withAcquireTimeout(acquireTimeout));
        }

        @Override
        public LineSessionScenario.PoolDraft withHookTimeout(Duration hookTimeout) {
            return copy(pool.withHookTimeout(hookTimeout));
        }

        @Override
        public LineSessionScenario.PoolDraft withCloseTimeout(Duration closeTimeout) {
            return copy(pool.withCloseTimeout(closeTimeout));
        }

        @Override
        public LineSessionScenario.PoolDraft withMaxRequestsPerWorker(int maxRequestsPerWorker) {
            return copy(pool.withMaxRequestsPerWorker(maxRequestsPerWorker));
        }

        @Override
        public LineSessionScenario.PoolDraft withMaxWorkerAge(Duration maxWorkerAge) {
            return copy(pool.withMaxWorkerAge(maxWorkerAge));
        }

        @Override
        public LineSessionScenario.PoolDraft withReset(Consumer<LineSession> resetHook) {
            return copy(pool.withResetHook(resetHook));
        }

        @Override
        public LineSessionScenario.PoolDraft withHealthCheck(Predicate<LineSession> healthCheck) {
            return copy(pool.withHealthCheck(healthCheck));
        }

        @Override
        public PooledLineSession open() {
            return runtime.openPooledLineSession(worker, pool.validateForOpen());
        }

        private LineSessionScenario.PoolDraft copy(WorkerPoolSettings<LineSession> updated) {
            return new ImmutablePoolDraft(runtime, worker, updated);
        }
    }
}

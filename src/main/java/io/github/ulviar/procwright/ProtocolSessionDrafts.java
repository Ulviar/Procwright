/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.ReadinessSettings;
import io.github.ulviar.procwright.internal.SessionScenarioSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
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
import java.util.function.Supplier;

final class ProtocolSessionDrafts {

    private ProtocolSessionDrafts() {}

    static <I extends Object, O extends Object> ProtocolSessionScenario.Draft<I, O> create(
            ScenarioRuntime runtime, Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        return new ImmutableDraft<>(
                runtime, initialSettings(runtime), Objects.requireNonNull(adapterFactory, "adapterFactory"));
    }

    private static <I extends Object, O extends Object>
            SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> initialSettings(
                    ScenarioRuntime runtime) {
        return new SessionScenarioSettings<>(
                SessionSettings.defaults(runtime.commandSpec()),
                ReadinessSettings.defaults(),
                ProtocolSessionSettings.defaults());
    }

    private record ImmutableDraft<I extends Object, O extends Object>(
            ScenarioRuntime runtime,
            SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> settings,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory)
            implements ProtocolSessionScenario.Draft<I, O> {

        private ImmutableDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(adapterFactory, "adapterFactory");
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withArg(String argument) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArg(argument)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withArgs(String... arguments) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArgs(arguments)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withArgs(Collection<String> arguments) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArgs(arguments)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withWorkingDirectory(Path workingDirectory) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withWorkingDirectory(workingDirectory)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withEnvironment(String name, String value) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withEnvironment(name, value)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withInheritedEnvironment() {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withInheritedEnvironment()));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withCleanEnvironment() {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withCleanEnvironment()));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withShutdown(ShutdownPolicy shutdownPolicy) {
            return withSession(settings.session().withShutdownPolicy(shutdownPolicy));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withIdleTimeout(Duration idleTimeout) {
            return withSession(settings.session().withIdleTimeout(idleTimeout));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withTerminal(TerminalPolicy terminalPolicy) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withPolicy(terminalPolicy)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withPtyProvider(PtyProvider ptyProvider) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withProvider(ptyProvider)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withTerminalSize(TerminalSize terminalSize) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withSize(terminalSize)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withReadiness(Consumer<ProtocolSession<I, O>> readinessProbe) {
            return withReadiness(settings.readiness().withProbe(readinessProbe));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withReadinessTimeout(Duration readinessTimeout) {
            return withReadiness(settings.readiness().withTimeout(readinessTimeout));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withRequestTimeout(Duration requestTimeout) {
            return withProtocol(settings.protocol().withRequestTimeout(requestTimeout));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withTranscriptLimit(int transcriptLimit) {
            return withProtocol(settings.protocol().withTranscriptLimit(transcriptLimit));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withOutputBacklogLimit(int outputBacklogLimit) {
            return withProtocol(settings.protocol().withOutputBacklogLimit(outputBacklogLimit));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withMaxRequestBytes(int maxRequestBytes) {
            return withProtocol(settings.protocol().withMaxRequestBytes(maxRequestBytes));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withMaxRequestChars(int maxRequestChars) {
            return withProtocol(settings.protocol().withMaxRequestChars(maxRequestChars));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withMaxResponseBytes(int maxResponseBytes) {
            return withProtocol(settings.protocol().withMaxResponseBytes(maxResponseBytes));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withMaxResponseChars(int maxResponseChars) {
            return withProtocol(settings.protocol().withMaxResponseChars(maxResponseChars));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withCharset(Charset charset) {
            return withCharsetPolicy(CharsetPolicy.replace(charset));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withCharsetPolicy(CharsetPolicy charsetPolicy) {
            return withProtocol(
                    settings.protocol().withCharsetPolicy(Objects.requireNonNull(charsetPolicy, "charsetPolicy")));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withDiagnosticListener(DiagnosticListener listener) {
            return withSession(settings.session()
                    .withDiagnostics(settings.session().diagnostics().withListener(listener)));
        }

        @Override
        public ProtocolSessionScenario.Draft<I, O> withDiagnosticTranscriptSink(
                DiagnosticTranscriptSink transcriptSink) {
            return withSession(settings.session()
                    .withDiagnostics(settings.session().diagnostics().withTranscriptSink(transcriptSink)));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> pooled() {
            WorkerPoolSettings<ProtocolSession<I, O>> poolSettings = WorkerPoolSettings.defaults();
            return new ImmutablePoolDraft<>(runtime, settings, adapterFactory, poolSettings);
        }

        @Override
        public ProtocolSession<I, O> open() {
            return runtime.openProtocolSession(adapterFactory, settings);
        }

        private ProtocolSessionScenario.Draft<I, O> withSession(SessionSettings session) {
            return copy(settings.withSession(session));
        }

        private ProtocolSessionScenario.Draft<I, O> withReadiness(ReadinessSettings<ProtocolSession<I, O>> readiness) {
            return copy(settings.withReadiness(readiness));
        }

        private ProtocolSessionScenario.Draft<I, O> withProtocol(ProtocolSessionSettings protocol) {
            return copy(settings.withProtocol(protocol));
        }

        private ProtocolSessionScenario.Draft<I, O> copy(
                SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> updated) {
            return new ImmutableDraft<>(runtime, updated, adapterFactory);
        }
    }

    private record ImmutablePoolDraft<I extends Object, O extends Object>(
            ScenarioRuntime runtime,
            SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> worker,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            WorkerPoolSettings<ProtocolSession<I, O>> pool)
            implements ProtocolSessionScenario.PoolDraft<I, O> {

        private ImmutablePoolDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(adapterFactory, "adapterFactory");
            Objects.requireNonNull(pool, "pool");
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withMaxSize(int maxSize) {
            return copy(pool.withMaxSize(maxSize));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withWarmupSize(int warmupSize) {
            return copy(pool.withWarmupSize(warmupSize));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withMinIdle(int minIdle) {
            return copy(pool.withMinIdle(minIdle));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withAcquireTimeout(Duration acquireTimeout) {
            return copy(pool.withAcquireTimeout(acquireTimeout));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withHookTimeout(Duration hookTimeout) {
            return copy(pool.withHookTimeout(hookTimeout));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withCloseTimeout(Duration closeTimeout) {
            return copy(pool.withCloseTimeout(closeTimeout));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withMaxRequestsPerWorker(int maxRequestsPerWorker) {
            return copy(pool.withMaxRequestsPerWorker(maxRequestsPerWorker));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withMaxWorkerAge(Duration maxWorkerAge) {
            return copy(pool.withMaxWorkerAge(maxWorkerAge));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withBackgroundReplenishment(boolean backgroundReplenishment) {
            return copy(pool.withBackgroundReplenishment(backgroundReplenishment));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withReset(Consumer<ProtocolSession<I, O>> resetHook) {
            return copy(pool.withResetHook(resetHook));
        }

        @Override
        public ProtocolSessionScenario.PoolDraft<I, O> withHealthCheck(Predicate<ProtocolSession<I, O>> healthCheck) {
            return copy(pool.withHealthCheck(healthCheck));
        }

        @Override
        public PooledProtocolSession<I, O> open() {
            return runtime.openPooledProtocolSession(adapterFactory, worker, pool.validateForOpen());
        }

        private ProtocolSessionScenario.PoolDraft<I, O> copy(WorkerPoolSettings<ProtocolSession<I, O>> updated) {
            return new ImmutablePoolDraft<>(runtime, worker, adapterFactory, updated);
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
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
import io.github.ulviar.procwright.session.PooledLineSessionException;
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

/** Namespace for immutable line-session and line-pool drafts. */
public final class LineSessionScenario {

    private LineSessionScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return new ImmutableDraft(
                runtime,
                new SessionScenarioSettings<>(
                        SessionSettings.defaults(runtime.launchSettings()),
                        ReadinessSettings.defaults(),
                        LineSessionSettings.defaults()));
    }

    /**
     * Persistent write-only configuration for opening line-oriented workers.
     *
     * <p>The Draft retains its PTY provider, readiness probe, response decoder, diagnostic listener, and transcript
     * sink. One opened worker serializes requests and response-decoder calls. Concurrent {@link #open()} calls, or
     * workers created through {@link #pooled()}, can still invoke the same retained instances concurrently. Retained
     * instances must be thread-safe; otherwise, use separate Draft branches with separate callback or provider
     * instances.
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
         * Appends a copied argument array.
         *
         * @param arguments arguments to append
         * @return updated draft
         */
        Draft withArgs(String... arguments);

        /**
         * Appends a copied argument collection.
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
         * Sets the caller-visible idle timeout.
         *
         * @param idleTimeout non-negative timeout; zero disables it
         * @return updated draft
         */
        Draft withIdleTimeout(Duration idleTimeout);

        /**
         * Selects the terminal requirement.
         *
         * @param terminalPolicy terminal policy
         * @return updated draft
         */
        Draft withTerminal(TerminalPolicy terminalPolicy);

        /**
         * Sets the provider used for terminal launches.
         *
         * @param ptyProvider PTY provider
         * @return updated draft
         */
        Draft withPtyProvider(PtyProvider ptyProvider);

        /**
         * Sets requested terminal dimensions.
         *
         * @param terminalSize terminal size
         * @return updated draft
         */
        Draft withTerminalSize(TerminalSize terminalSize);

        /**
         * Sets a probe completed before open returns.
         *
         * <p>The Draft retains the probe. Each direct session and each pooled worker invokes it once before becoming
         * available; worker starts can overlap and invoke the same probe instance concurrently.
         *
         * @param readinessProbe readiness probe
         * @return updated draft
         */
        Draft withReadiness(Consumer<LineSession> readinessProbe);

        /**
         * Sets the readiness timeout.
         *
         * @param readinessTimeout positive timeout
         * @return updated draft
         */
        Draft withReadinessTimeout(Duration readinessTimeout);

        /**
         * Sets the timeout for each request.
         *
         * @param requestTimeout positive timeout
         * @return updated draft
         */
        Draft withRequestTimeout(Duration requestTimeout);

        /**
         * Sets the retained transcript limit.
         *
         * @param transcriptLimit positive character limit
         * @return updated draft
         */
        Draft withTranscriptLimit(int transcriptLimit);

        /**
         * Sets the queued stdout line limit.
         *
         * @param stdoutBacklogLines positive line limit
         * @return updated draft
         */
        Draft withStdoutBacklogLines(int stdoutBacklogLines);

        /**
         * Sets the queued stdout character limit.
         *
         * @param stdoutBacklogChars positive character limit
         * @return updated draft
         */
        Draft withStdoutBacklogChars(int stdoutBacklogChars);

        /**
         * Sets the maximum decoded line length.
         *
         * @param maxLineChars positive character limit
         * @return updated draft
         */
        Draft withMaxLineChars(int maxLineChars);

        /**
         * Sets the encoded request byte limit.
         *
         * @param maxRequestBytes positive byte limit
         * @return updated draft
         */
        Draft withMaxRequestBytes(int maxRequestBytes);

        /**
         * Sets the request character limit.
         *
         * @param maxRequestChars positive character limit
         * @return updated draft
         */
        Draft withMaxRequestChars(int maxRequestChars);

        /**
         * Sets the response line limit.
         *
         * @param maxResponseLines positive line limit
         * @return updated draft
         */
        Draft withMaxResponseLines(int maxResponseLines);

        /**
         * Sets the response character limit.
         *
         * @param maxResponseChars positive character limit
         * @return updated draft
         */
        Draft withMaxResponseChars(int maxResponseChars);

        /**
         * Selects forgiving request and response decoding.
         *
         * @param charset protocol charset
         * @return updated draft
         */
        Draft withCharset(Charset charset);

        /**
         * Sets the charset and malformed-input policy.
         *
         * @param charsetPolicy charset policy
         * @return updated draft
         */
        Draft withCharsetPolicy(CharsetPolicy charsetPolicy);

        /**
         * Sets the decoder that determines response completion.
         *
         * <p>The Draft retains the decoder. Calls are serialized within one worker, but direct sessions and pooled
         * workers can invoke the same decoder instance concurrently.
         *
         * @param responseDecoder response decoder
         * @return updated draft
         */
        Draft withResponseDecoder(ResponseDecoder responseDecoder);

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
         * Branches into unopened pool configuration.
         *
         * @return pool draft carrying this worker configuration
         */
        PoolDraft pooled();

        /**
         * Opens one line-oriented worker.
         *
         * @return newly opened line session
         */
        LineSession open();
    }

    /**
     * Persistent write-only configuration for opening pools of line-oriented workers.
     *
     * <p>The PoolDraft retains the worker callbacks carried by {@link Draft#pooled()} and its health and reset callbacks.
     * One worker runs health, request, and reset work without overlap, but different workers or pools opened from this
     * PoolDraft can invoke the same retained callback instances concurrently. Retained instances must be thread-safe;
     * otherwise, use separate Draft or PoolDraft branches with separate callback instances.
     *
     * <p>A pool's configured maximum is a per-pool bound and does not reserve process-wide capacity. Across all line and
     * protocol pools, at most 256 workers may collectively hold admission while starting, live, or retiring. Worker
     * admission is acquired before the worker factory is invoked and is retained until physical retirement completes;
     * a non-cooperative retirement therefore continues to consume it. Independently, at most 256 pool-completion owners
     * and their pools may be retained concurrently; that admission precedes completion-owner startup and warmup.
     * Saturated pool opening or warmup fails with
     * {@link PooledLineSessionException.Reason#STARTUP_FAILED}; saturated demand acquisition fails with
     * {@link PooledLineSessionException.Reason#ACQUIRE_TIMEOUT}. Released capacity has no specified inter-pool ordering.
     */
    public interface PoolDraft {
        /**
         * Sets the per-pool maximum capacity without reserving any part of the process-wide worker limit.
         *
         * @param maxSize per-pool worker limit from 1 through 256
         * @return updated pool draft
         */
        PoolDraft withMaxSize(int maxSize);

        /**
         * Sets synchronous warmup capacity.
         *
         * @param warmupSize workers opened during pool open
         * @return updated pool draft
         */
        PoolDraft withWarmupSize(int warmupSize);

        /**
         * Sets the maintained idle-worker floor.
         *
         * @param minIdle non-negative idle-worker count
         * @return updated pool draft
         */
        PoolDraft withMinIdle(int minIdle);

        /**
         * Sets the worker acquisition timeout.
         *
         * @param acquireTimeout positive timeout
         * @return updated pool draft
         */
        PoolDraft withAcquireTimeout(Duration acquireTimeout);

        /**
         * Sets the reset and health-hook timeout.
         *
         * @param hookTimeout positive timeout
         * @return updated pool draft
         */
        PoolDraft withHookTimeout(Duration hookTimeout);

        /**
         * Sets how long {@link PooledLineSession#close()} waits for active requests and worker shutdown.
         *
         * <p>The default is 15 seconds.
         *
         * @param closeTimeout positive close timeout
         * @return updated pool draft
         */
        PoolDraft withCloseTimeout(Duration closeTimeout);

        /**
         * Sets the request-count retirement threshold.
         *
         * @param maxRequestsPerWorker positive request limit
         * @return updated pool draft
         */
        PoolDraft withMaxRequestsPerWorker(int maxRequestsPerWorker);

        /**
         * Sets the worker age retirement threshold.
         *
         * @param maxWorkerAge non-negative age; zero disables it
         * @return updated pool draft
         */
        PoolDraft withMaxWorkerAge(Duration maxWorkerAge);

        /**
         * Enables or disables background worker replacement.
         *
         * @param backgroundReplenishment whether background replacement is enabled
         * @return updated pool draft
         */
        PoolDraft withBackgroundReplenishment(boolean backgroundReplenishment);

        /**
         * Sets the hook run before a worker returns to idle.
         *
         * <p>The PoolDraft retains the hook. It does not overlap with request or health work for the same worker, but it
         * can overlap with hook or request work on other workers or pools.
         *
         * @param resetHook reset hook
         * @return updated pool draft
         */
        PoolDraft withReset(Consumer<LineSession> resetHook);

        /**
         * Sets the predicate required before worker reuse.
         *
         * <p>The PoolDraft retains the predicate. It runs before a worker serves a request and does not overlap with
         * request or reset work for that worker, but it can overlap with hook or request work on other workers or pools.
         *
         * @param healthCheck health predicate
         * @return updated pool draft
         */
        PoolDraft withHealthCheck(Predicate<LineSession> healthCheck);

        /**
         * Opens a new worker pool.
         *
         * @return newly opened worker pool
         */
        PooledLineSession open();
    }

    private record ImmutableDraft(
            ScenarioRuntime runtime, SessionScenarioSettings<LineSession, LineSessionSettings> settings)
            implements Draft {

        private ImmutableDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(settings, "settings");
        }

        @Override
        public Draft withArg(String argument) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArg(argument)));
        }

        @Override
        public Draft withArgs(String... arguments) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArgs(arguments)));
        }

        @Override
        public Draft withArgs(Collection<String> arguments) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withArgs(arguments)));
        }

        @Override
        public Draft withWorkingDirectory(Path workingDirectory) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withWorkingDirectory(workingDirectory)));
        }

        @Override
        public Draft withEnvironment(String name, String value) {
            return withSession(
                    settings.session().withLaunch(settings.session().launch().withEnvironment(name, value)));
        }

        @Override
        public Draft withInheritedEnvironment() {
            return withSession(settings.session()
                    .withLaunch(settings.session().launch().withEnvironmentPolicy(EnvironmentPolicy.INHERIT)));
        }

        @Override
        public Draft withCleanEnvironment() {
            return withSession(settings.session()
                    .withLaunch(settings.session().launch().withEnvironmentPolicy(EnvironmentPolicy.CLEAN)));
        }

        @Override
        public Draft withShutdown(ShutdownPolicy shutdownPolicy) {
            return withSession(settings.session().withShutdownPolicy(shutdownPolicy));
        }

        @Override
        public Draft withIdleTimeout(Duration idleTimeout) {
            return withSession(settings.session().withIdleTimeout(idleTimeout));
        }

        @Override
        public Draft withTerminal(TerminalPolicy terminalPolicy) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withPolicy(terminalPolicy)));
        }

        @Override
        public Draft withPtyProvider(PtyProvider ptyProvider) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withProvider(ptyProvider)));
        }

        @Override
        public Draft withTerminalSize(TerminalSize terminalSize) {
            return withSession(settings.session()
                    .withTerminal(settings.session().terminal().withSize(terminalSize)));
        }

        @Override
        public Draft withReadiness(Consumer<LineSession> readinessProbe) {
            return withReadiness(settings.readiness().withProbe(readinessProbe));
        }

        @Override
        public Draft withReadinessTimeout(Duration readinessTimeout) {
            return withReadiness(settings.readiness().withTimeout(readinessTimeout));
        }

        @Override
        public Draft withRequestTimeout(Duration requestTimeout) {
            return withProtocol(settings.protocol().withRequestTimeout(requestTimeout));
        }

        @Override
        public Draft withTranscriptLimit(int transcriptLimit) {
            return withProtocol(settings.protocol().withTranscriptLimit(transcriptLimit));
        }

        @Override
        public Draft withStdoutBacklogLines(int stdoutBacklogLines) {
            return withProtocol(settings.protocol().withStdoutBacklogLines(stdoutBacklogLines));
        }

        @Override
        public Draft withStdoutBacklogChars(int stdoutBacklogChars) {
            return withProtocol(settings.protocol().withStdoutBacklogChars(stdoutBacklogChars));
        }

        @Override
        public Draft withMaxLineChars(int maxLineChars) {
            return withProtocol(settings.protocol().withMaxLineChars(maxLineChars));
        }

        @Override
        public Draft withMaxRequestBytes(int maxRequestBytes) {
            return withProtocol(settings.protocol().withMaxRequestBytes(maxRequestBytes));
        }

        @Override
        public Draft withMaxRequestChars(int maxRequestChars) {
            return withProtocol(settings.protocol().withMaxRequestChars(maxRequestChars));
        }

        @Override
        public Draft withMaxResponseLines(int maxResponseLines) {
            return withProtocol(settings.protocol().withMaxResponseLines(maxResponseLines));
        }

        @Override
        public Draft withMaxResponseChars(int maxResponseChars) {
            return withProtocol(settings.protocol().withMaxResponseChars(maxResponseChars));
        }

        @Override
        public Draft withCharset(Charset charset) {
            CharsetPolicy charsetPolicy = CharsetPolicy.replace(charset);
            return withSessionAndProtocol(
                    settings.session().withCharset(charsetPolicy.charset()),
                    settings.protocol().withCharsetPolicy(charsetPolicy));
        }

        @Override
        public Draft withCharsetPolicy(CharsetPolicy charsetPolicy) {
            Objects.requireNonNull(charsetPolicy, "charsetPolicy");
            return withSessionAndProtocol(
                    settings.session().withCharset(charsetPolicy.charset()),
                    settings.protocol().withCharsetPolicy(charsetPolicy));
        }

        @Override
        public Draft withResponseDecoder(ResponseDecoder responseDecoder) {
            return withProtocol(settings.protocol().withResponseDecoder(responseDecoder));
        }

        @Override
        public Draft withDiagnosticListener(DiagnosticListener listener) {
            return withSession(settings.session()
                    .withDiagnostics(settings.session().diagnostics().withListener(listener)));
        }

        @Override
        public Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink) {
            return withSession(settings.session()
                    .withDiagnostics(settings.session().diagnostics().withTranscriptSink(transcriptSink)));
        }

        @Override
        public PoolDraft pooled() {
            WorkerPoolSettings<LineSession> poolSettings = WorkerPoolSettings.defaults(worker -> {}, worker -> true);
            return new ImmutablePoolDraft(runtime, settings, poolSettings);
        }

        @Override
        public LineSession open() {
            return runtime.openLineSession(settings);
        }

        private Draft withSession(SessionSettings session) {
            return new ImmutableDraft(
                    runtime, new SessionScenarioSettings<>(session, settings.readiness(), settings.protocol()));
        }

        private Draft withReadiness(ReadinessSettings<LineSession> readiness) {
            return new ImmutableDraft(
                    runtime, new SessionScenarioSettings<>(settings.session(), readiness, settings.protocol()));
        }

        private Draft withProtocol(LineSessionSettings protocol) {
            return new ImmutableDraft(
                    runtime, new SessionScenarioSettings<>(settings.session(), settings.readiness(), protocol));
        }

        private Draft withSessionAndProtocol(SessionSettings session, LineSessionSettings protocol) {
            return new ImmutableDraft(runtime, new SessionScenarioSettings<>(session, settings.readiness(), protocol));
        }
    }

    private record ImmutablePoolDraft(
            ScenarioRuntime runtime,
            SessionScenarioSettings<LineSession, LineSessionSettings> worker,
            WorkerPoolSettings<LineSession> pool)
            implements PoolDraft {

        private ImmutablePoolDraft {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(pool, "pool");
        }

        @Override
        public PoolDraft withMaxSize(int maxSize) {
            return copy(pool.withMaxSize(maxSize));
        }

        @Override
        public PoolDraft withWarmupSize(int warmupSize) {
            return copy(pool.withWarmupSize(warmupSize));
        }

        @Override
        public PoolDraft withMinIdle(int minIdle) {
            return copy(pool.withMinIdle(minIdle));
        }

        @Override
        public PoolDraft withAcquireTimeout(Duration acquireTimeout) {
            return copy(pool.withAcquireTimeout(acquireTimeout));
        }

        @Override
        public PoolDraft withHookTimeout(Duration hookTimeout) {
            return copy(pool.withHookTimeout(hookTimeout));
        }

        @Override
        public PoolDraft withCloseTimeout(Duration closeTimeout) {
            return copy(pool.withCloseTimeout(closeTimeout));
        }

        @Override
        public PoolDraft withMaxRequestsPerWorker(int maxRequestsPerWorker) {
            return copy(pool.withMaxRequestsPerWorker(maxRequestsPerWorker));
        }

        @Override
        public PoolDraft withMaxWorkerAge(Duration maxWorkerAge) {
            return copy(pool.withMaxWorkerAge(maxWorkerAge));
        }

        @Override
        public PoolDraft withBackgroundReplenishment(boolean backgroundReplenishment) {
            return copy(pool.withBackgroundReplenishment(backgroundReplenishment));
        }

        @Override
        public PoolDraft withReset(Consumer<LineSession> resetHook) {
            return copy(pool.withResetHook(resetHook));
        }

        @Override
        public PoolDraft withHealthCheck(Predicate<LineSession> healthCheck) {
            return copy(pool.withHealthCheck(healthCheck));
        }

        @Override
        public PooledLineSession open() {
            return runtime.openPooledLineSession(worker, pool.validateForOpen());
        }

        private PoolDraft copy(WorkerPoolSettings<LineSession> updated) {
            return new ImmutablePoolDraft(runtime, worker, updated);
        }
    }
}

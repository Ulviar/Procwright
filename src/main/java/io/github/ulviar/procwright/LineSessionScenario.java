/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
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
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Namespace for immutable line-session and line-pool drafts. */
public final class LineSessionScenario {

    private LineSessionScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return LineSessionDrafts.create(runtime);
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
     * protocol pools, at most 256 factory-admitted workers may collectively hold permits while starting, live, or
     * retiring. A worker permit is acquired before the worker factory is invoked and retained until session close,
     * terminal observation, and physical output cleanup all complete; a non-cooperative retirement therefore continues
     * to consume it. Saturated warmup fails with
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
         * @throws PooledLineSessionException with reason {@link PooledLineSessionException.Reason#STARTUP_FAILED} when
         *     synchronous warmup fails
         */
        PooledLineSession open();
    }
}

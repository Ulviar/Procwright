/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Namespace for immutable typed protocol-session and protocol-pool drafts. */
public final class ProtocolSessionScenario {

    private ProtocolSessionScenario() {}

    static <I extends Object, O extends Object> Draft<I, O> draft(
            ScenarioRuntime runtime, Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        return ProtocolSessionDrafts.create(runtime, adapterFactory);
    }

    /**
     * Persistent write-only configuration backed by a fresh-adapter factory.
     *
     * <p>Each call to {@link #open()} and each pool worker obtains one adapter before its process is launched. The draft
     * and all of its immutable branches are safe to reuse and open concurrently. Procwright does not serialize factory
     * calls: the factory must be safe for concurrent invocation and must return a fresh, non-null adapter every time.
     * One session serializes calls to its fresh adapter, but different adapters can run concurrently.
     *
     * <p>The Draft also retains its PTY provider, readiness probe, diagnostic listener, and transcript sink. Concurrent
     * opens and workers created through {@link #pooled()} can invoke each supplied instance concurrently. Retained
     * instances must be thread-safe; otherwise, use separate Draft branches with separate callback or provider
     * instances.
     *
     * @param <I> request type
     * @param <O> response type
     */
    public interface Draft<I extends Object, O extends Object> {
        /**
         * Appends one process argument.
         *
         * @param argument argument to append
         * @return updated draft
         */
        Draft<I, O> withArg(String argument);

        /**
         * Appends a copied argument array.
         *
         * @param arguments copied arguments to append
         * @return updated draft
         */
        Draft<I, O> withArgs(String... arguments);

        /**
         * Appends a copied argument collection.
         *
         * @param arguments copied arguments to append
         * @return updated draft
         */
        Draft<I, O> withArgs(Collection<String> arguments);

        /**
         * Sets the process working directory.
         *
         * @param workingDirectory process working directory
         * @return updated draft
         */
        Draft<I, O> withWorkingDirectory(Path workingDirectory);

        /**
         * Adds or replaces one child environment variable.
         *
         * @param name variable name
         * @param value variable value
         * @return updated draft
         */
        Draft<I, O> withEnvironment(String name, String value);

        /**
         * Selects parent environment inheritance.
         *
         * @return updated draft
         */
        Draft<I, O> withInheritedEnvironment();

        /**
         * Selects an initially empty child environment.
         *
         * @return updated draft
         */
        Draft<I, O> withCleanEnvironment();

        /**
         * Sets process shutdown escalation.
         *
         * @param shutdownPolicy process shutdown policy
         * @return updated draft
         */
        Draft<I, O> withShutdown(ShutdownPolicy shutdownPolicy);

        /**
         * Sets the caller-visible idle timeout.
         *
         * @param idleTimeout non-negative caller-visible idle timeout
         * @return updated draft
         */
        Draft<I, O> withIdleTimeout(Duration idleTimeout);

        /**
         * Selects the terminal requirement.
         *
         * @param terminalPolicy terminal requirement
         * @return updated draft
         */
        Draft<I, O> withTerminal(TerminalPolicy terminalPolicy);

        /**
         * Sets the provider used for terminal launches.
         *
         * @param ptyProvider provider used for terminal launches
         * @return updated draft
         */
        Draft<I, O> withPtyProvider(PtyProvider ptyProvider);

        /**
         * Sets requested terminal dimensions.
         *
         * @param terminalSize requested terminal dimensions
         * @return updated draft
         */
        Draft<I, O> withTerminalSize(TerminalSize terminalSize);

        /**
         * Sets a probe completed before open returns.
         *
         * <p>The Draft retains the probe. Each direct session and each pooled worker invokes it once before becoming
         * available; worker starts can overlap and invoke the same probe instance concurrently.
         *
         * @param readinessProbe probe completed before open returns
         * @return updated draft
         */
        Draft<I, O> withReadiness(Consumer<ProtocolSession<I, O>> readinessProbe);

        /**
         * Sets the readiness timeout.
         *
         * @param readinessTimeout positive readiness timeout
         * @return updated draft
         */
        Draft<I, O> withReadinessTimeout(Duration readinessTimeout);

        /**
         * Sets the timeout for each request.
         *
         * @param requestTimeout positive timeout for each request
         * @return updated draft
         */
        Draft<I, O> withRequestTimeout(Duration requestTimeout);

        /**
         * Sets the retained transcript limit.
         *
         * @param transcriptLimit positive retained transcript limit
         * @return updated draft
         */
        Draft<I, O> withTranscriptLimit(int transcriptLimit);

        /**
         * Sets the encoded request byte limit.
         *
         * @param maxRequestBytes positive encoded request limit
         * @return updated draft
         */
        Draft<I, O> withMaxRequestBytes(int maxRequestBytes);

        /**
         * Sets the request character limit.
         *
         * @param maxRequestChars positive request character limit
         * @return updated draft
         */
        Draft<I, O> withMaxRequestChars(int maxRequestChars);

        /**
         * Sets the byte limit shared by reads of one response and the pending-output capacity of each stream.
         *
         * <p>Stdout that exceeds the pending capacity fails the session immediately. Excess unread stderr remains
         * nonfatal until the adapter reads it. Both paths use {@code RESPONSE_TOO_LARGE}.
         *
         * @param maxResponseBytes positive response and pending-output byte limit
         * @return updated draft
         */
        Draft<I, O> withMaxResponseBytes(int maxResponseBytes);

        /**
         * Sets the decoded response character limit.
         *
         * @param maxResponseChars positive decoded response character limit
         * @return updated draft
         */
        Draft<I, O> withMaxResponseChars(int maxResponseChars);

        /**
         * Selects forgiving protocol text decoding.
         *
         * @param charset forgiving protocol text charset
         * @return updated draft
         */
        Draft<I, O> withCharset(Charset charset);

        /**
         * Sets protocol charset and malformed-input behavior.
         *
         * @param charsetPolicy protocol text charset and malformed-input policy
         * @return updated draft
         */
        Draft<I, O> withCharsetPolicy(CharsetPolicy charsetPolicy);

        /**
         * Observes lifecycle diagnostics.
         *
         * @param listener lifecycle diagnostic listener
         * @return updated draft
         */
        Draft<I, O> withDiagnosticListener(DiagnosticListener listener);

        /**
         * Receives bounded diagnostic transcript snapshots.
         *
         * @param transcriptSink bounded diagnostic transcript sink
         * @return updated draft
         */
        Draft<I, O> withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink);

        /**
         * Branches into unopened pool configuration.
         *
         * @return pool draft carrying this worker configuration and adapter factory
         */
        PoolDraft<I, O> pooled();

        /**
         * Opens one protocol worker.
         *
         * @return newly opened protocol session
         */
        ProtocolSession<I, O> open();
    }

    /**
     * Persistent write-only configuration for opening pools of typed protocol workers.
     *
     * <p>The PoolDraft retains the adapter factory and worker callbacks carried by {@link Draft#pooled()}, plus its
     * health and reset callbacks. One worker runs health, request, and reset work without overlap, and its adapter calls
     * are serialized. Different workers or pools opened from this PoolDraft can invoke the same factory and retained
     * callback instances concurrently. The factory and retained callbacks must be thread-safe; otherwise, use separate
     * Draft or PoolDraft branches with separate instances. The factory must still return a fresh adapter for every
     * worker.
     *
     * <p>A pool's configured maximum accepts values from 1 through 256 and defaults to 1. It counts every starting, idle,
     * leased, and retiring worker in that pool. Separate pools and directly opened sessions do not share a worker quota;
     * applications control their aggregate process count through the pools and sessions they create.
     *
     * @param <I> request type
     * @param <O> response type
     */
    public interface PoolDraft<I extends Object, O extends Object> {
        /**
         * Sets the maximum number of occupied worker slots in this pool.
         *
         * @param maxSize per-pool worker limit from 1 through 256
         * @return updated pool draft
         */
        PoolDraft<I, O> withMaxSize(int maxSize);

        /**
         * Sets synchronous warmup capacity.
         *
         * @param warmupSize workers opened synchronously during pool open
         * @return updated pool draft
         */
        PoolDraft<I, O> withWarmupSize(int warmupSize);

        /**
         * Sets the maintained idle-worker floor.
         *
         * <p>Zero disables background replenishment. A positive value starts establishing the idle floor
         * asynchronously when the pool opens and restores it after workers are acquired or retired, without exceeding
         * the maximum pool size.
         *
         * @param minIdle idle workers maintained in the background
         * @return updated pool draft
         */
        PoolDraft<I, O> withMinIdle(int minIdle);

        /**
         * Sets the worker acquisition timeout.
         *
         * @param acquireTimeout positive worker acquisition timeout
         * @return updated pool draft
         */
        PoolDraft<I, O> withAcquireTimeout(Duration acquireTimeout);

        /**
         * Sets the reset and health-hook timeout.
         *
         * @param hookTimeout positive reset and health-hook timeout
         * @return updated pool draft
         */
        PoolDraft<I, O> withHookTimeout(Duration hookTimeout);

        /**
         * Sets how long {@link PooledProtocolSession#close()} waits for active requests and worker shutdown.
         *
         * <p>The default is 15 seconds.
         *
         * @param closeTimeout positive close timeout
         * @return updated pool draft
         */
        PoolDraft<I, O> withCloseTimeout(Duration closeTimeout);

        /**
         * Sets the request-count retirement threshold.
         *
         * @param maxRequestsPerWorker positive request retirement limit
         * @return updated pool draft
         */
        PoolDraft<I, O> withMaxRequestsPerWorker(int maxRequestsPerWorker);

        /**
         * Sets the worker age retirement threshold.
         *
         * @param maxWorkerAge non-negative worker age limit; zero disables it
         * @return updated pool draft
         */
        PoolDraft<I, O> withMaxWorkerAge(Duration maxWorkerAge);

        /**
         * Sets the hook run before a worker returns to idle.
         *
         * <p>The PoolDraft retains the hook. It does not overlap with request or health work for the same worker, but it
         * can overlap with hook or request work on other workers or pools.
         *
         * @param resetHook hook run before a worker returns to idle
         * @return updated pool draft
         */
        PoolDraft<I, O> withReset(Consumer<ProtocolSession<I, O>> resetHook);

        /**
         * Sets the predicate required before worker reuse.
         *
         * <p>The PoolDraft retains the predicate. It runs before a worker serves a request and does not overlap with
         * request or reset work for that worker, but it can overlap with hook or request work on other workers or pools.
         *
         * @param healthCheck predicate required before worker reuse
         * @return updated pool draft
         */
        PoolDraft<I, O> withHealthCheck(Predicate<ProtocolSession<I, O>> healthCheck);

        /**
         * Opens a new typed worker pool.
         *
         * @return newly opened typed worker pool
         * @throws PooledSessionException with reason
         *     {@link PooledSessionException.Reason#STARTUP_FAILED} when synchronous warmup fails
         */
        PooledProtocolSession<I, O> open();
    }
}

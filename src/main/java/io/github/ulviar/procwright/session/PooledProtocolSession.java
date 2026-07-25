/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultPooledProtocolSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Pool of reusable typed protocol-session workers.
 *
 * <p>The configured maximum is a per-pool bound from 1 through 256; it does not reserve process-wide capacity. Across
 * all line and protocol pools, at most 256 workers may collectively hold admission while starting, live, or retiring.
 * Admission is acquired before the worker factory and retained until physical retirement completes, including a
 * non-cooperative close.
 *
 * <p>Worker saturation during warmup fails pool opening with
 * {@link PooledProtocolSessionException.Reason#STARTUP_FAILED}; saturation during demand acquisition fails with
 * {@link PooledProtocolSessionException.Reason#ACQUIRE_TIMEOUT}. Capacity released by one pool has no specified
 * recipient or inter-pool ordering.
 *
 * @param <I> request type
 * @param <O> response type
 */
public sealed interface PooledProtocolSession<I extends Object, O extends Object> extends AutoCloseable
        permits DefaultPooledProtocolSession {

    /**
     * Sends one pooled request using the worker protocol-session default timeout.
     *
     * @param request request value
     * @return decoded response
     */
    O request(I request);

    /**
     * Sends one pooled request using an explicit request timeout.
     *
     * @param request request value
     * @param timeout request timeout
     * @return decoded response
     */
    O request(I request, Duration timeout);

    /**
     * Returns a current pool metrics snapshot.
     *
     * @return metrics snapshot
     */
    PooledProtocolSessionMetrics metrics();

    /**
     * Atomically starts closing the pool and returns a future for complete worker drain.
     *
     * <p>Idle workers close immediately. A healthy active request is allowed to finish, then its worker closes. The
     * returned future completes exceptionally with reason
     * {@link PooledProtocolSessionException.Reason#WORKER_FAILED} when ordinary worker cleanup fails. A single cleanup
     * {@link Error} is preserved by identity; multiple failures with an {@code Error} primary produce an {@code Error}
     * aggregate whose cause is that primary. Cancelling or completing the returned future does not cancel or alter
     * internal cleanup. Repeated calls return independent views of the same terminal cleanup. Completion actions never
     * run while the pool state is locked.
     *
     * @return cancellation-isolated close completion view
     */
    CompletableFuture<Void> closeAsync();

    /**
     * Closes and drains the pool within the timeout configured by
     * {@link io.github.ulviar.procwright.ProtocolSessionScenario.PoolDraft#withCloseTimeout(Duration)}.
     *
     * <p>Idle workers close immediately. A healthy active request is allowed to finish, then its worker closes. A drain
     * timeout includes close initiation and future lookup. It does not cancel cleanup; {@link #closeAsync()} can observe
     * eventual completion. This method is safe for try-with-resources.
     *
     * @throws PooledProtocolSessionException with reason
     *     {@link PooledProtocolSessionException.Reason#DRAIN_TIMEOUT} when the configured close timeout elapses
     * @throws PooledProtocolSessionException with reason {@link PooledProtocolSessionException.Reason#INTERRUPTED} when
     *     the waiting thread is interrupted
     * @throws PooledProtocolSessionException with reason {@link PooledProtocolSessionException.Reason#WORKER_FAILED}
     *     when worker cleanup fails
     * @throws Error when worker cleanup observes an {@code Error}; multiple cleanup failures may be represented by an
     *     {@code Error} aggregate
     */
    @Override
    void close();
}

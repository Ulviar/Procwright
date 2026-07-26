/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultPooledProtocolSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Pool of reusable typed protocol-session workers.
 *
 * <p>The configured maximum belongs to this pool, accepts values from 1 through 256, and defaults to 1. Starting, idle,
 * leased, and retiring workers all occupy this pool's slots. Separate pools and directly opened sessions do not share a
 * worker quota; applications control their aggregate process count through the pools and sessions they create.
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
    PooledSessionMetrics metrics();

    /**
     * Atomically starts closing the pool and returns a future for logical worker drain.
     *
     * <p>Idle workers close immediately. A healthy active request is allowed to finish, then its worker closes. Drain
     * completes after every logical pool slot is released and each accepted worker's process outcome and logical output
     * processing have settled. It does not wait for a potentially blocking physical close of process streams; a later
     * physical-close failure cannot change the result.
     *
     * <p>A worker factory still running when close begins is interrupted and its pool slot is released. If the factory
     * ignores interruption and later returns a worker, Procwright retires that worker asynchronously. That late worker
     * is not current pool state, and its retirement does not delay or rewrite an already completed close future.
     *
     * <p>The future completes exceptionally with reason
     * {@link PooledSessionException.Reason#WORKER_FAILED} when logical worker cleanup fails; a fatal cleanup failure may
     * propagate as an {@link Error}. The shape of secondary failure details is not an API contract. Cancelling or
     * completing the returned future does not cancel or alter internal cleanup. Repeated calls return independent views
     * of the same terminal cleanup. Completion actions never run while the pool state is locked.
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
     * eventual logical completion. A successful return has the same physical-close boundary documented by
     * {@link #closeAsync()}, including its treatment of a factory that ignores interruption. This method is safe for
     * try-with-resources.
     *
     * @throws PooledSessionException with reason
     *     {@link PooledSessionException.Reason#DRAIN_TIMEOUT} when the configured close timeout elapses
     * @throws PooledSessionException with reason {@link PooledSessionException.Reason#INTERRUPTED} when
     *     the waiting thread is interrupted
     * @throws PooledSessionException with reason {@link PooledSessionException.Reason#WORKER_FAILED}
     *     when worker cleanup fails
     * @throws Error when a fatal worker cleanup failure becomes the terminal outcome
     */
    @Override
    void close();
}

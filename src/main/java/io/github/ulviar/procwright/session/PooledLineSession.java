/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultPooledLineSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Pool of reusable line-oriented workers.
 *
 * <p>The pool reuses {@link LineSession} workers. It does not launch processes directly and does not expose worker
 * leases; returning a worker to the pool is owned by the pooled request lifecycle.
 *
 * <p>Line validation and bounded encoding complete before a worker is leased. Once a worker is leased, every failed
 * pooled request retires that worker, including a pre-write failure that could leave a directly owned line session open.
 *
 * <p>The configured maximum belongs to this pool, accepts values from 1 through 256, and defaults to 1. Starting, idle,
 * leased, and retiring workers all occupy this pool's slots. Separate pools and directly opened sessions do not share a
 * worker quota; applications control their aggregate process count through the pools and sessions they create.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive
 * pooled line sessions from {@code CommandService}.
 */
public sealed interface PooledLineSession extends AutoCloseable permits DefaultPooledLineSession {

    /**
     * Sends one pooled request using the worker line-session default timeout.
     *
     * <p>Failure and worker-retirement handling follows the class contract.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     */
    LineResponse request(String line);

    /**
     * Sends one pooled request using an explicit request timeout.
     *
     * <p>Failure and worker-retirement handling follows the class contract.
     *
     * @param line request line without the terminating line feed
     * @param timeout request timeout
     * @return decoded response
     */
    LineResponse request(String line, Duration timeout);

    /**
     * Returns a current pool metrics snapshot.
     *
     * @return metrics snapshot
     */
    PooledLineSessionMetrics metrics();

    /**
     * Atomically starts closing the pool and returns a future for complete worker drain.
     *
     * <p>Idle workers close immediately. A healthy active request is allowed to finish, then its worker closes. The
     * returned future completes exceptionally with reason {@link PooledLineSessionException.Reason#WORKER_FAILED} when
     * ordinary worker cleanup fails. A single cleanup {@link Error} is preserved by identity; multiple failures with an
     * {@code Error} primary produce an {@code Error} aggregate whose cause is that primary. Cancelling or completing the
     * returned future does not cancel or alter internal cleanup. Repeated calls return independent views of the same
     * terminal cleanup. Completion actions never run while the pool state is locked.
     *
     * @return cancellation-isolated close completion view
     */
    CompletableFuture<Void> closeAsync();

    /**
     * Closes and drains the pool within the timeout configured by
     * {@link io.github.ulviar.procwright.LineSessionScenario.PoolDraft#withCloseTimeout(Duration)}.
     *
     * <p>Idle workers close immediately. A healthy active request is allowed to finish, then its worker closes. A drain
     * timeout includes close initiation and future lookup. It does not cancel cleanup; {@link #closeAsync()} can observe
     * eventual completion. This method is safe for try-with-resources.
     *
     * @throws PooledLineSessionException with reason {@link PooledLineSessionException.Reason#DRAIN_TIMEOUT} when the
     *     configured close timeout elapses
     * @throws PooledLineSessionException with reason {@link PooledLineSessionException.Reason#INTERRUPTED} when the
     *     waiting thread is interrupted
     * @throws PooledLineSessionException with reason {@link PooledLineSessionException.Reason#WORKER_FAILED} when worker
     *     cleanup fails
     * @throws Error when worker cleanup observes an {@code Error}; multiple cleanup failures may be represented by an
     *     {@code Error} aggregate
     */
    @Override
    void close();
}

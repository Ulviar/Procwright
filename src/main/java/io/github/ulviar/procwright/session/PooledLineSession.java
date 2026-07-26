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
    PooledSessionMetrics metrics();

    /**
     * Atomically starts closing the pool and returns a future for logical worker drain.
     *
     * <p>Idle workers close immediately. A healthy active request is allowed to finish, then its worker closes. Drain
     * completes after every pool slot is released and each worker's process outcome and logical output processing have
     * settled. It does not wait for a potentially blocking physical close of process streams; a later physical-close
     * failure cannot change the result.
     *
     * <p>The future completes exceptionally with reason {@link PooledSessionException.Reason#WORKER_FAILED} when
     * logical worker cleanup fails; a fatal cleanup failure may propagate as an {@link Error}. The shape of secondary
     * failure details is not an API contract. Cancelling or completing the returned future does not cancel or alter
     * internal cleanup. Repeated calls return independent views of the same terminal cleanup. Completion actions never
     * run while the pool state is locked.
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
     * eventual logical completion. A successful return has the same physical-close boundary documented by
     * {@link #closeAsync()}. This method is safe for try-with-resources.
     *
     * @throws PooledSessionException with reason {@link PooledSessionException.Reason#DRAIN_TIMEOUT} when the
     *     configured close timeout elapses
     * @throws PooledSessionException with reason {@link PooledSessionException.Reason#INTERRUPTED} when the
     *     waiting thread is interrupted
     * @throws PooledSessionException with reason {@link PooledSessionException.Reason#WORKER_FAILED} when worker
     *     cleanup fails
     * @throws Error when a fatal worker cleanup failure becomes the terminal outcome
     */
    @Override
    void close();
}

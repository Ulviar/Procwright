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
 * <p>The configured maximum is a per-pool bound from 1 through 256; it does not reserve process-wide capacity. Across
 * all line and protocol pools, at most 256 workers may collectively hold admission while starting, live, or retiring.
 * Admission is acquired before the worker factory and retained until physical retirement completes, including a
 * non-cooperative close.
 *
 * <p>Worker saturation during warmup fails pool opening with
 * {@link PooledLineSessionException.Reason#STARTUP_FAILED}; saturation during demand acquisition fails with
 * {@link PooledLineSessionException.Reason#ACQUIRE_TIMEOUT}. Capacity released by one pool has no specified recipient
 * or inter-pool ordering.
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
     * worker cleanup fails. Cancelling or completing the returned future does not cancel or alter internal cleanup.
     * Repeated calls return independent views of the same terminal cleanup. One of 256 process-wide terminal slots is
     * reserved during {@code open()}, so an accepted pool does not wait for terminal admission here. A blocking
     * synchronous continuation attached before completion retains only this pool's slot: it cannot delay another
     * accepted pool's close, but new pool openings fail with
     * {@link PooledLineSessionException.Reason#STARTUP_FAILED} while all slots remain occupied.
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
     */
    @Override
    void close();
}

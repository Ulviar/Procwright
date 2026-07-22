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
 * <p>The configured maximum is a per-pool bound from 1 through 256; it does not reserve process-wide capacity. Two
 * independent process-wide limits apply across all line and protocol pools:
 *
 * <ul>
 *   <li>At most 256 workers may collectively hold admission while starting, live, or retiring. Admission is acquired
 *       before the worker factory and retained until physical retirement completes, including a non-cooperative close.
 *   <li>At most 256 pool-completion owners and their pools may be retained concurrently. This admission is acquired
 *       during pool opening, before completion-owner startup and warmup, and retained through terminal pool completion.
 * </ul>
 *
 * <p>Pool-owner or warmup saturation fails pool opening with
 * {@link PooledLineSessionException.Reason#STARTUP_FAILED}; worker saturation during demand acquisition fails with
 * {@link PooledLineSessionException.Reason#ACQUIRE_TIMEOUT}. Capacity released by one pool has no specified recipient or
 * inter-pool ordering.
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
     * Repeated calls return independent views of the same terminal cleanup. When internal terminal completion propagates
     * to a defensive view that is still incomplete, a synchronous continuation triggered by that propagation executes
     * on this pool's pre-admitted completion owner. Until it returns, it retains this pool's admission but cannot occupy
     * another pool's completion owner. Completing or cancelling a defensive view from caller code, and synchronous
     * continuations thereby triggered, run on that caller and never retain completion-owner admission. After drain has
     * completed, {@code closeAsync()} returns an already completed defensive view; a synchronous continuation then
     * attached to that view likewise runs on the attaching caller without retaining owner admission. No executor is
     * otherwise selected or guaranteed by this contract.
     *
     * @return cancellation-isolated close completion view
     */
    CompletableFuture<Void> closeAsync();

    /**
     * Closes and drains the pool within the timeout configured by
     * {@link io.github.ulviar.procwright.LineSessionScenario.PoolDraft#withCloseTimeout(Duration)}.
     *
     * <p>Idle workers close immediately. A healthy active request is allowed to finish, then its worker closes. A drain
     * timeout does not cancel cleanup; {@link #closeAsync()} can observe eventual completion. This method is safe for
     * try-with-resources.
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

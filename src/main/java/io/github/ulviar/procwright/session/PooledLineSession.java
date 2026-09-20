/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultPooledLineSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Pool of reusable line-oriented workers.
 *
 * <p>The pool reuses {@link LineSession} workers without exposing leases. Each request returns or retires its worker
 * automatically.
 *
 * <p>Line validation and encoded-size checks complete before a worker is leased; the encoded byte array is created
 * after acquisition. Failure during this local preparation returns an acquired worker without resetting it or consuming
 * its request limit, subject to normal age and close rules. Once the worker's request starts, failure retires that
 * worker, including a pre-write failure that could leave a directly owned line session open.
 *
 * <p>Concurrent requests lease different available workers. Requests have no affinity to a particular process; use
 * a directly opened session for a stateful conversation. The pool owns every worker and returns or retires it after
 * each request. Close the pool with try-with-resources when its work is complete.
 *
 * <p>Procwright does not retry a failed request automatically: the process may already have performed its effects.
 * Later requests may start a replacement for a retired worker.
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
     * <p>Request preparation, encoding, and the worker exchange share the request timeout. Waiting for a worker and
     * checking its health use the separate acquisition timeout; that wait does not spend the request budget.
     * The caller waits for reset completion or its separate hook timeout. An ordinary reset failure or timeout
     * retires the worker but preserves the successful response; a fatal reset {@link Error} propagates. Shutdown
     * after failure can add cleanup time. Consequently the request timeout is not a bound on this whole method call.
     *
     * @param line request text containing neither CR nor LF; an empty line is allowed
     * @return decoded response
     * @throws IllegalArgumentException if the line contains CR or LF
     * @throws LineSessionException if request preparation or the worker exchange fails
     * @throws PooledSessionException if the pool is closed, acquisition or worker startup fails, or a health hook fails
     */
    LineResponse request(String line);

    /**
     * Sends one pooled request using an explicit request timeout.
     *
     * <p>Acquisition, reset, and failure handling follow {@link #request(String)}.
     *
     * @param line request text containing neither CR nor LF; an empty line is allowed
     * @param timeout positive timeout for request work, excluding acquisition and reset
     * @return decoded response
     * @throws IllegalArgumentException if the line contains CR or LF or the timeout is zero or negative
     * @throws LineSessionException if request preparation or the worker exchange fails
     * @throws PooledSessionException if the pool is closed, acquisition or worker startup fails, or a health hook fails
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
     * completes after every logical pool slot is released and each accepted worker's process outcome and logical output
     * processing have settled. It does not wait for a potentially blocking physical close of process streams; a later
     * physical-close failure cannot change the result.
     *
     * <p>A worker factory still running when close begins is interrupted and its pool slot is released. Interruption
     * of its readiness wait is normal cancellation and is not reported as an unhandled failure when cleanup succeeds.
     * If the factory
     * ignores interruption and later returns a worker, Procwright retires that worker asynchronously. That late worker
     * is not current pool state, and its retirement does not delay or rewrite an already completed close future.
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
     * {@link #closeAsync()}, including its treatment of a factory that ignores interruption. This method is safe for
     * try-with-resources.
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Stable reason a pooled worker was retired, reported by {@link PooledSessionMetrics#retireReasons()}.
 *
 * <p>Retirement replaces a worker, not necessarily the pool. Age and request-count policies can retire otherwise healthy
 * workers; {@link #RESET_FAILED} also need not make the completed request fail.
 */
public enum PooledWorkerRetireReason {
    /** Pool was closed. */
    CLOSED,
    /** Worker reached its maximum age when reuse was checked; active requests are not preempted solely for age. */
    AGE,
    /** Worker served its maximum configured request count. */
    MAX_REQUESTS,
    /** Worker timed out while serving a request. */
    TIMEOUT,
    /** Worker startup completed after the caller's startup deadline. */
    STARTUP_TIMEOUT,
    /** Worker startup completed after its waiting caller was interrupted. */
    STARTUP_INTERRUPTED,
    /** Worker health predicate rejected it or its health check failed. */
    HEALTH_FAILED,
    /** Worker failed because protocol decoding failed. */
    DECODER_FAILED,
    /** Worker process exited or reached EOF. */
    PROCESS_EXITED,
    /** Worker failed for another runtime reason. */
    WORKER_FAILED,
    /** Worker reset failed after a response had already completed. */
    RESET_FAILED
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Stable reason a pooled worker was retired.
 */
public enum PooledWorkerRetireReason {
    /** Pool was closed. */
    CLOSED,
    /** Worker reached its maximum configured age. */
    AGE,
    /** Worker served its maximum configured request count. */
    MAX_REQUESTS,
    /** Worker timed out while serving a request. */
    TIMEOUT,
    /** Worker startup completed after the caller's startup deadline. */
    STARTUP_TIMEOUT,
    /** Worker startup completed after its waiting caller was interrupted. */
    STARTUP_INTERRUPTED,
    /** Worker health check failed. */
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

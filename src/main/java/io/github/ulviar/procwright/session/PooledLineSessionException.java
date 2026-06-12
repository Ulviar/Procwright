/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;

/**
 * Signals a pooled line-session failure outside the underlying line request itself.
 */
public final class PooledLineSessionException extends ProcwrightException {

    /** Failure reason. */
    private final Reason reason;

    /**
     * Creates a pooled line-session exception.
     *
     * @param reason failure reason
     * @param message failure message
     */
    public PooledLineSessionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates a pooled line-session exception with a cause.
     *
     * @param reason failure reason
     * @param message failure message
     * @param cause failure cause
     */
    public PooledLineSessionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the pooled scenario failure reason.
     *
     * @return failure reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Distinct pooled line-session failure reasons.
     */
    public enum Reason {
        /** No worker became available before the acquire deadline. */
        ACQUIRE_TIMEOUT,
        /** Pool is closed or closing. */
        CLOSED,
        /** Worker startup failed. */
        STARTUP_FAILED,
        /** Worker health or reset hook did not finish before its deadline. */
        HOOK_TIMEOUT,
        /** Current thread was interrupted while waiting for pool work. */
        INTERRUPTED,
        /** Worker lifecycle hook or request handling failed outside normal line-session errors. */
        WORKER_FAILED
    }
}

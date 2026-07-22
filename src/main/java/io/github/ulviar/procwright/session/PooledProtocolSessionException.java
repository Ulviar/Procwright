/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Signals a pooled protocol-session failure outside the underlying protocol request itself.
 */
@SuppressWarnings("serial")
public final class PooledProtocolSessionException extends ProcwrightException {

    /** Failure reason. */
    private final Reason reason;

    /**
     * Creates a pooled protocol-session exception.
     *
     * @param reason failure reason
     * @param message failure message
     */
    public PooledProtocolSessionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates a pooled protocol-session exception with a cause.
     *
     * @param reason failure reason
     * @param message failure message
     * @param cause failure cause, or {@code null} when unavailable
     */
    public PooledProtocolSessionException(Reason reason, String message, @Nullable Throwable cause) {
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
     * Stable pooled protocol-session failure reasons.
     */
    public enum Reason {
        /** No worker became available before the acquire deadline. */
        ACQUIRE_TIMEOUT,
        /** Pool is closed or closing. */
        CLOSED,
        /** Worker startup failed. */
        STARTUP_FAILED,
        /** Worker health hook did not finish before its deadline. */
        HOOK_TIMEOUT,
        /** Current thread was interrupted while waiting for pool work. */
        INTERRUPTED,
        /** Pool close did not drain every worker within its configured timeout. */
        DRAIN_TIMEOUT,
        /** Worker lifecycle hook or request handling failed outside normal protocol-session errors. */
        WORKER_FAILED
    }
}

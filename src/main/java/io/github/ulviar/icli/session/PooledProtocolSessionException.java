package io.github.ulviar.icli.session;

import java.util.Objects;

/**
 * Signals a pooled protocol-session failure outside the underlying protocol request itself.
 */
public final class PooledProtocolSessionException extends RuntimeException {

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
     * @param cause failure cause
     */
    public PooledProtocolSessionException(Reason reason, String message, Throwable cause) {
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
        /** Worker health or reset hook did not finish before its deadline. */
        HOOK_TIMEOUT,
        /** Current thread was interrupted while waiting for pool work. */
        INTERRUPTED,
        /** Worker lifecycle hook or request handling failed outside normal protocol-session errors. */
        WORKER_FAILED
    }
}

package com.github.ulviar.icli;

import java.util.Objects;

/**
 * Signals a pooled line-session failure outside the underlying line request itself.
 */
public final class PooledLineSessionException extends RuntimeException {

    /** Failure reason. */
    private final Reason reason;

    PooledLineSessionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    PooledLineSessionException(Reason reason, String message, Throwable cause) {
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
        /** Worker lifecycle hook or request handling failed outside normal line-session errors. */
        WORKER_FAILED
    }
}

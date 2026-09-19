/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Signals a pooled-session lifecycle failure outside the underlying request itself.
 *
 * <p>Worker request failures normally preserve {@link LineSessionException} or {@link ProtocolSessionException};
 * this type distinguishes acquisition, startup, hooks, and pool shutdown. Use {@link #reason()} rather than parsing
 * messages. A failure does not imply that a request is safe to retry or that the whole pool is closed.
 */
@SuppressWarnings("serial")
public final class PooledSessionException extends ProcwrightException {

    /** Structured lifecycle reason exposed by {@link #reason()}. */
    private final Reason reason;

    /**
     * Creates a pooled-session exception.
     *
     * @param reason failure reason
     * @param message failure message
     */
    public PooledSessionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates a pooled-session exception with a cause.
     *
     * @param reason failure reason
     * @param message failure message
     * @param cause failure cause, or {@code null} when unavailable
     */
    public PooledSessionException(Reason reason, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the pooled-session lifecycle failure reason.
     *
     * @return failure reason
     */
    public Reason reason() {
        return reason;
    }

    /** Stable pooled-session lifecycle failure reasons. */
    public enum Reason {
        /** No worker became available before the acquire deadline. */
        ACQUIRE_TIMEOUT,
        /** Pool is closed or closing. */
        CLOSED,
        /** Pool construction or worker startup failed. */
        STARTUP_FAILED,
        /** Worker health hook did not finish before its deadline. */
        HOOK_TIMEOUT,
        /** Current thread was interrupted while waiting for pool work. */
        INTERRUPTED,
        /** The close wait expired; cleanup continues and can be observed through the pool closeAsync method. */
        DRAIN_TIMEOUT,
        /** Worker lifecycle hook or request handling failed outside the underlying request's normal errors. */
        WORKER_FAILED
    }
}

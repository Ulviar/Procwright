/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Signals that the process could not be started, supervised, or captured.
 */
@SuppressWarnings("serial")
public final class CommandExecutionException extends ProcwrightException {

    /** Failure reason. */
    private final Reason reason;

    /** Completed diagnostic result when the failure occurred after process execution. */
    private final @Nullable CommandResult result;

    /**
     * Creates an execution exception with a message and cause.
     *
     * @param message failure message
     * @param cause failure cause, or {@code null} when unavailable
     */
    public CommandExecutionException(String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = Reason.RUNTIME_FAILURE;
        this.result = null;
    }

    /**
     * Creates an execution exception with a message.
     *
     * @param message failure message
     */
    public CommandExecutionException(String message) {
        super(message);
        this.reason = Reason.RUNTIME_FAILURE;
        this.result = null;
    }

    /**
     * Creates an execution exception with a typed reason and cause.
     *
     * <p>{@link Reason#DECODE_ERROR} is not accepted because decode failures require a completed result snapshot.
     *
     * @param reason failure reason
     * @param message failure message
     * @param cause failure cause, or {@code null} when unavailable
     * @throws IllegalArgumentException when {@code reason} is {@link Reason#DECODE_ERROR}
     */
    public CommandExecutionException(Reason reason, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = requireResultlessReason(reason);
        this.result = null;
    }

    /**
     * Creates an execution exception with a typed reason.
     *
     * <p>{@link Reason#DECODE_ERROR} is not accepted because decode failures require a completed result snapshot.
     *
     * @param reason failure reason
     * @param message failure message
     * @throws IllegalArgumentException when {@code reason} is {@link Reason#DECODE_ERROR}
     */
    public CommandExecutionException(Reason reason, String message) {
        super(message);
        this.reason = requireResultlessReason(reason);
        this.result = null;
    }

    /**
     * Creates a typed execution failure with a completed diagnostic result snapshot.
     *
     * <p>Only {@link Reason#DECODE_ERROR} is result-bearing: the process completed and bounded bytes are available,
     * but strict output decoding failed. The snapshot preserves those bytes, best-effort diagnostic text, stderr, exit
     * state, and truncation metadata. Raw bytes remain authoritative when a custom charset decoder itself is broken.
     *
     * @param reason failure reason
     * @param message failure message
     * @param cause failure cause, or {@code null} when unavailable
     * @param result completed diagnostic result snapshot
     */
    public CommandExecutionException(Reason reason, String message, @Nullable Throwable cause, CommandResult result) {
        super(message, cause);
        this.reason = requireResultBearingReason(reason);
        this.result = java.util.Objects.requireNonNull(result, "result");
    }

    /**
     * Returns the typed execution failure reason.
     *
     * @return failure reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the completed diagnostic result when the process reached a result-bearing failure stage.
     *
     * @return diagnostic result snapshot for {@link Reason#DECODE_ERROR}, otherwise empty
     */
    public Optional<CommandResult> result() {
        return Optional.ofNullable(result);
    }

    private static Reason requireResultBearingReason(Reason reason) {
        java.util.Objects.requireNonNull(reason, "reason");
        if (reason != Reason.DECODE_ERROR) {
            throw new IllegalArgumentException("Only DECODE_ERROR may carry a completed command result");
        }
        return reason;
    }

    private static Reason requireResultlessReason(Reason reason) {
        java.util.Objects.requireNonNull(reason, "reason");
        if (reason == Reason.DECODE_ERROR) {
            throw new IllegalArgumentException("DECODE_ERROR requires a completed command result");
        }
        return reason;
    }

    /**
     * Stable execution failure reasons.
     */
    public enum Reason {
        /** Process launch failed. */
        LAUNCH_FAILED,
        /** Captured output could not be decoded according to the selected charset policy. */
        DECODE_ERROR,
        /** Session readiness probe did not complete before its deadline. */
        READINESS_TIMEOUT,
        /** Session readiness probe failed. */
        READINESS_FAILED,
        /** The process could not be supervised, captured, or cleaned up normally. */
        RUNTIME_FAILURE
    }
}

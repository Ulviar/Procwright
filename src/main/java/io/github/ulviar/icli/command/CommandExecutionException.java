package io.github.ulviar.icli.command;

import io.github.ulviar.icli.IcliException;

/**
 * Signals that the process could not be started, supervised, or captured.
 */
public final class CommandExecutionException extends IcliException {

    /** Failure reason. */
    private final Reason reason;

    /**
     * Creates an execution exception with a message and cause.
     *
     * @param message failure message
     * @param cause failure cause
     */
    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.reason = Reason.RUNTIME_FAILURE;
    }

    /**
     * Creates an execution exception with a message.
     *
     * @param message failure message
     */
    public CommandExecutionException(String message) {
        super(message);
        this.reason = Reason.RUNTIME_FAILURE;
    }

    /**
     * Creates an execution exception with a typed reason and cause.
     *
     * @param reason failure reason
     * @param message failure message
     * @param cause failure cause
     */
    public CommandExecutionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates an execution exception with a typed reason.
     *
     * @param reason failure reason
     * @param message failure message
     */
    public CommandExecutionException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
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

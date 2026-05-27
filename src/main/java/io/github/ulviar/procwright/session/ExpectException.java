package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;

/**
 * Signals an expect automation failure.
 */
public final class ExpectException extends ProcwrightException {

    /** Failure reason. */
    private final Reason reason;

    /** Bounded transcript snapshot. */
    private final LineTranscript transcript;

    /**
     * Creates an expect exception.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     */
    public ExpectException(Reason reason, LineTranscript transcript, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    /**
     * Creates an expect exception with a cause.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     * @param cause failure cause
     */
    public ExpectException(Reason reason, LineTranscript transcript, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    /**
     * Returns the failure reason.
     *
     * @return failure reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the bounded transcript captured at failure time.
     *
     * @return transcript snapshot
     */
    public LineTranscript transcript() {
        return transcript;
    }

    /**
     * Distinct expect failure reasons.
     */
    public enum Reason {
        /** Expected output did not appear before the deadline. */
        TIMEOUT,
        /** Process stdout reached EOF before expected output appeared. */
        EOF,
        /** Expect helper was closed before the operation could complete. */
        CLOSED,
        /** Output could not be read, filtered, or written. */
        FAILURE
    }
}

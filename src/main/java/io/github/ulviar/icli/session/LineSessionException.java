package io.github.ulviar.icli.session;

import java.util.Objects;

/**
 * Signals a line-oriented request/response failure.
 */
public final class LineSessionException extends RuntimeException {

    /** Failure reason. */
    private final Reason reason;

    /** Bounded transcript snapshot. */
    private final LineTranscript transcript;

    /**
     * Creates a line-session exception.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     */
    public LineSessionException(Reason reason, LineTranscript transcript, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    /**
     * Creates a line-session exception with a cause.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     * @param cause failure cause
     */
    public LineSessionException(Reason reason, LineTranscript transcript, String message, Throwable cause) {
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
     * Distinct line-session failure reasons.
     */
    public enum Reason {
        /** Request did not produce a complete response before its deadline. */
        TIMEOUT,
        /** Process stdout reached EOF before a complete response was decoded. */
        EOF,
        /** Session was closed before the request could complete. */
        CLOSED,
        /** Process stdin could not accept the request. */
        BROKEN_PIPE,
        /** Output bytes could not be decoded according to the selected charset policy. */
        DECODE_ERROR,
        /** Response exceeded a configured size limit. */
        RESPONSE_TOO_LARGE,
        /** Stdout produced more pending response data than the session allows. */
        STDOUT_BACKLOG_OVERFLOW,
        /** Custom response decoder failed. */
        DECODER_FAILED,
        /** Output could not be read or another runtime path failed. */
        FAILURE
    }
}

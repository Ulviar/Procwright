/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Signals a line-oriented request/response failure with a stable reason and bounded transcript.
 *
 * <p>Use {@link #reason()} rather than parsing the message. The reason alone does not establish retryability:
 * {@link LineSession#request(String)} distinguishes failures before writing from terminal failures. A request may
 * have reached the child before failure, so replay can repeat its effects.
 */
@SuppressWarnings("serial")
public final class LineSessionException extends ProcwrightException {

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
     * @param cause failure cause, or {@code null} when unavailable
     */
    public LineSessionException(Reason reason, LineTranscript transcript, String message, @Nullable Throwable cause) {
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
        /** Request exceeded the configured byte or character limit. */
        REQUEST_TOO_LARGE,
        /** Request preparation, admission, writing, or response decoding exceeded the deadline. */
        TIMEOUT,
        /** Process stdout reached EOF before a complete response was decoded. */
        EOF,
        /** Session was closed before the request could complete. */
        CLOSED,
        /** Process stdin could not accept the request. */
        BROKEN_PIPE,
        /** Output bytes could not be decoded according to the selected charset policy. */
        DECODE_ERROR,
        /** Response or pending stdout exceeded a configured response limit. */
        RESPONSE_TOO_LARGE,
        /** Process exited before the request could be written. */
        PROCESS_EXITED,
        /** Custom response decoder failed. */
        DECODER_FAILED,
        /** I/O or another runtime path failed, including interruption; inspect the cause for detail. */
        FAILURE
    }
}

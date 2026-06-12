/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Signals a protocol request/response failure.
 */
public final class ProtocolSessionException extends ProcwrightException {

    /** Failure reason. */
    private final Reason reason;
    /** Bounded transcript snapshot. */
    private final ProtocolTranscript transcript;
    /** Process exit code when known. */
    private final OptionalInt exitCode;

    /**
     * Creates a protocol session exception.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     */
    public ProtocolSessionException(Reason reason, ProtocolTranscript transcript, String message) {
        this(reason, transcript, OptionalInt.empty(), message, null);
    }

    /**
     * Creates a protocol session exception with a cause.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     * @param cause failure cause
     */
    public ProtocolSessionException(Reason reason, ProtocolTranscript transcript, String message, Throwable cause) {
        this(reason, transcript, OptionalInt.empty(), message, cause);
    }

    /**
     * Creates a protocol session exception with process exit information.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param exitCode process exit code when known
     * @param message failure message
     * @param cause failure cause
     */
    public ProtocolSessionException(
            Reason reason, ProtocolTranscript transcript, OptionalInt exitCode, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
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
    public ProtocolTranscript transcript() {
        return transcript;
    }

    /**
     * Returns the process exit code if it is known at failure time.
     *
     * @return exit code when known
     */
    public OptionalInt exitCode() {
        return exitCode;
    }

    /**
     * Stable protocol failure reasons.
     */
    public enum Reason {
        /** Request did not complete before its deadline. */
        TIMEOUT,
        /** Session was closed before the request could complete. */
        CLOSED,
        /** Process output reached EOF before one complete response was decoded. */
        EOF,
        /** Process stdin could not accept the request. */
        BROKEN_PIPE,
        /** Output bytes could not be decoded according to the selected charset policy. */
        DECODE_ERROR,
        /** Request exceeded a configured size limit. */
        REQUEST_TOO_LARGE,
        /** Response exceeded a configured size limit. */
        RESPONSE_TOO_LARGE,
        /** One output stream produced more pending data than the session allows. */
        OUTPUT_BACKLOG_OVERFLOW,
        /** Protocol adapter failed while decoding a response. */
        PROTOCOL_DECODER_FAILED,
        /** Process exited while the session was in use. */
        PROCESS_EXITED,
        /** Another protocol runtime path failed. */
        FAILURE
    }
}

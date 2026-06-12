/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;

/**
 * Signals an integration protocol framing failure.
 */
public final class IntegrationProtocolException extends ProcwrightException {

    /** Protocol failure reason. */
    private final Reason reason;

    /**
     * Creates a protocol exception.
     *
     * @param reason failure reason
     * @param message error message
     */
    public IntegrationProtocolException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates a protocol exception with a cause.
     *
     * @param reason failure reason
     * @param message error message
     * @param cause failure cause
     */
    public IntegrationProtocolException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the protocol failure reason.
     *
     * @return failure reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Protocol failure reason.
     */
    public enum Reason {
        /** Header block is malformed. */
        BAD_HEADER,
        /** Frame payload cannot be represented by the selected framing rule. */
        BAD_FRAME,
        /** Required content length header is absent. */
        MISSING_LENGTH,
        /** Content length is invalid. */
        BAD_LENGTH,
        /** Frame body exceeds the configured limit. */
        OVERSIZED_FRAME,
        /** Input ended before one complete frame was read. */
        EOF,
        /** Stream I/O failed while reading or writing a frame. */
        IO,
        /** Frame body is not valid UTF-8 JSON text. */
        INVALID_ENCODING
    }
}

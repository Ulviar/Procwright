/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;

/**
 * Identifies an invalid frame or JSON value encountered by a built-in integration adapter.
 *
 * <p>Direct adapter calls throw this exception. When it is the selected failure of an exchange inside a protocol
 * session, it becomes the cause of {@link io.github.ulviar.procwright.session.ProtocolSessionException}; the outer reason identifies
 * the failing request or response stage. Inspect {@link #reason()} on this cause to distinguish malformed headers,
 * encoding errors, and invalid JSON. Core timeouts and byte-budget failures may instead produce a core protocol
 * exception directly; this type is not a common catch point for every failed exchange.
 *
 * <p>The reason is immutable. Messages are diagnostic text, not a machine-readable protocol, and this exception is
 * not a stable Java-serialization format.
 */
@SuppressWarnings("serial")
public final class IntegrationProtocolException extends ProcwrightException {

    /** Protocol failure reason. */
    private final Reason reason;

    /**
     * Creates a framing or JSON failure with no explicit cause.
     *
     * @param reason non-null failure classification
     * @param message diagnostic message
     * @throws NullPointerException if {@code reason} is {@code null}
     */
    public IntegrationProtocolException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates a framing or JSON failure that retains its underlying cause.
     *
     * @param reason non-null failure classification
     * @param message diagnostic message
     * @param cause underlying parser, encoding, or input failure
     * @throws NullPointerException if {@code reason} is {@code null}
     */
    public IntegrationProtocolException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the protocol failure reason.
     *
     * @return the non-null classification supplied at construction
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Stable classifications for adapter framing and JSON failures.
     *
     * <p>These reasons describe the adapter failure retained in the cause of a core protocol-session exception;
     * they do not replace the outer session reason or determine whether that session can be reused.
     */
    public enum Reason {
        /** Header block is malformed, exceeds 8192 bytes, or contains a duplicate Content-Length field. */
        BAD_HEADER,
        /** Request cannot be encoded as JSON or contains a forbidden byte for its delimiter framing. */
        BAD_FRAME,
        /** Required content length header is absent. */
        MISSING_LENGTH,
        /** Content-Length is not a non-negative decimal integer within the supported integer range. */
        BAD_LENGTH,
        /** Announced Content-Length body size exceeds the adapter's configured response-body limit. */
        OVERSIZED_FRAME,
        /** Stdout ended before the Content-Length header block or its complete body was read. */
        EOF,
        /** Frame body is not valid UTF-8 JSON text. */
        INVALID_ENCODING,
        /** JSON is malformed, has trailing values or duplicate keys, or exceeds the nesting limit. */
        MALFORMED_JSON
    }
}

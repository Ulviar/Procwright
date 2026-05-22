package com.github.ulviar.icli.session;

import java.util.Objects;

/**
 * Bounded transcript captured by protocol workflows.
 *
 * @param text retained transcript text
 * @param truncated whether older transcript content was discarded
 * @param malformed whether output contained bytes that were malformed for transcript decoding
 * @param redacted whether transcript content was intentionally redacted
 */
public record ProtocolTranscript(String text, boolean truncated, boolean malformed, boolean redacted) {

    /**
     * Creates a protocol transcript snapshot.
     *
     * @param text retained transcript text
     * @param truncated whether older transcript content was discarded
     * @param malformed whether output contained bytes that were malformed for transcript decoding
     * @param redacted whether transcript content was intentionally redacted
     */
    public ProtocolTranscript {
        Objects.requireNonNull(text, "text");
    }
}

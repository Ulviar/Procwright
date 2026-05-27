package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Bounded diagnostic output retained by a streaming scenario.
 *
 * @param text retained diagnostic text
 * @param truncated true when older diagnostic text was discarded
 */
public record StreamTranscript(String text, boolean truncated) {

    /**
     * Validates a stream transcript snapshot.
     *
     * @param text retained diagnostic text
     * @param truncated true when older diagnostic text was discarded
     */
    public StreamTranscript {
        Objects.requireNonNull(text, "text");
    }
}

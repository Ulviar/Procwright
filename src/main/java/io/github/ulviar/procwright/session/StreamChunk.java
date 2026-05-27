package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Text chunk received by a streaming listener.
 *
 * @param source output stream that produced the chunk
 * @param text decoded chunk text
 */
public record StreamChunk(StreamSource source, String text) {

    /**
     * Validates a streaming chunk.
     *
     * @param source output stream that produced the chunk
     * @param text decoded chunk text
     */
    public StreamChunk {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(text, "text");
    }
}

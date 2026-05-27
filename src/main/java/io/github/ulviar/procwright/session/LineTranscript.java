package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Bounded text transcript captured by line-oriented workflows.
 *
 * @param text retained transcript text
 * @param truncated whether older transcript content was discarded
 */
public record LineTranscript(String text, boolean truncated) {

    /**
     * Creates a line transcript snapshot.
     *
     * @param text retained transcript text
     * @param truncated whether older transcript content was discarded
     */
    public LineTranscript {
        Objects.requireNonNull(text, "text");
    }
}

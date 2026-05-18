package com.github.ulviar.icli.session;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Completed line-oriented request response.
 *
 * @param lines decoded response lines
 * @param transcript bounded transcript snapshot captured after decoding
 * @param elapsed elapsed request/response time
 */
public record LineResponse(List<String> lines, LineTranscript transcript, Duration elapsed) {

    /**
     * Creates a line response.
     */
    public LineResponse {
        lines = List.copyOf(lines);
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }

    /**
     * Returns response lines joined with line feeds.
     *
     * @return response text
     */
    public String text() {
        return String.join("\n", lines);
    }
}

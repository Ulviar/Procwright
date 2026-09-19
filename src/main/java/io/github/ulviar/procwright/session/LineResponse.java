/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of a completed line-session exchange.
 *
 * <p>The response lines are copied into an unmodifiable list. They are the decoder's returned values, which may omit
 * framing lines it consumed. The transcript is a diagnostic snapshot of the session, not just this response, and may
 * be truncated or contain sensitive output. Use {@link #lines()} or {@link #text()} for application data.
 *
 * @param lines decoded response lines
 * @param transcript bounded transcript snapshot captured after decoding
 * @param elapsed non-negative elapsed worker request time; pool acquisition and reset are excluded
 */
public record LineResponse(List<String> lines, LineTranscript transcript, Duration elapsed) {

    /**
     * Creates a line response.
     *
     * @param lines decoded response lines
     * @param transcript bounded transcript snapshot captured after decoding
     * @param elapsed non-negative elapsed worker request time; pool acquisition and reset are excluded
     * @throws IllegalArgumentException if elapsed is negative
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
     * Returns response lines joined with LF, with no added trailing separator; an empty response produces empty text.
     *
     * @return response text
     */
    public String text() {
        return String.join("\n", lines);
    }
}

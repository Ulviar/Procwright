/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Immutable decoded output fragment delivered to a {@link StreamListener}.
 *
 * <p>Chunk boundaries are arbitrary: one chunk can contain several lines or only part of a line, ANSI sequence, or
 * progress update. CR and other control characters are preserved. Do not treat each chunk as a complete line or append
 * an extra newline when forwarding it. The source identifies stdout or stderr; no ordering between those streams is
 * promised. The value may be retained after the listener returns.
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

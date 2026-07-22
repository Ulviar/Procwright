/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Receives streaming output chunks.
 *
 * <p>Within one stream session, listener calls are synchronous and serialized across stdout and stderr, one chunk at a
 * time. A slow listener therefore applies backpressure to that process output instead of causing unbounded in-memory
 * buffering.
 *
 * <p>A stream {@code Draft} retains the supplied listener instance. Concurrent opens of the same Draft can invoke that
 * instance concurrently from different sessions. A listener shared this way must be thread-safe; otherwise, use
 * separate Draft branches with separate listener instances.
 */
@FunctionalInterface
public interface StreamListener {

    /**
     * Returns a listener that ignores all chunks.
     *
     * @return no-op listener
     */
    static StreamListener noop() {
        return NoopStreamListener.INSTANCE;
    }

    /**
     * Handles one output chunk.
     *
     * @param chunk output chunk
     */
    void onChunk(StreamChunk chunk);
}

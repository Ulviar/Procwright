/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Receives streaming output chunks.
 *
 * <p>Within one stream session, listener calls are synchronous and serialized across stdout and stderr, one chunk at a
 * time. A slow listener therefore applies backpressure to that process output instead of causing unbounded in-memory
 * buffering. After natural process exit completes the session future, all listener calls have returned and no later
 * call can begin. Explicit close and timeout admit no further deliveries, but a delivery admitted immediately before
 * stopping may invoke or remain inside the listener after the session future completes.
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

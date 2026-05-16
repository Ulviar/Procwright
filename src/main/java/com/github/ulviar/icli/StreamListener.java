package com.github.ulviar.icli;

/**
 * Receives streaming output chunks.
 *
 * <p>Listeners are invoked synchronously, one chunk at a time. A slow listener therefore applies backpressure to
 * process output instead of causing unbounded in-memory buffering.
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

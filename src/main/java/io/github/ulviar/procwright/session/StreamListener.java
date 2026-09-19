/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Receives decoded streaming output fragments; see {@link StreamChunk} for chunk boundaries and stream ordering.
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
     * Handles one output chunk synchronously on a Procwright output-delivery thread.
     *
     * <p>Return promptly to allow output draining. An ordinary callback exception fails the stream with
     * {@link StreamException.Reason#LISTENER_FAILED}; a fatal {@link Error} remains unwrapped. No thread-local state or
     * fixed thread identity should be assumed across calls.
     *
     * @param chunk output chunk
     */
    void onChunk(StreamChunk chunk);
}

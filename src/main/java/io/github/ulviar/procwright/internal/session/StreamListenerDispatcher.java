/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.StreamChunk;
import io.github.ulviar.procwright.session.StreamListener;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes synchronous delivery to one stream listener. */
final class StreamListenerDispatcher {

    private final StreamListener listener;
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final AtomicBoolean stopped = new AtomicBoolean();

    StreamListenerDispatcher(StreamListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void deliver(StreamChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        deliveryLock.lock();
        try {
            if (stopped.get()) {
                return;
            }
            listener.onChunk(chunk);
        } finally {
            deliveryLock.unlock();
        }
    }

    void stop() {
        stopped.set(true);
    }
}

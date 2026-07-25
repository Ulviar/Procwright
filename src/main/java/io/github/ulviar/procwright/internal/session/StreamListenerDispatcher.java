/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.StreamChunk;
import io.github.ulviar.procwright.session.StreamListener;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Owns serialized, cancellable delivery to one stream listener. */
final class StreamListenerDispatcher {

    private final StreamListener listener;
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final BoundedTaskRunner.CancellationSignal cancellation = new BoundedTaskRunner.CancellationSignal();
    private final StreamListenerTaskOwner taskOwner = new StreamListenerTaskOwner();

    StreamListenerDispatcher(StreamListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void deliver(StreamChunk chunk, BooleanSupplier deliveryAllowed)
            throws InterruptedException, TimeoutException, ExecutionException {
        Objects.requireNonNull(chunk, "chunk");
        BooleanSupplier allowed = Objects.requireNonNull(deliveryAllowed, "deliveryAllowed");
        deliveryLock.lock();
        try {
            if (!allowed.getAsBoolean()) {
                return;
            }
            try {
                BoundedTaskRunner.runWithStarter(
                        BoundedTaskLimits.STREAM_LISTENERS,
                        "procwright-stream-listener-",
                        Long.MAX_VALUE,
                        cancellation,
                        taskOwner,
                        () -> {
                            listener.onChunk(chunk);
                            return null;
                        });
            } catch (BoundedTaskRunner.TaskCancelledException ignored) {
                // A selected terminal outcome no longer waits for listener delivery.
            }
        } finally {
            deliveryLock.unlock();
        }
    }

    void stop() {
        cancellation.cancel();
        taskOwner.close();
    }
}

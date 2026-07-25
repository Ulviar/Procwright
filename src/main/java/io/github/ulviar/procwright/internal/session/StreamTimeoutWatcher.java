/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Owns the lifetime and settlement of one stream-session timeout watcher.
 *
 * <p>Terminal publication can wait on {@link #stopAndAwait()} without depending on the session's
 * terminal state machine.
 */
final class StreamTimeoutWatcher {

    private final AtomicReference<Thread> watcher = new AtomicReference<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    void start(Duration timeout, BooleanSupplier terminalSelected, Runnable expiration) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(terminalSelected, "terminalSelected");
        Objects.requireNonNull(expiration, "expiration");
        if (timeout.isZero()) {
            stopped.complete(null);
            return;
        }

        Thread thread = Threading.unstarted("procwright-stream-timeout-", () -> {
            try {
                if (sleep(timeout)) {
                    expiration.run();
                }
            } finally {
                watcher.compareAndSet(Thread.currentThread(), null);
                stopped.complete(null);
            }
        });
        watcher.set(thread);
        try {
            thread.start();
        } catch (RuntimeException | Error startFailure) {
            watcher.compareAndSet(thread, null);
            stopped.complete(null);
            throw startFailure;
        }
        if (terminalSelected.getAsBoolean()) {
            stop();
        }
    }

    void stop() {
        Thread running = watcher.get();
        if (running != null) {
            running.interrupt();
        }
    }

    void stopAndAwait() {
        Thread running = watcher.get();
        if (running != null && running != Thread.currentThread()) {
            running.interrupt();
        }
        if (running != Thread.currentThread()) {
            stopped.join();
        }
    }

    CompletableFuture<Void> stopped() {
        return stopped.copy();
    }

    private static boolean sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(DurationSupport.saturatedNanos(duration));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Owns the interruptible timer thread for one stream-session timeout.
 */
final class StreamTimeoutWatcher {

    private final AtomicReference<Thread> watcher = new AtomicReference<>();

    void start(Duration timeout, BooleanSupplier terminalSelected, Runnable expiration) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(terminalSelected, "terminalSelected");
        Objects.requireNonNull(expiration, "expiration");
        if (timeout.isZero() || terminalSelected.getAsBoolean()) {
            return;
        }

        Thread thread = Threading.unstarted("procwright-stream-timeout-", () -> {
            try {
                if (sleep(timeout)) {
                    expiration.run();
                }
            } finally {
                watcher.compareAndSet(Thread.currentThread(), null);
            }
        });
        watcher.set(thread);
        try {
            thread.start();
        } catch (RuntimeException | Error startFailure) {
            watcher.compareAndSet(thread, null);
            throw startFailure;
        }
        if (terminalSelected.getAsBoolean()) {
            stop();
        }
    }

    void stop() {
        Thread running = watcher.get();
        if (running != null && running != Thread.currentThread()) {
            running.interrupt();
        }
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes session requests while preserving their absolute deadline. */
final class SerializedRequestGate {

    private final ReentrantLock lock = new ReentrantLock();
    private final Waiter waiter;

    SerializedRequestGate() {
        this(Waiter.timed());
    }

    SerializedRequestGate(Waiter waiter) {
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    boolean acquireUntil(long deadlineNanos) throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos > 0 && waiter.acquire(lock, remainingNanos);
    }

    void release() {
        lock.unlock();
    }

    @FunctionalInterface
    interface Waiter {

        boolean acquire(ReentrantLock lock, long remainingNanos) throws InterruptedException;

        static Waiter timed() {
            return (lock, remainingNanos) -> lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
        }
    }
}

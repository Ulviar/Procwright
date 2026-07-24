/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Owns bounded-task admission and retains capacity until its permit is physically released. */
final class BoundedTaskLimiter {

    private static final long CANCELLATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final int capacity;
    private final Semaphore permits;

    BoundedTaskLimiter(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        permits = new Semaphore(capacity, true);
    }

    BoundedTaskPermit acquire(long deadlineNanos) throws TimeoutException, InterruptedException {
        return acquire(deadlineNanos, System::nanoTime);
    }

    BoundedTaskPermit acquire(long deadlineNanos, LongSupplier nanoTime) throws TimeoutException, InterruptedException {
        long remainingNanos = deadlineNanos - nanoTime.getAsLong();
        if (remainingNanos <= 0 || !permits.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("timed operation capacity was not available before its deadline");
        }
        return new BoundedTaskPermit(permits);
    }

    BoundedTaskPermit acquire(long deadlineNanos, BoundedTaskRunner.CancellationSignal cancellation)
            throws TimeoutException, InterruptedException, BoundedTaskRunner.TaskCancelledException {
        return acquire(deadlineNanos, cancellation, System::nanoTime);
    }

    BoundedTaskPermit acquire(
            long deadlineNanos, BoundedTaskRunner.CancellationSignal cancellation, LongSupplier nanoTime)
            throws TimeoutException, InterruptedException, BoundedTaskRunner.TaskCancelledException {
        Objects.requireNonNull(cancellation, "cancellation");
        while (true) {
            cancellation.throwIfCancelled();
            long remainingNanos = deadlineNanos - nanoTime.getAsLong();
            if (remainingNanos <= 0) {
                throw new TimeoutException("timed operation capacity was not available before its deadline");
            }
            long waitNanos = Math.min(remainingNanos, CANCELLATION_POLL_NANOS);
            if (permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
                BoundedTaskPermit permit = new BoundedTaskPermit(permits);
                try {
                    cancellation.throwIfCancelled();
                    return permit;
                } catch (BoundedTaskRunner.TaskCancelledException cancelled) {
                    permit.close();
                    throw cancelled;
                }
            }
        }
    }

    BoundedTaskPermit tryAcquire() {
        return permits.tryAcquire() ? new BoundedTaskPermit(permits) : null;
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    int capacity() {
        return capacity;
    }
}

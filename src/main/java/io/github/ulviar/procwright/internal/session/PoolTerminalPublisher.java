/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns one pool's terminal publication thread and its process-wide capacity reservation. */
final class PoolTerminalPublisher {

    private static final String THREAD_PREFIX = "procwright-pool-terminal-";
    private static final Capacity SHARED = new Capacity(WorkerPoolSettings.MAX_SIZE);

    private final Capacity capacity;
    private final ArrayBlockingQueue<Runnable> action = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean assigned = new AtomicBoolean();

    private PoolTerminalPublisher(Capacity capacity, long sequence) {
        this.capacity = capacity;
        Thread owner = Threading.unstartedPlatformNonInheriting(THREAD_PREFIX + sequence, this::run);
        owner.start();
    }

    static Capacity sharedCapacity() {
        return SHARED;
    }

    void assign(Runnable terminalAction) {
        Objects.requireNonNull(terminalAction, "terminalAction");
        if (!assigned.compareAndSet(false, true)) {
            throw new IllegalStateException("pool terminal action is already assigned");
        }
        if (!action.offer(terminalAction)) {
            throw new IllegalStateException("pool terminal owner rejected its only action");
        }
    }

    private void run() {
        try {
            Runnable terminalAction = takeAction();
            Thread.interrupted();
            terminalAction.run();
        } finally {
            capacity.release();
        }
    }

    private Runnable takeAction() {
        while (true) {
            try {
                return action.take();
            } catch (InterruptedException ignored) {
                // Terminal publication is mandatory and the owner is not exposed.
            }
        }
    }

    static final class Capacity {

        private final Semaphore permits;
        private final AtomicLong sequence = new AtomicLong();

        Capacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("terminal publication capacity must be positive");
            }
            permits = new Semaphore(capacity, true);
        }

        PoolTerminalPublisher reserve() {
            if (!permits.tryAcquire()) {
                throw new RejectedExecutionException("pool terminal publication capacity is exhausted");
            }
            try {
                return new PoolTerminalPublisher(this, sequence.getAndIncrement());
            } catch (RuntimeException | Error failure) {
                release();
                throw failure;
            }
        }

        private void release() {
            permits.release();
        }
    }
}

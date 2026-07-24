/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns a hard bound for mandatory lifecycle-future publications without consuming physical-cleanup capacity.
 *
 * <p>Every permit has a fresh asynchronous owner running before the permit is returned. A publication can therefore
 * never fall back to its caller or a physical-cleanup thread. The owner remains accounted until the publication and
 * all synchronous dependents have returned. Saturation is rejected only while reserving an owner; an accepted
 * publication is never dropped or run inline.
 *
 * @hidden
 */
public final class BoundedLifecyclePublisher {

    public static final int SHARED_CAPACITY = BoundedCloseDispatcher.SHARED_MAX_OUTSTANDING_CAPACITY;

    private static final BoundedLifecyclePublisher SHARED = new BoundedLifecyclePublisher(SHARED_CAPACITY);

    private final int capacity;
    private final ThreadFactory ownerThreads;
    private final AtomicLong sequence = new AtomicLong();
    private final Object capacityLock = new Object();
    private int owners;

    public BoundedLifecyclePublisher(int capacity) {
        this.capacity = requirePositiveCapacity(capacity);
        ownerThreads = task ->
                Threading.unstarted("procwright-lifecycle-publication-" + sequence.getAndIncrement() + '-', task);
    }

    BoundedLifecyclePublisher(int capacity, ThreadFactory ownerThreads) {
        this.capacity = requirePositiveCapacity(capacity);
        this.ownerThreads = Objects.requireNonNull(ownerThreads, "ownerThreads");
    }

    public static BoundedLifecyclePublisher shared() {
        return SHARED;
    }

    public Reservation reserve(int permits) {
        if (permits <= 0 || permits > capacity) {
            throw new IllegalArgumentException("permits must be between 1 and " + capacity);
        }
        claimCapacity(permits);

        Owner[] reserved = null;
        int constructed = 0;
        try {
            reserved = new BoundedLifecyclePublisher.Owner[permits];
            while (constructed < permits) {
                Owner owner = new Owner();
                reserved[constructed++] = owner;
                owner.prepareThread();
                owner.start();
            }
            return new Reservation(reserved);
        } catch (RuntimeException | Error constructionFailure) {
            rollbackReservation(reserved, constructed, permits, constructionFailure);
            throw constructionFailure;
        }
    }

    public int ownerCount() {
        synchronized (capacityLock) {
            return owners;
        }
    }

    int capacity() {
        return capacity;
    }

    private static int requirePositiveCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return capacity;
    }

    private void claimCapacity(int permits) {
        synchronized (capacityLock) {
            if (owners > capacity - permits) {
                throw new RejectedExecutionException("Lifecycle publication capacity is exhausted: "
                        + owners
                        + " of "
                        + capacity
                        + " future owners are reserved or executing dependents");
            }
            owners += permits;
        }
    }

    private void rollbackReservation(Owner[] reserved, int constructed, int claimed, Throwable constructionFailure) {
        if (reserved != null) {
            for (int index = 0; index < constructed; index++) {
                try {
                    reserved[index].abortConstruction();
                } catch (RuntimeException | Error rollbackFailure) {
                    attachRollbackFailure(constructionFailure, rollbackFailure);
                }
            }
        }
        try {
            releaseCapacity(claimed - constructed);
        } catch (RuntimeException | Error rollbackFailure) {
            attachRollbackFailure(constructionFailure, rollbackFailure);
        }
    }

    private static void attachRollbackFailure(Throwable primary, Throwable secondary) {
        try {
            SuppressionSupport.attach(primary, secondary);
        } catch (Throwable ignored) {
            // Optional rollback diagnostics must not replace the construction failure.
        }
    }

    private void releaseCapacity(int permits) {
        if (permits == 0) {
            return;
        }
        synchronized (capacityLock) {
            if (permits < 0 || owners < permits) {
                throw new IllegalStateException("Lifecycle publication owner accounting underflow");
            }
            owners -= permits;
        }
    }

    public final class Reservation {

        private final Owner[] reserved;
        private int next;

        private Reservation(Owner[] reserved) {
            this.reserved = reserved;
        }

        public synchronized Permit takePermit() {
            if (next == reserved.length) {
                throw new IllegalStateException("Lifecycle publication reservation has no unused permits");
            }
            Owner owner = reserved[next];
            reserved[next++] = null;
            return owner.permit;
        }

        public synchronized void release() {
            while (next < reserved.length) {
                Owner owner = reserved[next];
                reserved[next++] = null;
                owner.permit.release();
            }
        }
    }

    public final class Permit {

        private final Owner owner;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private Permit(Owner owner) {
            this.owner = owner;
        }

        public void publish(Runnable publication) {
            Objects.requireNonNull(publication, "publication");
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("Lifecycle publication permit has already been consumed");
            }
            owner.assign(publication);
        }

        public void release() {
            if (consumed.compareAndSet(false, true)) {
                owner.assign(null);
            }
        }
    }

    private final class Owner implements Runnable {

        private final Permit permit = new Permit(this);
        private final CountDownLatch assignment = new CountDownLatch(1);
        private final AtomicBoolean accountingReleased = new AtomicBoolean();
        private volatile Runnable publication;
        private Thread thread;

        private void prepareThread() {
            thread = Objects.requireNonNull(ownerThreads.newThread(this), "ownerThreads returned null");
            if (thread.getState() != Thread.State.NEW) {
                throw new RejectedExecutionException("Lifecycle owner thread must be unstarted");
            }
        }

        private void start() {
            thread.start();
            if (!thread.isAlive()) {
                throw new RejectedExecutionException("Lifecycle owner thread did not start");
            }
        }

        private void assign(Runnable task) {
            publication = task;
            assignment.countDown();
        }

        private void abortConstruction() {
            assignment.countDown();
            Thread candidate = thread;
            if (candidate == null || !candidate.isAlive()) {
                releaseAccounting();
            }
        }

        private void releaseAccounting() {
            if (accountingReleased.compareAndSet(false, true)) {
                releaseCapacity(1);
            }
        }

        @Override
        public void run() {
            boolean restoreInterrupt = false;
            try {
                while (true) {
                    try {
                        assignment.await();
                        break;
                    } catch (InterruptedException interruption) {
                        restoreInterrupt = true;
                    }
                }
                Runnable task = publication;
                if (task != null) {
                    task.run();
                }
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
                releaseAccounting();
            }
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns a hard bound for mandatory lifecycle-future publications without consuming physical-cleanup capacity.
 *
 * <p>Every permit has an asynchronous owner running before the permit is returned. A publication can therefore never
 * fall back to its caller or a physical-cleanup thread. The owner remains accounted until {@code CompletableFuture}
 * has invoked every synchronous dependent: releasing it before {@code complete} returns would allow an unbounded
 * number of continuation-blocked threads. Saturation is consequently rejected only while reserving a future owner,
 * before that future is exposed; an accepted publication is never dropped or run inline.
 *
 * @hidden
 */
public final class BoundedLifecyclePublisher {

    public static final int SHARED_CAPACITY = BoundedCloseDispatcher.SHARED_MAX_OUTSTANDING_CAPACITY;

    private static final FailureProbe NO_FAILURES = (point, ordinal) -> {};
    private static final OwnerLifecycleProbe NO_OWNER_LIFECYCLE_PROBE = new OwnerLifecycleProbe() {};
    private static final PermitReleaser RELEASE_PERMIT = Permit::release;
    private static final Runnable NO_START_HOOK = () -> {};
    private static final BoundedLifecyclePublisher SHARED = new BoundedLifecyclePublisher(SHARED_CAPACITY);

    private final int capacity;
    private final ThreadStarter threadStarter;
    private final PermitDequeAllocator permitDequeAllocator;
    private final FailureProbe failureProbe;
    private final OwnerLifecycleProbe ownerLifecycleProbe;
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();
    private int owners;

    public BoundedLifecyclePublisher(int capacity) {
        this(capacity, StartWitness::start, ArrayDeque::new, NO_FAILURES, NO_OWNER_LIFECYCLE_PROBE);
    }

    BoundedLifecyclePublisher(
            int capacity,
            ThreadStarter threadStarter,
            PermitDequeAllocator permitDequeAllocator,
            FailureProbe failureProbe) {
        this(capacity, threadStarter, permitDequeAllocator, failureProbe, NO_OWNER_LIFECYCLE_PROBE);
    }

    BoundedLifecyclePublisher(
            int capacity,
            ThreadStarter threadStarter,
            PermitDequeAllocator permitDequeAllocator,
            FailureProbe failureProbe,
            OwnerLifecycleProbe ownerLifecycleProbe) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.threadStarter = Objects.requireNonNull(threadStarter, "threadStarter");
        this.permitDequeAllocator = Objects.requireNonNull(permitDequeAllocator, "permitDequeAllocator");
        this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe");
        this.ownerLifecycleProbe = Objects.requireNonNull(ownerLifecycleProbe, "ownerLifecycleProbe");
    }

    public static BoundedLifecyclePublisher shared() {
        return SHARED;
    }

    public Reservation reserve(int permits) {
        if (permits <= 0 || permits > capacity) {
            throw new IllegalArgumentException("permits must be between 1 and " + capacity);
        }
        failureProbe.at(FailurePoint.BEFORE_PERMIT_DEQUE_ALLOCATION, -1);
        ArrayDeque<Permit> reserved =
                Objects.requireNonNull(permitDequeAllocator.allocate(permits), "permitDequeAllocator returned null");
        ConstructionLedger ledger = new ConstructionLedger(permits);
        try {
            reserveOwners(permits);
            ledger.recordAccounting();
            failureProbe.at(FailurePoint.AFTER_ACCOUNTING, -1);
            for (int index = 0; index < permits; index++) {
                failureProbe.at(FailurePoint.BEFORE_OWNER_CONSTRUCTION, index);
                Owner owner = new Owner(index);
                ledger.recordOwner(index, owner);
                failureProbe.at(FailurePoint.AFTER_OWNER_CONSTRUCTION, index);
                owner.start();
                failureProbe.at(FailurePoint.AFTER_OWNER_START, index);
                failureProbe.at(FailurePoint.BEFORE_PERMIT_CONSTRUCTION, index);
                Permit permit = new Permit(owner);
                failureProbe.at(FailurePoint.AFTER_PERMIT_CONSTRUCTION, index);
                failureProbe.at(FailurePoint.BEFORE_PERMIT_RECORD, index);
                reserved.addLast(permit);
                failureProbe.at(FailurePoint.AFTER_PERMIT_RECORD, index);
            }
            failureProbe.at(FailurePoint.BEFORE_RESERVATION_CONSTRUCTION, -1);
            Reservation reservation = new Reservation(reserved);
            failureProbe.at(FailurePoint.AFTER_RESERVATION_CONSTRUCTION, -1);
            ledger.commit();
            return reservation;
        } catch (RuntimeException | Error constructionFailure) {
            ledger.rollback();
            throw constructionFailure;
        }
    }

    private void reserveOwners(int permits) {
        synchronized (lock) {
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

    public int ownerCount() {
        synchronized (lock) {
            return owners;
        }
    }

    public int capacity() {
        return capacity;
    }

    private void releaseOwners(int count) {
        if (count == 0) {
            return;
        }
        synchronized (lock) {
            if (count < 0 || owners < count) {
                throw new IllegalStateException("Lifecycle publication owner accounting underflow");
            }
            owners -= count;
            lock.notifyAll();
        }
    }

    enum FailurePoint {
        BEFORE_PERMIT_DEQUE_ALLOCATION,
        AFTER_ACCOUNTING,
        BEFORE_OWNER_CONSTRUCTION,
        AFTER_OWNER_CONSTRUCTION,
        AFTER_OWNER_START,
        BEFORE_PERMIT_CONSTRUCTION,
        AFTER_PERMIT_CONSTRUCTION,
        BEFORE_PERMIT_RECORD,
        AFTER_PERMIT_RECORD,
        BEFORE_RESERVATION_CONSTRUCTION,
        AFTER_RESERVATION_CONSTRUCTION
    }

    @FunctionalInterface
    interface ThreadStarter {

        /** Launches the publisher-owned invocation only through {@code witness}. */
        void start(StartWitness witness);
    }

    static final class StartWitness {

        private final String threadPrefix;
        private final Runnable task;
        private final Runnable afterWrapperExit;
        private Thread owner;
        private boolean executionMayHaveStarted;

        private StartWitness(String threadPrefix, Runnable task, Runnable afterWrapperExit) {
            this.threadPrefix = threadPrefix;
            this.task = task;
            this.afterWrapperExit = afterWrapperExit;
        }

        Thread start() {
            return start(NO_START_HOOK, NO_START_HOOK);
        }

        Thread start(Runnable beforeEntry, Runnable afterExit) {
            Objects.requireNonNull(beforeEntry, "beforeEntry");
            Objects.requireNonNull(afterExit, "afterExit");
            Thread candidate;
            synchronized (this) {
                if (owner != null) {
                    throw new IllegalStateException("Lifecycle owner start witness has already been used");
                }
                candidate = Threading.unstarted(threadPrefix, () -> {
                    try {
                        try {
                            beforeEntry.run();
                        } finally {
                            try {
                                task.run();
                            } finally {
                                afterExit.run();
                            }
                        }
                    } finally {
                        afterWrapperExit.run();
                    }
                });
                owner = candidate;
                executionMayHaveStarted = true;
            }
            try {
                candidate.start();
            } catch (RuntimeException | Error startFailure) {
                synchronized (this) {
                    if (candidate.getState() == Thread.State.NEW) {
                        executionMayHaveStarted = false;
                    }
                }
                throw startFailure;
            }
            return candidate;
        }

        private synchronized Thread owner() {
            return owner;
        }

        private synchronized boolean executionMayHaveStarted() {
            return executionMayHaveStarted;
        }
    }

    @FunctionalInterface
    interface PermitDequeAllocator {

        ArrayDeque<Permit> allocate(int permits);
    }

    @FunctionalInterface
    interface FailureProbe {

        void at(FailurePoint point, int ordinal);
    }

    interface OwnerLifecycleProbe {

        default void beforeAbortedOwnerExit(int ordinal) {}
    }

    @FunctionalInterface
    interface PermitReleaser {

        void release(Permit permit);
    }

    private final class ConstructionLedger {

        private final int permitCount;
        private final Owner[] constructedOwners;
        private boolean accounted;
        private boolean committed;

        private ConstructionLedger(int permitCount) {
            this.permitCount = permitCount;
            constructedOwners = new BoundedLifecyclePublisher.Owner[permitCount];
        }

        private void recordAccounting() {
            accounted = true;
        }

        private void recordOwner(int index, Owner owner) {
            if (constructedOwners[index] != null) {
                throw new IllegalStateException("Lifecycle publication owner was already recorded");
            }
            constructedOwners[index] = owner;
        }

        private void commit() {
            committed = true;
        }

        private void rollback() {
            if (!accounted || committed) {
                return;
            }
            int constructed = 0;
            for (Owner owner : constructedOwners) {
                if (owner != null) {
                    constructed++;
                    owner.abortConstruction();
                }
            }
            releaseOwners(permitCount - constructed);
            accounted = false;
        }
    }

    public final class Reservation {

        private final ArrayDeque<Permit> permits;

        private Reservation(ArrayDeque<Permit> permits) {
            this.permits = permits;
        }

        public Permit takePermit() {
            synchronized (this) {
                Permit permit = permits.pollFirst();
                if (permit == null) {
                    throw new IllegalStateException("Lifecycle publication reservation has no unused permits");
                }
                return permit;
            }
        }

        public void release() {
            release(RELEASE_PERMIT);
        }

        void release(PermitReleaser permitReleaser) {
            Objects.requireNonNull(permitReleaser, "permitReleaser");
            Throwable firstFailure = null;
            synchronized (this) {
                Permit permit;
                while ((permit = permits.pollFirst()) != null) {
                    try {
                        permitReleaser.release(permit);
                    } catch (RuntimeException | Error releaseFailure) {
                        if (firstFailure == null) {
                            firstFailure = releaseFailure;
                        }
                    }
                }
            }
            rethrowReleaseFailure(firstFailure);
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
            owner.publish(publication);
        }

        public void release() {
            if (consumed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }

    private final class Owner implements Runnable {

        private final int ordinal;
        private final AtomicBoolean accountingReleased = new AtomicBoolean();
        private Runnable publication;
        private boolean assigned;
        private boolean released;
        private boolean ownerMayHaveStarted;
        private boolean runEntered;
        private boolean constructionAborted;

        private Owner(int ordinal) {
            this.ordinal = ordinal;
        }

        private void start() {
            StartWitness witness = new StartWitness(
                    "procwright-lifecycle-publication-" + sequence.getAndIncrement() + '-',
                    this,
                    this::releaseAccounting);
            try {
                threadStarter.start(witness);
                Thread owner = witness.owner();
                if (owner == null
                        || !witness.executionMayHaveStarted()
                        || owner == Thread.currentThread()
                        || !owner.isAlive()) {
                    throw new RejectedExecutionException(
                            "Lifecycle thread starter must launch the publisher-owned invocation asynchronously");
                }
            } catch (RuntimeException | Error startFailure) {
                synchronized (this) {
                    ownerMayHaveStarted = witness.executionMayHaveStarted();
                }
                throw startFailure;
            }
            synchronized (this) {
                ownerMayHaveStarted = true;
            }
        }

        private void publish(Runnable task) {
            synchronized (this) {
                if (assigned || released) {
                    throw new IllegalStateException("Lifecycle publication owner has already been consumed");
                }
                publication = task;
                assigned = true;
                notifyAll();
            }
        }

        private void release() {
            synchronized (this) {
                if (assigned || released) {
                    return;
                }
                released = true;
                notifyAll();
            }
        }

        private void abortConstruction() {
            boolean releaseWithoutOwner;
            synchronized (this) {
                constructionAborted = true;
                if (!assigned && !released) {
                    released = true;
                    notifyAll();
                }
                releaseWithoutOwner = !ownerMayHaveStarted && !runEntered;
            }
            if (releaseWithoutOwner) {
                releaseAccounting();
            }
        }

        private void releaseAccounting() {
            if (accountingReleased.compareAndSet(false, true)) {
                releaseOwners(1);
            }
        }

        @Override
        public void run() {
            boolean restoreInterrupt = false;
            Runnable task;
            try {
                synchronized (this) {
                    runEntered = true;
                    notifyAll();
                }
                synchronized (this) {
                    while (!assigned && !released) {
                        try {
                            wait();
                        } catch (InterruptedException interruption) {
                            restoreInterrupt = true;
                        }
                    }
                    task = publication;
                }
                if (task != null) {
                    task.run();
                }
            } finally {
                try {
                    boolean aborted;
                    synchronized (this) {
                        aborted = constructionAborted;
                    }
                    if (aborted) {
                        ownerLifecycleProbe.beforeAbortedOwnerExit(ordinal);
                    }
                } finally {
                    if (restoreInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private static void rethrowReleaseFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}

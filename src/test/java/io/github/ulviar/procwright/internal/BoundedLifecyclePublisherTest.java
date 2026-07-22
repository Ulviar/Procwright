/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

final class BoundedLifecyclePublisherTest {

    private static final int STRESS_REPETITIONS = 32;
    private static final long AWAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    @Test
    void failuresBeforeOrDuringPermitDequeAllocationLeaveExistingOwnersExact() throws Exception {
        for (int repetition = 0; repetition < STRESS_REPETITIONS; repetition++) {
            for (boolean failBeforeAllocation : new boolean[] {true, false}) {
                AssertionError expected = new AssertionError(
                        failBeforeAllocation ? "before permit deque allocation" : "permit deque allocation");
                TrackingThreadStarter starter = new TrackingThreadStarter();
                OneShotFailureProbe probe = new OneShotFailureProbe(
                        BoundedLifecyclePublisher.FailurePoint.BEFORE_PERMIT_DEQUE_ALLOCATION, -1, expected);
                OneShotDequeAllocator allocator = new OneShotDequeAllocator(expected);
                BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(
                        3,
                        starter,
                        failBeforeAllocation ? ArrayDeque::new : allocator,
                        failBeforeAllocation ? probe : (point, ordinal) -> {});
                BoundedLifecyclePublisher.Reservation existing = publisher.reserve(1);
                if (failBeforeAllocation) {
                    probe.arm();
                } else {
                    allocator.arm();
                }

                assertTransactionalRecovery(publisher, starter, existing, expected, "deque allocation " + repetition);
            }
        }
    }

    @Test
    void failuresAfterAccountingRollbackEveryConstructedOwnerAndPermit() throws Exception {
        List<BoundedLifecyclePublisher.FailurePoint> failurePoints = List.of(
                BoundedLifecyclePublisher.FailurePoint.AFTER_ACCOUNTING,
                BoundedLifecyclePublisher.FailurePoint.BEFORE_OWNER_CONSTRUCTION,
                BoundedLifecyclePublisher.FailurePoint.AFTER_OWNER_CONSTRUCTION,
                BoundedLifecyclePublisher.FailurePoint.AFTER_OWNER_START,
                BoundedLifecyclePublisher.FailurePoint.BEFORE_PERMIT_CONSTRUCTION,
                BoundedLifecyclePublisher.FailurePoint.AFTER_PERMIT_CONSTRUCTION,
                BoundedLifecyclePublisher.FailurePoint.BEFORE_PERMIT_RECORD,
                BoundedLifecyclePublisher.FailurePoint.AFTER_PERMIT_RECORD,
                BoundedLifecyclePublisher.FailurePoint.BEFORE_RESERVATION_CONSTRUCTION,
                BoundedLifecyclePublisher.FailurePoint.AFTER_RESERVATION_CONSTRUCTION);
        for (BoundedLifecyclePublisher.FailurePoint failurePoint : failurePoints) {
            List<Integer> ordinals =
                    switch (failurePoint) {
                        case AFTER_ACCOUNTING, BEFORE_RESERVATION_CONSTRUCTION, AFTER_RESERVATION_CONSTRUCTION ->
                            List.of(-1);
                        default -> List.of(0, 1);
                    };
            for (int ordinal : ordinals) {
                AssertionError expected = new AssertionError("injected at " + failurePoint + '[' + ordinal + ']');
                TrackingThreadStarter starter = new TrackingThreadStarter();
                OneShotFailureProbe probe = new OneShotFailureProbe(failurePoint, ordinal, expected);
                BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3, starter, ArrayDeque::new, probe);
                BoundedLifecyclePublisher.Reservation existing = publisher.reserve(1);
                probe.arm();

                assertTransactionalRecovery(
                        publisher, starter, existing, expected, failurePoint.name() + '[' + ordinal + ']');
            }
        }
    }

    @Test
    void threadStarterFailuresCannotLeakCapacityOrOrphanAStartedOwner() throws Exception {
        for (int repetition = 0; repetition < STRESS_REPETITIONS; repetition++) {
            for (boolean failAfterStart : new boolean[] {false, true}) {
                AssertionError expected = new AssertionError(
                        failAfterStart ? "thread starter failed after start" : "thread starter failed before start");
                TrackingThreadStarter starter = new TrackingThreadStarter();
                AtomicBoolean failNextStart = new AtomicBoolean();
                BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(
                        3,
                        witness -> {
                            if (!failAfterStart && failNextStart.compareAndSet(true, false)) {
                                throw expected;
                            }
                            starter.start(witness);
                            if (failNextStart.compareAndSet(true, false)) {
                                throw expected;
                            }
                        },
                        ArrayDeque::new,
                        (point, ordinal) -> {});
                BoundedLifecyclePublisher.Reservation existing = publisher.reserve(1);
                failNextStart.set(true);

                assertTransactionalRecovery(
                        publisher,
                        starter,
                        existing,
                        expected,
                        (failAfterStart ? "after thread start " : "during thread start ") + repetition);
            }
        }
    }

    @Test
    void reservationReleaseDoesNotCopyPermitsBeforeReleasingOwners() throws Exception {
        CopyRejectingPermitDeque permits = new CopyRejectingPermitDeque(3);
        TrackingThreadStarter starter = new TrackingThreadStarter();
        BoundedLifecyclePublisher publisher =
                new BoundedLifecyclePublisher(3, starter, ignored -> permits, (point, ordinal) -> {});
        BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(3);
        permits.rejectCopies();

        try {
            reservation.release();
        } finally {
            permits.allowCopies();
            reservation.release();
        }

        starter.awaitCompletedCount(3, "allocation-free reservation release");
        assertEquals(0, publisher.ownerCount());
    }

    @Test
    void reservationReleaseContinuesAfterEveryPermitFailureAndPreservesTheFirst() throws Exception {
        TrackingThreadStarter starter = new TrackingThreadStarter();
        BoundedLifecyclePublisher publisher =
                new BoundedLifecyclePublisher(3, starter, ArrayDeque::new, (point, ordinal) -> {});
        BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(3);
        AssertionError[] failures = {
            new AssertionError("first permit release"),
            new AssertionError("second permit release"),
            new AssertionError("third permit release")
        };
        AtomicInteger releaseAttempts = new AtomicInteger();

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> reservation.release(permit -> {
                    int ordinal = releaseAttempts.getAndIncrement();
                    permit.release();
                    throw failures[ordinal];
                }));

        assertSame(failures[0], actual);
        assertEquals(3, releaseAttempts.get());
        reservation.release(ignored -> releaseAttempts.incrementAndGet());
        assertEquals(3, releaseAttempts.get());
        starter.awaitCompletedCount(3, "throwing reservation release");
        assertEquals(0, publisher.ownerCount());
    }

    @Test
    void normalPublicationRetainsCapacityUntilThePhysicalWrapperExits() throws Exception {
        AfterExitBarrierStarter starter = new AfterExitBarrierStarter(List.of());
        BoundedLifecyclePublisher publisher =
                new BoundedLifecyclePublisher(1, starter, ArrayDeque::new, (point, ordinal) -> {});

        for (int repetition = 0; repetition < STRESS_REPETITIONS; repetition++) {
            BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(1);
            reservation.takePermit().publish(() -> {});
            starter.awaitAfterExitCount(repetition + 1, "normal publication " + repetition);

            assertEquals(1, publisher.ownerCount());
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));

            starter.releaseExits(1);
            starter.awaitTerminatedRange(repetition, repetition + 1, "normal publication " + repetition);
            assertEquals(0, publisher.ownerCount());
        }
    }

    @Test
    void hostileContinuationAndAfterExitBarrierBothRetainCapacity() throws Exception {
        AfterExitBarrierStarter starter = new AfterExitBarrierStarter(List.of());
        BoundedLifecyclePublisher publisher =
                new BoundedLifecyclePublisher(1, starter, ArrayDeque::new, (point, ordinal) -> {});

        for (int repetition = 0; repetition < STRESS_REPETITIONS; repetition++) {
            Semaphore continuationEntered = new Semaphore(0);
            Semaphore continuationRelease = new Semaphore(0);
            CompletableFuture<Void> publication = new CompletableFuture<>();
            CompletableFuture<Void> continuation = publication.thenRun(() -> {
                continuationEntered.release();
                continuationRelease.acquireUninterruptibly();
            });
            BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(1);
            reservation.takePermit().publish(() -> publication.complete(null));
            awaitPermit(continuationEntered, "hostile continuation " + repetition);

            assertEquals(1, publisher.ownerCount());
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));

            continuationRelease.release();
            starter.awaitAfterExitCount(repetition + 1, "hostile continuation afterExit " + repetition);
            assertEquals(1, publisher.ownerCount());
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));

            starter.releaseExits(1);
            starter.awaitTerminatedRange(repetition, repetition + 1, "hostile continuation " + repetition);
            continuation.join();
            assertEquals(0, publisher.ownerCount());
        }
    }

    @Test
    void afterExitFailuresCannotLeakCapacity() throws Exception {
        List<Throwable> failures = new ArrayList<>(STRESS_REPETITIONS);
        for (int repetition = 0; repetition < STRESS_REPETITIONS; repetition++) {
            failures.add(
                    repetition % 2 == 0
                            ? new IllegalStateException("afterExit runtime failure " + repetition)
                            : new AssertionError("afterExit error " + repetition));
        }
        AfterExitBarrierStarter starter = new AfterExitBarrierStarter(failures);
        BoundedLifecyclePublisher publisher =
                new BoundedLifecyclePublisher(1, starter, ArrayDeque::new, (point, ordinal) -> {});

        for (int repetition = 0; repetition < STRESS_REPETITIONS; repetition++) {
            BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(1);
            reservation.release();
            starter.awaitAfterExitCount(repetition + 1, "throwing afterExit " + repetition);

            assertEquals(1, publisher.ownerCount());
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));

            starter.releaseExits(1);
            starter.awaitTerminatedRange(repetition, repetition + 1, "throwing afterExit " + repetition);
            assertSame(failures.get(repetition), starter.uncaughtFailure(repetition));
            assertEquals(0, publisher.ownerCount());
        }
    }

    @Test
    void repeatedConstructionFailureMatrixRetainsStartedWrappersThroughAfterExit() throws Exception {
        for (int repetition = 0; repetition < STRESS_REPETITIONS; repetition++) {
            for (BoundedLifecyclePublisher.FailurePoint failurePoint :
                    BoundedLifecyclePublisher.FailurePoint.values()) {
                List<Integer> ordinals =
                        switch (failurePoint) {
                            case BEFORE_PERMIT_DEQUE_ALLOCATION,
                                    AFTER_ACCOUNTING,
                                    BEFORE_RESERVATION_CONSTRUCTION,
                                    AFTER_RESERVATION_CONSTRUCTION -> List.of(-1);
                            default -> List.of(0, 1);
                        };
                for (int ordinal : ordinals) {
                    assertConstructionFailureRetainsWrappers(repetition, failurePoint, ordinal);
                }
            }
        }
    }

    @Test
    void startThenThrowBeforeOwnerEntryRetainsCapacityUntilPhysicalOwnerExits() throws Exception {
        int failureCount = STRESS_REPETITIONS;
        List<AssertionError> failures = new ArrayList<>(failureCount);
        for (int ordinal = 0; ordinal < failureCount; ordinal++) {
            failures.add(new AssertionError("delayed-entry start failure " + ordinal));
        }
        GatedStartThenThrowStarter starter = new GatedStartThenThrowStarter(failures);
        BoundedLifecyclePublisher publisher =
                new BoundedLifecyclePublisher(1, starter, ArrayDeque::new, (point, ordinal) -> {});

        for (int ordinal = 0; ordinal < failureCount; ordinal++) {
            AssertionError actual = assertThrows(AssertionError.class, () -> publisher.reserve(1));
            assertSame(failures.get(ordinal), actual);
            starter.awaitLiveCount(1, "delayed-entry failure " + ordinal);
            try {
                assertEquals(1, publisher.ownerCount());
                int startsBeforeRejection = starter.startedCount();
                assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));
                assertEquals(startsBeforeRejection, starter.startedCount());
                assertEquals(1, starter.maximumLiveOwnerCount());
            } finally {
                starter.releaseOneEntry();
            }
            starter.awaitBlockedExitCount(ordinal + 1, "delayed-entry failure " + ordinal);
            assertEquals(1, publisher.ownerCount());
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));
            starter.releaseOneExit();
            starter.awaitCompletedCount(ordinal + 1, "delayed-entry failure " + ordinal);
            assertEquals(0, publisher.ownerCount());
        }

        BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(1);
        assertEquals(1, publisher.ownerCount());
        recovered.release();
        starter.releaseOneEntry();
        starter.awaitBlockedExitCount(failureCount + 1, "recovered delayed-entry owner");
        assertEquals(1, publisher.ownerCount());
        assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));
        starter.releaseOneExit();
        starter.awaitCompletedCount(failureCount + 1, "recovered delayed-entry owner");
        assertEquals(0, publisher.ownerCount());
        assertEquals(publisher.capacity(), starter.maximumLiveOwnerCount());
    }

    @Test
    void repeatedPostStartFailuresRetainCapacityUntilEachAbortedOwnerExits() throws Exception {
        int failureCount = STRESS_REPETITIONS;
        BlockingAbortedExitProbe lifecycleProbe = new BlockingAbortedExitProbe();
        List<AssertionError> failures = new ArrayList<>(failureCount);
        for (int ordinal = 0; ordinal < failureCount; ordinal++) {
            failures.add(new AssertionError("post-start failure " + ordinal));
        }
        LiveTrackingThreadStarter starter = new LiveTrackingThreadStarter();
        RepeatedAfterStartFailureProbe failureProbe = new RepeatedAfterStartFailureProbe(failures);
        BoundedLifecyclePublisher publisher =
                new BoundedLifecyclePublisher(1, starter, ArrayDeque::new, failureProbe, lifecycleProbe);

        for (int ordinal = 0; ordinal < failureCount; ordinal++) {
            AssertionError actual = assertThrows(AssertionError.class, () -> publisher.reserve(1));

            assertSame(failures.get(ordinal), actual);
            lifecycleProbe.awaitBlockedExitCount(ordinal + 1);
            assertEquals(1, publisher.ownerCount());
            assertEquals(1, starter.liveOwnerCount());
            int startsBeforeRejection = starter.startedCount();
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));
            assertEquals(startsBeforeRejection, starter.startedCount());

            lifecycleProbe.releaseOneExit();
            starter.awaitCompletedCount(ordinal + 1, "post-start failure " + ordinal);
            assertEquals(0, publisher.ownerCount());
        }

        BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(1);
        assertEquals(1, publisher.ownerCount());
        recovered.release();
        starter.awaitCompletedCount(failureCount + 1, "recovered owner");
        assertEquals(0, publisher.ownerCount());
        assertEquals(publisher.capacity(), starter.maximumLiveOwnerCount());
    }

    private static void assertConstructionFailureRetainsWrappers(
            int repetition, BoundedLifecyclePublisher.FailurePoint failurePoint, int ordinal) throws Exception {
        String failureStage = "construction failure " + repetition + ' ' + failurePoint + '[' + ordinal + ']';
        AssertionError expected = new AssertionError(failureStage);
        OneShotFailureProbe probe = new OneShotFailureProbe(failurePoint, ordinal, expected);
        AfterExitBarrierStarter starter = new AfterExitBarrierStarter(List.of());
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(2, starter, ArrayDeque::new, probe);
        probe.arm();

        AssertionError actual = assertThrows(AssertionError.class, () -> publisher.reserve(2), failureStage);
        assertSame(expected, actual, failureStage);
        int abortedWrappers = starter.startedCount();
        if (abortedWrappers > 0) {
            starter.awaitAfterExitCount(abortedWrappers, failureStage);
            assertEquals(abortedWrappers, publisher.ownerCount(), failureStage);
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(2), failureStage);
            starter.releaseExits(abortedWrappers);
            starter.awaitTerminatedRange(0, abortedWrappers, failureStage);
        }
        assertEquals(0, publisher.ownerCount(), failureStage);

        BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(2);
        assertEquals(2, publisher.ownerCount(), failureStage);
        recovered.release();
        starter.awaitAfterExitCount(abortedWrappers + 2, failureStage + " recovery");
        assertEquals(2, publisher.ownerCount(), failureStage);
        assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1), failureStage);
        starter.releaseExits(2);
        starter.awaitTerminatedRange(abortedWrappers, abortedWrappers + 2, failureStage + " recovery");
        assertEquals(0, publisher.ownerCount(), failureStage);
    }

    private static void awaitPermit(Semaphore semaphore, String failureStage) throws InterruptedException {
        if (!semaphore.tryAcquire(AWAIT_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
            throw new AssertionError(failureStage + " did not reach its barrier");
        }
    }

    private static void assertTransactionalRecovery(
            BoundedLifecyclePublisher publisher,
            TrackingThreadStarter starter,
            BoundedLifecyclePublisher.Reservation existing,
            AssertionError expected,
            String failureStage)
            throws Exception {
        Throwable actual = assertThrows(expected.getClass(), () -> publisher.reserve(2), failureStage);

        assertSame(expected, actual, failureStage);
        starter.awaitCompletedCount(starter.startedCount() - 1, failureStage);
        assertEquals(1, publisher.ownerCount(), failureStage);

        BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(2);
        assertEquals(3, publisher.ownerCount(), failureStage);
        recovered.release();
        existing.release();
        starter.awaitCompletedCount(starter.startedCount(), failureStage);
        assertEquals(0, publisher.ownerCount(), failureStage);
    }

    private static final class OneShotFailureProbe implements BoundedLifecyclePublisher.FailureProbe {

        private final BoundedLifecyclePublisher.FailurePoint expectedPoint;
        private final int expectedOrdinal;
        private final AssertionError failure;
        private final AtomicBoolean armed = new AtomicBoolean();

        private OneShotFailureProbe(
                BoundedLifecyclePublisher.FailurePoint expectedPoint, int expectedOrdinal, AssertionError failure) {
            this.expectedPoint = expectedPoint;
            this.expectedOrdinal = expectedOrdinal;
            this.failure = failure;
        }

        private void arm() {
            armed.set(true);
        }

        @Override
        public void at(BoundedLifecyclePublisher.FailurePoint point, int ordinal) {
            if (point == expectedPoint && ordinal == expectedOrdinal && armed.compareAndSet(true, false)) {
                throw failure;
            }
        }
    }

    private static final class OneShotDequeAllocator implements BoundedLifecyclePublisher.PermitDequeAllocator {

        private final AssertionError failure;
        private final AtomicBoolean armed = new AtomicBoolean();

        private OneShotDequeAllocator(AssertionError failure) {
            this.failure = failure;
        }

        private void arm() {
            armed.set(true);
        }

        @Override
        public ArrayDeque<BoundedLifecyclePublisher.Permit> allocate(int permits) {
            if (armed.compareAndSet(true, false)) {
                throw failure;
            }
            return new ArrayDeque<>(permits);
        }
    }

    private static final class CopyRejectingPermitDeque extends ArrayDeque<BoundedLifecyclePublisher.Permit> {

        private static final long serialVersionUID = 1L;

        private boolean rejectCopies;

        private CopyRejectingPermitDeque(int permits) {
            super(permits);
        }

        private void rejectCopies() {
            rejectCopies = true;
        }

        private void allowCopies() {
            rejectCopies = false;
        }

        @Override
        public Object[] toArray() {
            if (rejectCopies) {
                throw new OutOfMemoryError("reservation release attempted a permit copy");
            }
            return super.toArray();
        }
    }

    private static final class AfterExitBarrierStarter implements BoundedLifecyclePublisher.ThreadStarter {

        private final List<? extends Throwable> afterExitFailures;
        private final Semaphore exitReleases = new Semaphore(0);
        private final AtomicInteger afterExitCount = new AtomicInteger();
        private final AtomicInteger nextOwner = new AtomicInteger();
        private final AtomicReferenceArray<Throwable> uncaughtFailures;
        private final List<Thread> owners = new ArrayList<>();
        private final Object lifecycleMonitor = new Object();

        private AfterExitBarrierStarter(List<? extends Throwable> afterExitFailures) {
            this.afterExitFailures = afterExitFailures;
            uncaughtFailures = new AtomicReferenceArray<>(afterExitFailures.size());
        }

        @Override
        public void start(BoundedLifecyclePublisher.StartWitness witness) {
            int ordinal = nextOwner.getAndIncrement();
            Thread owner = witness.start(() -> {}, () -> {
                afterExitCount.incrementAndGet();
                synchronized (lifecycleMonitor) {
                    lifecycleMonitor.notifyAll();
                }
                exitReleases.acquireUninterruptibly();
                if (ordinal < afterExitFailures.size()) {
                    rethrow(afterExitFailures.get(ordinal));
                }
            });
            if (ordinal < afterExitFailures.size()) {
                owner.setUncaughtExceptionHandler((ignored, failure) -> uncaughtFailures.set(ordinal, failure));
            }
            synchronized (lifecycleMonitor) {
                owners.add(owner);
            }
        }

        private int startedCount() {
            return nextOwner.get();
        }

        private void awaitAfterExitCount(int expected, String failureStage) throws InterruptedException {
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
            synchronized (lifecycleMonitor) {
                while (afterExitCount.get() < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(failureStage + " owner did not reach afterExit");
                    }
                    TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                }
            }
        }

        private void releaseExits(int count) {
            exitReleases.release(count);
        }

        private void awaitTerminatedRange(int from, int to, String failureStage) throws InterruptedException {
            List<Thread> expectedOwners;
            synchronized (lifecycleMonitor) {
                expectedOwners = List.copyOf(owners.subList(from, to));
            }
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
            for (Thread owner : expectedOwners) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(owner, remaining);
                }
                if (owner.isAlive()) {
                    throw new AssertionError(failureStage + " owner did not terminate");
                }
            }
        }

        private Throwable uncaughtFailure(int ordinal) {
            return uncaughtFailures.get(ordinal);
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) failure;
        }
    }

    private static final class TrackingThreadStarter implements BoundedLifecyclePublisher.ThreadStarter {

        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final List<Thread> completedOwners = new ArrayList<>();
        private final Object completionMonitor = new Object();

        @Override
        public void start(BoundedLifecyclePublisher.StartWitness witness) {
            started.incrementAndGet();
            witness.start(() -> {}, () -> {
                synchronized (completionMonitor) {
                    completedOwners.add(Thread.currentThread());
                    completed.incrementAndGet();
                    completionMonitor.notifyAll();
                }
            });
        }

        private int startedCount() {
            return started.get();
        }

        private void awaitCompletedCount(int expected, String failureStage) throws InterruptedException {
            List<Thread> expectedOwners;
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
            synchronized (completionMonitor) {
                while (completed.get() < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(failureStage + " left an owner waiting forever");
                    }
                    TimeUnit.NANOSECONDS.timedWait(completionMonitor, remaining);
                }
                expectedOwners = List.copyOf(completedOwners.subList(0, expected));
            }
            for (Thread owner : expectedOwners) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(owner, remaining);
                }
                if (owner.isAlive()) {
                    throw new AssertionError(failureStage + " left an owner wrapper alive");
                }
            }
        }
    }

    private static final class RepeatedAfterStartFailureProbe implements BoundedLifecyclePublisher.FailureProbe {

        private final List<AssertionError> failures;
        private final AtomicInteger nextFailure = new AtomicInteger();

        private RepeatedAfterStartFailureProbe(List<AssertionError> failures) {
            this.failures = failures;
        }

        @Override
        public void at(BoundedLifecyclePublisher.FailurePoint point, int ordinal) {
            if (point != BoundedLifecyclePublisher.FailurePoint.AFTER_OWNER_START || ordinal != 0) {
                return;
            }
            int failure = nextFailure.getAndIncrement();
            if (failure < failures.size()) {
                throw failures.get(failure);
            }
        }
    }

    private static final class LiveTrackingThreadStarter implements BoundedLifecyclePublisher.ThreadStarter {

        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger liveOwners = new AtomicInteger();
        private final AtomicInteger maximumLiveOwners = new AtomicInteger();
        private final List<Thread> completedOwners = new ArrayList<>();
        private final Object completionMonitor = new Object();

        @Override
        public void start(BoundedLifecyclePublisher.StartWitness witness) {
            started.incrementAndGet();
            witness.start(
                    () -> {
                        int live = liveOwners.incrementAndGet();
                        maximumLiveOwners.accumulateAndGet(live, Math::max);
                    },
                    () -> {
                        liveOwners.decrementAndGet();
                        synchronized (completionMonitor) {
                            completedOwners.add(Thread.currentThread());
                            completed.incrementAndGet();
                            completionMonitor.notifyAll();
                        }
                    });
        }

        private int startedCount() {
            return started.get();
        }

        private int liveOwnerCount() {
            return liveOwners.get();
        }

        private int maximumLiveOwnerCount() {
            return maximumLiveOwners.get();
        }

        private void awaitCompletedCount(int expected, String failureStage) throws InterruptedException {
            List<Thread> expectedOwners;
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
            synchronized (completionMonitor) {
                while (completed.get() < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(failureStage + " owner did not terminate");
                    }
                    TimeUnit.NANOSECONDS.timedWait(completionMonitor, remaining);
                }
                expectedOwners = List.copyOf(completedOwners.subList(0, expected));
            }
            for (Thread owner : expectedOwners) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(owner, remaining);
                }
                if (owner.isAlive()) {
                    throw new AssertionError(failureStage + " owner wrapper did not terminate");
                }
            }
        }
    }

    private static final class GatedStartThenThrowStarter implements BoundedLifecyclePublisher.ThreadStarter {

        private final List<AssertionError> failures;
        private final Semaphore entryReleases = new Semaphore(0);
        private final Semaphore exitReleases = new Semaphore(0);
        private final AtomicInteger nextFailure = new AtomicInteger();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger liveOwners = new AtomicInteger();
        private final AtomicInteger maximumLiveOwners = new AtomicInteger();
        private final List<Thread> completedOwners = new ArrayList<>();
        private final Object lifecycleMonitor = new Object();
        private int blockedExits;

        private GatedStartThenThrowStarter(List<AssertionError> failures) {
            this.failures = failures;
        }

        @Override
        public void start(BoundedLifecyclePublisher.StartWitness witness) {
            int start = started.getAndIncrement();
            witness.start(
                    () -> {
                        int live = liveOwners.incrementAndGet();
                        maximumLiveOwners.accumulateAndGet(live, Math::max);
                        synchronized (lifecycleMonitor) {
                            lifecycleMonitor.notifyAll();
                        }
                        entryReleases.acquireUninterruptibly();
                    },
                    () -> {
                        synchronized (lifecycleMonitor) {
                            blockedExits++;
                            lifecycleMonitor.notifyAll();
                        }
                        exitReleases.acquireUninterruptibly();
                        liveOwners.decrementAndGet();
                        synchronized (lifecycleMonitor) {
                            completedOwners.add(Thread.currentThread());
                            completed.incrementAndGet();
                            lifecycleMonitor.notifyAll();
                        }
                    });
            awaitLiveCount(start + 1 - completed.get(), "starter launch " + start);
            if (start < failures.size()) {
                throw failures.get(start);
            }
        }

        private int startedCount() {
            return started.get();
        }

        private int maximumLiveOwnerCount() {
            return maximumLiveOwners.get();
        }

        private void releaseOneEntry() {
            entryReleases.release();
        }

        private void awaitBlockedExitCount(int expected, String failureStage) {
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
            synchronized (lifecycleMonitor) {
                while (blockedExits < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(failureStage + " owner did not reach afterExit");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted while awaiting " + failureStage, interruption);
                    }
                }
            }
        }

        private void releaseOneExit() {
            exitReleases.release();
        }

        private void awaitLiveCount(int expected, String failureStage) {
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
            synchronized (lifecycleMonitor) {
                while (liveOwners.get() < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(failureStage + " owner did not become live");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted while awaiting " + failureStage, interruption);
                    }
                }
            }
        }

        private void awaitCompletedCount(int expected, String failureStage) {
            List<Thread> expectedOwners;
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
            synchronized (lifecycleMonitor) {
                while (completed.get() < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(failureStage + " owner did not terminate");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted while awaiting " + failureStage, interruption);
                    }
                }
                expectedOwners = List.copyOf(completedOwners.subList(0, expected));
            }
            for (Thread owner : expectedOwners) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(owner, remaining);
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted while awaiting " + failureStage, interruption);
                    }
                }
                if (owner.isAlive()) {
                    throw new AssertionError(failureStage + " owner wrapper did not terminate");
                }
            }
        }
    }

    private static final class BlockingAbortedExitProbe implements BoundedLifecyclePublisher.OwnerLifecycleProbe {

        private final Semaphore exitReleases = new Semaphore(0);
        private int blockedExits;

        @Override
        public void beforeAbortedOwnerExit(int ordinal) {
            synchronized (this) {
                blockedExits++;
                notifyAll();
            }
            exitReleases.acquireUninterruptibly();
        }

        private synchronized void awaitBlockedExitCount(int expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (blockedExits < expected) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("aborted owner did not reach exit");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while awaiting owner lifecycle", interruption);
                }
            }
        }

        private void releaseOneExit() {
            exitReleases.release();
        }
    }
}

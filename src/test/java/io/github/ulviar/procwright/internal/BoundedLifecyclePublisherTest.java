/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedLifecyclePublisherTest {

    private static final long AWAIT_SECONDS = 5;

    @Test
    void validatesRequestsAndRecoversAfterBulkReservationRelease() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLifecyclePublisher(0));

        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(2);
        assertThrows(IllegalArgumentException.class, () -> publisher.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> publisher.reserve(3));

        BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(2);
        assertEquals(2, publisher.ownerCount());
        assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));

        BoundedLifecyclePublisher.Permit first = reservation.takePermit();
        BoundedLifecyclePublisher.Permit second = reservation.takePermit();
        assertThrows(IllegalStateException.class, reservation::takePermit);
        first.release();
        second.release();

        awaitOwnerCount(publisher, 0);
        BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(2);
        recovered.release();
        awaitOwnerCount(publisher, 0);
    }

    @Test
    void ownersArePhysicallyStartedBeforeReservationReturns() {
        List<Thread> owners = new ArrayList<>();
        ThreadFactory factory = task -> {
            Thread owner = Threading.unstarted("lifecycle-owner-test-", task);
            synchronized (owners) {
                owners.add(owner);
            }
            return owner;
        };
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3, factory);

        BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(3);

        synchronized (owners) {
            assertEquals(3, owners.size());
            assertTrue(owners.stream().allMatch(Thread::isAlive));
            assertTrue(owners.stream().allMatch(Thread::isDaemon));
        }
        reservation.release();
        awaitOwnerCount(publisher, 0);
    }

    @Test
    void constructionAndStartFailuresRollbackAndPreserveIdentity() {
        AssertionError constructionFailure = new AssertionError("thread construction");
        AtomicInteger constructions = new AtomicInteger();
        BoundedLifecyclePublisher constructionPublisher = new BoundedLifecyclePublisher(2, task -> {
            if (constructions.getAndIncrement() == 1) {
                throw constructionFailure;
            }
            return Threading.unstarted("construction-failure-test-", task);
        });

        AssertionError actualConstruction = assertThrows(AssertionError.class, () -> constructionPublisher.reserve(2));
        assertSame(constructionFailure, actualConstruction);
        awaitOwnerCount(constructionPublisher, 0);
        BoundedLifecyclePublisher.Reservation constructionRecovery = constructionPublisher.reserve(2);
        constructionRecovery.release();
        awaitOwnerCount(constructionPublisher, 0);

        IllegalStateException startFailure = new IllegalStateException("thread start");
        AtomicInteger starts = new AtomicInteger();
        BoundedLifecyclePublisher startPublisher = new BoundedLifecyclePublisher(2, task -> {
            if (starts.getAndIncrement() == 1) {
                return startFailingThread(task, startFailure);
            }
            return Threading.unstarted("start-failure-test-", task);
        });

        IllegalStateException actualStart = assertThrows(IllegalStateException.class, () -> startPublisher.reserve(2));
        assertSame(startFailure, actualStart);
        awaitOwnerCount(startPublisher, 0);
        BoundedLifecyclePublisher.Reservation startRecovery = startPublisher.reserve(2);
        startRecovery.release();
        awaitOwnerCount(startPublisher, 0);
    }

    @Test
    void publishAndReleaseRaceConsumesPermitExactlyOnce() throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);

        for (int repetition = 0; repetition < 64; repetition++) {
            BoundedLifecyclePublisher.Permit permit = publisher.reserve(1).takePermit();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger publications = new AtomicInteger();
            AtomicInteger rejectedPublications = new AtomicInteger();
            Thread publish = new Thread(() -> {
                awaitUninterruptibly(start);
                try {
                    permit.publish(publications::incrementAndGet);
                } catch (IllegalStateException expected) {
                    rejectedPublications.incrementAndGet();
                }
            });
            Thread release = new Thread(() -> {
                awaitUninterruptibly(start);
                permit.release();
            });

            publish.start();
            release.start();
            start.countDown();
            publish.join();
            release.join();
            awaitOwnerCount(publisher, 0);

            assertEquals(1, publications.get() + rejectedPublications.get());
            assertTrue(publications.get() <= 1);
            assertThrows(IllegalStateException.class, () -> permit.publish(() -> {}));
            permit.release();
        }
    }

    @Test
    void synchronousContinuationRetainsCapacityUntilItReturns() throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        CompletableFuture<Void> terminal = new CompletableFuture<>();
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch continuationRelease = new CountDownLatch(1);
        CompletableFuture<Void> continuation = terminal.thenRun(() -> {
            continuationEntered.countDown();
            awaitUninterruptibly(continuationRelease);
        });

        publisher.reserve(1).takePermit().publish(() -> terminal.complete(null));
        assertTrue(continuationEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        assertEquals(1, publisher.ownerCount());
        assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));

        continuationRelease.countDown();
        continuation.join();
        awaitOwnerCount(publisher, 0);
        BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(1);
        recovered.release();
        awaitOwnerCount(publisher, 0);
    }

    @Test
    void everyPublicationUsesAFreshNonInheritingOwner() throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        ThreadLocal<String> local = new ThreadLocal<>();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller");
        AtomicReference<Thread> firstOwner = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            CountDownLatch firstCompleted = new CountDownLatch(1);
            publisher.reserve(1).takePermit().publish(() -> {
                try {
                    if (inherited.get() != null) {
                        throw new AssertionError("owner inherited caller state");
                    }
                    firstOwner.set(Thread.currentThread());
                    local.set("contaminated");
                } catch (Throwable actual) {
                    failure.set(actual);
                } finally {
                    firstCompleted.countDown();
                }
            });
            assertTrue(firstCompleted.await(AWAIT_SECONDS, TimeUnit.SECONDS));
            awaitOwnerCount(publisher, 0);

            CountDownLatch secondCompleted = new CountDownLatch(1);
            AtomicReference<Thread> secondOwner = new AtomicReference<>();
            publisher.reserve(1).takePermit().publish(() -> {
                try {
                    secondOwner.set(Thread.currentThread());
                    if (local.get() != null || inherited.get() != null) {
                        throw new AssertionError("owner carried thread-local state");
                    }
                } catch (Throwable actual) {
                    failure.compareAndSet(null, actual);
                } finally {
                    secondCompleted.countDown();
                }
            });
            assertTrue(secondCompleted.await(AWAIT_SECONDS, TimeUnit.SECONDS));
            awaitOwnerCount(publisher, 0);

            assertNull(failure.get());
            assertNotSame(firstOwner.get(), secondOwner.get());
        } finally {
            inherited.remove();
            local.remove();
        }
    }

    @Test
    void fatalPublicationFailureReachesUncaughtHandlerByIdentityAndReleasesCapacity() throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        AssertionError expected = new AssertionError("fatal publication");
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);

        publisher.reserve(1).takePermit().publish(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> {
                uncaught.set(failure);
                reported.countDown();
            });
            throw expected;
        });

        assertTrue(reported.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertSame(expected, uncaught.get());
        awaitOwnerCount(publisher, 0);
        BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(1);
        recovered.release();
        awaitOwnerCount(publisher, 0);
    }

    @Test
    void concurrentReservationsNeverExceedCapacityAndFullyRecover() throws Exception {
        int capacity = 4;
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(capacity);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean violation = new AtomicBoolean();
        AtomicInteger maximumOwners = new AtomicInteger();
        List<Thread> contenders = new ArrayList<>();

        for (int contender = 0; contender < 12; contender++) {
            Thread thread = new Thread(() -> {
                awaitUninterruptibly(start);
                for (int attempt = 0; attempt < 100; attempt++) {
                    try {
                        BoundedLifecyclePublisher.Permit permit =
                                publisher.reserve(1).takePermit();
                        int observed = publisher.ownerCount();
                        maximumOwners.accumulateAndGet(observed, Math::max);
                        if (observed > capacity) {
                            violation.set(true);
                        }
                        permit.publish(() -> {});
                    } catch (RejectedExecutionException saturated) {
                        Thread.yield();
                    }
                }
            });
            contenders.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread contender : contenders) {
            contender.join();
        }
        awaitOwnerCount(publisher, 0);

        assertFalse(violation.get());
        assertTrue(maximumOwners.get() > 0);
        assertTrue(maximumOwners.get() <= capacity);
        BoundedLifecyclePublisher.Reservation fullCapacity = publisher.reserve(capacity);
        assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));
        fullCapacity.release();
        awaitOwnerCount(publisher, 0);
    }

    private static Thread startFailingThread(Runnable task, RuntimeException failure) {
        Thread thread = new Thread(null, task, "lifecycle-start-failure", 0, false) {
            @Override
            public synchronized void start() {
                super.start();
                throw failure;
            }
        };
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitOwnerCount(BoundedLifecyclePublisher publisher, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (publisher.ownerCount() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, publisher.ownerCount());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean restoreInterrupt = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }
}

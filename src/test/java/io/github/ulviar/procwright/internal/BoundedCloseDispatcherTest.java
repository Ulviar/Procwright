/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.dispatch;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.dispatchRequired;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedCloseDispatcherTest {

    @Test
    void validatesCapacityConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(1, 0));
        assertThrows(
                IllegalArgumentException.class, () -> new BoundedCloseDispatcher(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void activeAndPendingWorkShareOneHardBound() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allClosed = new CountDownLatch(3);

        dispatch(dispatcher, blockingClose(firstStarted, releaseFirst, allClosed), "close-", ignored -> {});
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        dispatch(dispatcher, allClosed::countDown, "close-", ignored -> {});
        dispatch(dispatcher, allClosed::countDown, "close-", ignored -> {});

        assertEquals(1, dispatcher.activeCount());
        assertEquals(2, dispatcher.pendingCount());
        assertEquals(3, dispatcher.outstandingCount());
        assertThrows(
                RejectedExecutionException.class, () -> dispatch(dispatcher, () -> {}, "rejected-", ignored -> {}));

        releaseFirst.countDown();
        assertTrue(allClosed.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void mandatorySettlementRetainsCapacityUntilItCompletes() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1);
        CountDownLatch settlementStarted = new CountDownLatch(1);
        CountDownLatch releaseSettlement = new CountDownLatch(1);
        CountDownLatch secondClosed = new CountDownLatch(1);

        dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(() -> {}, "first-close-", ignored -> {
            settlementStarted.countDown();
            awaitUninterruptibly(releaseSettlement);
        }));
        assertTrue(settlementStarted.await(1, TimeUnit.SECONDS));
        dispatch(dispatcher, secondClosed::countDown, "second-close-", ignored -> {});

        assertEquals(1, dispatcher.activeCount());
        assertEquals(1, dispatcher.pendingCount());
        assertEquals(2, dispatcher.outstandingCount());
        assertEquals(1, secondClosed.getCount());

        releaseSettlement.countDown();
        assertTrue(secondClosed.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void successfulCloseSettlesWithoutSubmittingANotification() throws Exception {
        RecordingNotifications notifications = new RecordingNotifications();
        CountDownLatch taskFinished = new CountDownLatch(1);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                (prefix, task) -> {
                    Threading.start(prefix, () -> {
                        try {
                            task.run();
                        } finally {
                            taskFinished.countDown();
                        }
                    });
                },
                notifications);
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicInteger settlements = new AtomicInteger();

        dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                physicalCloses::incrementAndGet,
                "successful-close-",
                failure -> {
                    assertSame(null, failure);
                    settlements.incrementAndGet();
                },
                ignored -> {
                    throw new AssertionError("a successful close has no failure to report");
                }));
        assertTrue(taskFinished.await(1, TimeUnit.SECONDS));

        assertEquals(1, physicalCloses.get());
        assertEquals(1, settlements.get());
        assertEquals(0, notifications.submissions.get());
        assertTrue(notifications.reported.isEmpty());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void ownedFailureSettlesWithoutSubmittingARedundantNotification() throws Exception {
        IOException physicalFailure = new IOException("owned close failed");
        RecordingNotifications notifications = new RecordingNotifications();
        CountDownLatch taskFinished = new CountDownLatch(1);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                (prefix, task) -> {
                    Threading.start(prefix, () -> {
                        try {
                            task.run();
                        } finally {
                            taskFinished.countDown();
                        }
                    });
                },
                notifications);
        AtomicReference<Throwable> settled = new AtomicReference<>();

        dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                () -> {
                    throw physicalFailure;
                },
                "owned-close-",
                settled::set));
        assertTrue(taskFinished.await(1, TimeUnit.SECONDS));

        assertSame(physicalFailure, settled.get());
        assertEquals(0, notifications.submissions.get());
        assertTrue(notifications.reported.isEmpty());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void starterFailureSettlesTheCloseAndReleasesCapacity() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("cannot start close");
        RecordingNotifications notifications = new RecordingNotifications();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                (name, task) -> {
                    throw startFailure;
                },
                notifications);
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicReference<Throwable> settled = new AtomicReference<>();
        AtomicReference<Throwable> reported = new AtomicReference<>();

        dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                physicalCloses::incrementAndGet, "close-", settled::set, reported::set));

        assertEquals(0, physicalCloses.get());
        assertSame(startFailure, settled.get());
        assertSame(startFailure, reported.get());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void requiredDispatchReportsAnImmediateStarterFailureToTheCaller() {
        IllegalStateException startFailure = new IllegalStateException("cannot start required close");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, (name, task) -> {
            throw startFailure;
        });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> dispatchRequired(dispatcher, () -> {}, "required-close-", ignored -> {}));

        assertSame(startFailure, thrown);
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void callbackFailureDoesNotBlockLaterCloseWork() throws Exception {
        RecordingNotifications notifications = new RecordingNotifications();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, Threading::start, notifications);
        IOException closeFailure = new IOException("close failed");
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");
        CountDownLatch secondClosed = new CountDownLatch(1);

        dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                () -> {
                    throw closeFailure;
                },
                "close-",
                ignored -> {},
                ignored -> {
                    throw callbackFailure;
                }));
        dispatch(dispatcher, secondClosed::countDown, "close-", ignored -> {});

        assertTrue(secondClosed.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        assertTrue(eventually(() -> notifications.reported.size() == 1));
        assertEquals(1, notifications.reported.size());
        assertSame(closeFailure, notifications.reported.get(0).getCause());
    }

    private static java.io.Closeable blockingClose(
            CountDownLatch started, CountDownLatch release, CountDownLatch closed) {
        return () -> {
            started.countDown();
            awaitUninterruptibly(release);
            closed.countDown();
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return condition.getAsBoolean();
    }

    private static final class RecordingNotifications implements CloseNotificationPublisher {

        private final List<Throwable> reported = new CopyOnWriteArrayList<>();
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public void execute(Thread sourceThread, Runnable callback) {
            submissions.incrementAndGet();
            callback.run();
        }

        @Override
        public void report(Thread sourceThread, Throwable failure) {
            reported.add(failure);
        }
    }
}

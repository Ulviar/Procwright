/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

final class BoundedCloseDispatcherLinearizationTest {

    @Test
    void releasedActiveSlotLaunchesAcceptedWorkBeforeBlockingSettlement() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2);
        CountDownLatch settlementEntered = new CountDownLatch(1);
        CountDownLatch releaseSettlement = new CountDownLatch(1);
        CountDownLatch secondClosed = new CountDownLatch(1);
        BoundedCloseDispatcher.Reservation reservation = dispatcher.reserve(2);

        reservation.dispatch(
                BoundedCloseDispatcher.ownedCloseRequest(
                        () -> {},
                        "procwright-blocking-settlement-",
                        ignored -> {
                            settlementEntered.countDown();
                            awaitUninterruptibly(releaseSettlement);
                        },
                        ignored -> {},
                        () -> {}),
                BoundedCloseDispatcher.closeRequest(
                        secondClosed::countDown, "procwright-close-after-settlement-", ignored -> {}));
        try {
            assertTrue(settlementEntered.await(1, TimeUnit.SECONDS));
            assertTrue(
                    secondClosed.await(1, TimeUnit.SECONDS),
                    "accepted queued close must launch before arbitrary settlement runs");
        } finally {
            releaseSettlement.countDown();
        }

        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void nullReservationDispatchDoesNotConsumeItsPermit() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2);
        BoundedCloseDispatcher.Reservation reservation = dispatcher.reserve(1);

        assertThrows(
                NullPointerException.class, () -> reservation.dispatch((BoundedCloseDispatcher.CloseRequest) null));
        assertEquals(1, dispatcher.outstandingCount());

        reservation.release();
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));

        CountDownLatch recoveredClose = new CountDownLatch(1);
        dispatcher.reserve(1).dispatch(recoveredClose::countDown, "procwright-close-after-null-", ignored -> {});
        assertTrue(recoveredClose.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void queuedStarterFailureIsReportedAfterTheOriginalDispatchReturns() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("queued close starter failed");
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch firstCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstClose = new CountDownLatch(1);
        CountDownLatch secondClosed = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, (name, task) -> {
            if (starts.getAndIncrement() == 1) {
                throw startFailure;
            }
            Threading.start(name, task);
        });
        BoundedCloseDispatcher.Reservation reservation = dispatcher.reserve(2);

        reservation.dispatch(
                BoundedCloseDispatcher.closeRequest(
                        () -> {
                            firstCloseEntered.countDown();
                            awaitUninterruptibly(releaseFirstClose);
                        },
                        "procwright-active-close-",
                        ignored -> {}),
                BoundedCloseDispatcher.closeRequest(secondClosed::countDown, "procwright-queued-close-", failure -> {
                    reported.set(failure);
                    failureReported.countDown();
                }));
        assertTrue(firstCloseEntered.await(1, TimeUnit.SECONDS));
        assertEquals(1, starts.get(), "the queued close must not start during the original dispatch");

        releaseFirstClose.countDown();

        assertTrue(secondClosed.await(1, TimeUnit.SECONDS));
        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertSame(startFailure, reported.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void notificationStartFailureCannotStopFallbackCleanupProgress() throws Exception {
        CloseNotificationPublisher rejectedNotifications = new CloseNotificationPublisher() {
            @Override
            public void execute(Thread sourceThread, Runnable callback) {
                throw new RejectedExecutionException("notification owner rejected");
            }

            @Override
            public void report(Thread sourceThread, Throwable failure) {
                throw new AssertionError("report must not be needed");
            }
        };
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                2,
                (name, task) -> {
                    throw new RejectedExecutionException("close owner rejected");
                },
                rejectedNotifications);
        BoundedCloseDispatcher.Reservation reservation = dispatcher.reserve(2);
        CountDownLatch firstSettled = new CountDownLatch(1);
        CountDownLatch secondSettled = new CountDownLatch(1);

        assertThrows(
                RejectedExecutionException.class,
                () -> reservation.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                        () -> {}, "first-fallback-", ignored -> firstSettled.countDown(), ignored -> {}, () -> {})));
        assertTrue(firstSettled.await(1, TimeUnit.SECONDS));

        assertThrows(
                RejectedExecutionException.class,
                () -> reservation.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                        () -> {}, "second-fallback-", ignored -> secondSettled.countDown(), ignored -> {}, () -> {})));

        assertTrue(secondSettled.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void fallbackFailureAggregationDoesNotDependOnTheLaunchFailureMonitor() throws Exception {
        IllegalStateException firstStart = new IllegalStateException("first owner rejected");
        IllegalArgumentException secondStart = new IllegalArgumentException("second owner rejected");
        AssertionError firstClose = new AssertionError("first close failed");
        IllegalArgumentException secondClose = new IllegalArgumentException("second close failed");
        AtomicInteger starts = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, (name, task) -> {
            if (starts.getAndIncrement() == 0) {
                throw firstStart;
            }
            throw secondStart;
        });
        BoundedCloseDispatcher.Reservation reservation = dispatcher.reserve(2);
        CountDownLatch firstSettled = new CountDownLatch(1);
        CountDownLatch secondSettled = new CountDownLatch(1);
        AtomicReference<Throwable> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> secondResult = new AtomicReference<>();
        AtomicInteger firstCloses = new AtomicInteger();
        AtomicInteger secondCloses = new AtomicInteger();
        AtomicInteger firstSettlements = new AtomicInteger();
        AtomicInteger secondSettlements = new AtomicInteger();

        try (var monitor = hold(firstStart)) {
            monitor.verifyHeld();
            assertSame(
                    firstStart,
                    assertThrows(
                            IllegalStateException.class,
                            () -> reservation.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                                    () -> {
                                        firstCloses.incrementAndGet();
                                        throw firstClose;
                                    },
                                    "first-blocked-failure-",
                                    failure -> {
                                        firstSettlements.incrementAndGet();
                                        firstResult.set(failure);
                                        firstSettled.countDown();
                                    },
                                    ignored -> {},
                                    () -> {}))));
            assertTrue(firstSettled.await(1, TimeUnit.SECONDS));

            assertSame(
                    secondStart,
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> reservation.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                                    () -> {
                                        secondCloses.incrementAndGet();
                                        throw secondClose;
                                    },
                                    "second-independent-failure-",
                                    failure -> {
                                        secondSettlements.incrementAndGet();
                                        secondResult.set(failure);
                                        secondSettled.countDown();
                                    },
                                    ignored -> {},
                                    () -> {}))));
            assertTrue(secondSettled.await(1, TimeUnit.SECONDS));
        }

        assertSame(firstStart, firstResult.get().getCause());
        assertEquals(
                java.util.List.of(firstClose),
                java.util.List.of(firstResult.get().getSuppressed()));
        assertSame(secondStart, secondResult.get().getCause());
        assertEquals(
                java.util.List.of(secondClose),
                java.util.List.of(secondResult.get().getSuppressed()));
        assertEquals(1, firstCloses.get());
        assertEquals(1, secondCloses.get());
        assertEquals(1, firstSettlements.get());
        assertEquals(1, secondSettlements.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void physicalSettlementAndCallbackFailuresProduceOneFlatDiagnosticAggregate() throws Exception {
        IOException physicalFailure = new IOException("physical close failed");
        IllegalStateException settlementFailure = new IllegalStateException("settlement failed");
        AssertionError callbackFailure = new AssertionError("callback failed");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch reportCompleted = new CountDownLatch(1);
        CloseNotificationPublisher notifications = new CloseNotificationPublisher() {
            @Override
            public void execute(Thread sourceThread, Runnable callback) {
                callback.run();
            }

            @Override
            public void report(Thread sourceThread, Throwable failure) {
                reported.set(failure);
                reportCompleted.countDown();
            }
        };
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, Threading::start, notifications);

        dispatcher
                .reserve(1)
                .dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                        () -> {
                            throw physicalFailure;
                        },
                        "procwright-flat-close-failures-",
                        ignored -> {
                            throw settlementFailure;
                        },
                        ignored -> {
                            throw callbackFailure;
                        },
                        () -> {}));

        assertTrue(reportCompleted.await(1, TimeUnit.SECONDS));
        assertSame(physicalFailure, reported.get().getCause());
        assertEquals(
                List.of(settlementFailure, callbackFailure),
                List.of(reported.get().getSuppressed()));
        assertEquals(0, physicalFailure.getSuppressed().length);
        assertEquals(0, settlementFailure.getSuppressed().length);
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (deadline - System.nanoTime() > 0) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
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
}

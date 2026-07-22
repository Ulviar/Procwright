/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void nullReservationDispatchDoesNotConsumeItsPermitOrStrandFallbackOwnership() throws Exception {
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
    void reservationReleaseContinuesAfterEveryPermitFailureAndPreservesTheFirst() {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        BoundedCloseDispatcher.Reservation reservation = dispatcher.reserve(3);
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
        assertEquals(0, dispatcher.outstandingCount());
        reservation.release(ignored -> releaseAttempts.incrementAndGet());
        assertEquals(3, releaseAttempts.get());
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

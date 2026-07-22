/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OneShotIoTaskOwnerTest {

    @Test
    void cancelledNonCooperativeTaskRetainsGlobalPermitUntilActualExit() throws Exception {
        OneShotIoTaskOwner owner = new OneShotIoTaskOwner(1);
        OneShotIoTaskOwner.Reservation reservation = owner.reserve(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            OneShotIoTaskOwner.OwnedFuture<Void> task = reservation.submit(executor, () -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return null;
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            task.cancel(true);

            CommandExecutionException exhausted = assertThrows(CommandExecutionException.class, () -> owner.reserve(1));
            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, exhausted.reason());
            assertEquals(0, owner.availablePermits());

            release.countDown();
            assertTrue(eventually(() -> owner.availablePermits() == 1));
            try (OneShotIoTaskOwner.Reservation reused = owner.reserve(1)) {
                assertNotNull(reused);
            }
        } finally {
            release.countDown();
            reservation.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && deadline - System.nanoTime() > 0) {
            Thread.onSpinWait();
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PoolReplenishmentSchedulerTest {

    @Test
    void cancelledTurnDoesNotRunAfterSaturatedOwnersAreReleased() throws Exception {
        CountDownLatch ownersEntered = new CountDownLatch(8);
        CountDownLatch releaseOwners = new CountDownLatch(1);
        CountDownLatch ownersFinished = new CountDownLatch(8);
        AtomicInteger cancelledRuns = new AtomicInteger();
        try {
            for (int index = 0; index < 8; index++) {
                PoolReplenishmentScheduler.schedule(
                        () -> {
                            ownersEntered.countDown();
                            await(releaseOwners);
                            ownersFinished.countDown();
                        },
                        Duration.ZERO);
            }
            assertTrue(ownersEntered.await(1, TimeUnit.SECONDS));

            PoolReplenisher.Cancellation cancellation =
                    PoolReplenishmentScheduler.schedule(cancelledRuns::incrementAndGet, Duration.ZERO);
            cancellation.cancel();
        } finally {
            releaseOwners.countDown();
        }
        assertTrue(ownersFinished.await(1, TimeUnit.SECONDS));
        CountDownLatch marker = new CountDownLatch(1);
        PoolReplenishmentScheduler.schedule(marker::countDown, Duration.ZERO);
        assertTrue(marker.await(1, TimeUnit.SECONDS));
        assertEquals(0, cancelledRuns.get());
    }

    @Test
    void ownerUsesThePlatformContextClassLoader() throws Exception {
        AtomicReference<ClassLoader> observed = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        PoolReplenishmentScheduler.schedule(
                () -> {
                    observed.set(Thread.currentThread().getContextClassLoader());
                    completed.countDown();
                },
                Duration.ZERO);

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertSame(ClassLoader.getPlatformClassLoader(), observed.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}

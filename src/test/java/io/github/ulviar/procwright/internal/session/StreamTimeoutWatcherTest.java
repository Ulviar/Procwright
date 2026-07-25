/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StreamTimeoutWatcherTest {

    @Test
    void stopAndAwaitSettlesWatcherWithoutRunningExpiration() {
        AtomicInteger expirations = new AtomicInteger();
        StreamTimeoutWatcher watcher = new StreamTimeoutWatcher();

        watcher.start(Duration.ofDays(1), () -> false, expirations::incrementAndGet);
        watcher.stopAndAwait();

        assertTrue(watcher.stopped().isDone());
        assertEquals(0, expirations.get());
    }

    @Test
    void preselectedTerminalOutcomeStopsNewWatcher() {
        AtomicInteger expirations = new AtomicInteger();
        StreamTimeoutWatcher watcher = new StreamTimeoutWatcher();

        watcher.start(Duration.ofDays(1), () -> true, expirations::incrementAndGet);
        watcher.stopAndAwait();

        assertEquals(0, expirations.get());
    }

    @Test
    void expirationCanAwaitItsOwnSettlementWithoutDeadlock() throws Exception {
        StreamTimeoutWatcher watcher = new StreamTimeoutWatcher();
        CountDownLatch expired = new CountDownLatch(1);

        watcher.start(Duration.ofMillis(1), () -> false, () -> {
            watcher.stopAndAwait();
            expired.countDown();
        });

        assertTrue(expired.await(1, TimeUnit.SECONDS));
        watcher.stopped().get(1, TimeUnit.SECONDS);
    }

    @Test
    void stopAndAwaitWaitsUntilExpirationCallbackReturns() throws Exception {
        StreamTimeoutWatcher watcher = new StreamTimeoutWatcher();
        CountDownLatch expirationStarted = new CountDownLatch(1);
        CountDownLatch releaseExpiration = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);

        watcher.start(Duration.ofMillis(1), () -> false, () -> {
            expirationStarted.countDown();
            awaitUninterruptibly(releaseExpiration);
        });
        Thread stopper = null;
        try {
            assertTrue(expirationStarted.await(1, TimeUnit.SECONDS));
            stopper = new Thread(
                    () -> {
                        watcher.stopAndAwait();
                        stopReturned.countDown();
                    },
                    "stream-timeout-watcher-test-stopper");
            stopper.start();

            assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS));
            releaseExpiration.countDown();
            assertTrue(stopReturned.await(1, TimeUnit.SECONDS));
            stopper.join(1_000);
            assertFalse(stopper.isAlive(), "stopper thread did not stop");
            assertTrue(watcher.stopped().isDone());
        } finally {
            releaseExpiration.countDown();
            watcher.stopAndAwait();
            if (stopper != null) {
                stopper.join(1_000);
            }
        }
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

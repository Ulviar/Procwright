/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class StreamTimeoutWatcherTest {

    @Test
    void preselectedTerminalOutcomeStopsNewWatcher() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        StreamTimeoutWatcher watcher = new StreamTimeoutWatcher();

        watcher.start(Duration.ofMillis(50), () -> true, expired::countDown);

        assertFalse(expired.await(150, TimeUnit.MILLISECONDS));
    }

    @Test
    void stopDoesNotWaitForRunningExpirationCallback() throws Exception {
        CountDownLatch expirationStarted = new CountDownLatch(1);
        CountDownLatch releaseExpiration = new CountDownLatch(1);
        CountDownLatch expirationFinished = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        StreamTimeoutWatcher watcher = new StreamTimeoutWatcher();
        watcher.start(Duration.ofMillis(1), () -> false, () -> {
            expirationStarted.countDown();
            try {
                awaitUninterruptibly(releaseExpiration);
            } finally {
                expirationFinished.countDown();
            }
        });
        Thread stopper = new Thread(
                () -> {
                    watcher.stop();
                    stopReturned.countDown();
                },
                "stream-timeout-watcher-test-stopper");
        stopper.setDaemon(true);
        try {
            assertTrue(expirationStarted.await(1, TimeUnit.SECONDS));
            stopper.start();

            assertTrue(stopReturned.await(1, TimeUnit.SECONDS));
            assertFalse(expirationFinished.await(50, TimeUnit.MILLISECONDS));
        } finally {
            releaseExpiration.countDown();
            stopper.join(1_000);
        }
        assertTrue(expirationFinished.await(1, TimeUnit.SECONDS));
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

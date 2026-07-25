/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class BoundedTaskPermitTest {

    @Test
    void repeatedCloseReleasesCapacityOnce() {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskPermit permit = limiter.tryAcquire();

        permit.close();
        permit.close();

        assertEquals(1, limiter.availablePermits());
    }

    @Test
    void concurrentCloseReleasesCapacityOnce() throws Exception {
        int callers = 8;
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskPermit permit = limiter.tryAcquire();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[callers];

        try {
            for (int index = 0; index < callers; index++) {
                threads[index] = new Thread(
                        () -> {
                            ready.countDown();
                            try {
                                start.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            permit.close();
                        },
                        "bounded-permit-close-" + index);
                threads[index].setDaemon(true);
                threads[index].start();
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            }
        } finally {
            start.countDown();
            for (Thread thread : threads) {
                if (thread != null) {
                    thread.interrupt();
                    thread.join(TimeUnit.SECONDS.toMillis(1));
                }
            }
        }
        for (Thread thread : threads) {
            assertFalse(thread.isAlive());
        }
        assertEquals(1, limiter.availablePermits());
    }
}

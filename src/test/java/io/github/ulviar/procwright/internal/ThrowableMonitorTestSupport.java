/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test fixture for proving that lifecycle owners do not retain locks while touching a failure. */
public final class ThrowableMonitorTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private ThrowableMonitorTestSupport() {}

    public static Hold hold(Throwable failure) {
        return new Hold(failure);
    }

    public static boolean awaitBlocked(AtomicReference<Thread> threadReference) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (deadline - System.nanoTime() > 0) {
            Thread thread = threadReference.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    public static final class Hold implements AutoCloseable {

        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread owner;

        private Hold(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            CountDownLatch held = new CountDownLatch(1);
            owner = new Thread(
                    () -> {
                        synchronized (failure) {
                            held.countDown();
                            awaitUninterruptibly(release);
                        }
                    },
                    "procwright-test-held-failure-monitor");
            owner.start();
            try {
                if (!held.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    close();
                    throw new AssertionError("Throwable monitor owner did not start");
                }
            } catch (InterruptedException failureToStart) {
                Thread.currentThread().interrupt();
                close();
                throw new AssertionError("Interrupted while starting Throwable monitor owner", failureToStart);
            }
        }

        public void verifyHeld() {
            if (!owner.isAlive()) {
                throw new AssertionError("Throwable monitor owner stopped unexpectedly");
            }
        }

        @Override
        public void close() {
            release.countDown();
            try {
                owner.join(TIMEOUT.toMillis());
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while stopping Throwable monitor owner", interruption);
            }
            if (owner.isAlive()) {
                throw new AssertionError("Throwable monitor owner did not stop");
            }
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

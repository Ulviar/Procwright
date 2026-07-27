/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OneShotTaskTest {

    @Test
    void reportsFailureToHandlerRegisteredAfterTaskCompletion() throws Exception {
        IllegalStateException failure = new IllegalStateException("I/O failed");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OneShotTask<Void> task = OneShotTask.submit(executor, () -> {
                throw failure;
            });
            assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));

            task.onFailure(observed::set);

            assertSame(failure, observed.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void reportsFailureThatOccursAfterFutureCancellation() throws Exception {
        AssertionError lateFailure = new AssertionError("late I/O failure");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OneShotTask<Void> task = OneShotTask.submit(executor, () -> {
                entered.countDown();
                awaitUninterruptibly(release);
                throw lateFailure;
            });
            task.onFailure(failure -> {
                observed.set(failure);
                reported.countDown();
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            task.cancel(true);
            release.countDown();

            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertSame(lateFailure, observed.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
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

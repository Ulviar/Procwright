/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.Threading;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PoolLifecycleDispatcherTest {

    @Test
    void blockedOwnerDoesNotBlockIndependentTask() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        dispatcher.execute(() -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        dispatcher.execute(secondFinished::countDown);

        assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
        releaseFirst.countDown();
    }

    @Test
    void queuedTasksRunOnceInSubmissionOrder() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(1);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(4);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        dispatcher.execute(() -> {
            ownerStarted.countDown();
            await(releaseOwner);
        });
        assertTrue(ownerStarted.await(1, TimeUnit.SECONDS));

        for (int index = 0; index < 4; index++) {
            int taskId = index;
            dispatcher.execute(() -> {
                order.add(taskId);
                completed.countDown();
            });
        }

        releaseOwner.countDown();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(0, 1, 2, 3), order);
    }

    @Test
    void taskErrorDoesNotReduceOwnerCapacity() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(1);
        CountDownLatch failedTaskRan = new CountDownLatch(1);
        CountDownLatch followingRan = new CountDownLatch(1);

        dispatcher.execute(() -> {
            failedTaskRan.countDown();
            throw new AssertionError("mandatory task failed");
        });
        dispatcher.execute(followingRan::countDown);

        assertTrue(failedTaskRan.await(1, TimeUnit.SECONDS));
        assertTrue(followingRan.await(1, TimeUnit.SECONDS));
    }

    @Test
    void recursiveBlockingSubmissionRunsInline() throws Exception {
        PoolLifecycleDispatcher dispatcher =
                dispatcher(1, 16, PoolLifecycleDispatcher.Saturation.BLOCK, "test-recursive-");
        AtomicReference<Thread> owner = new AtomicReference<>();
        AtomicReference<Thread> nestedOwner = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        dispatcher.execute(() -> {
            owner.set(Thread.currentThread());
            dispatcher.execute(() -> nestedOwner.set(Thread.currentThread()));
            finished.countDown();
        });

        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertSame(owner.get(), nestedOwner.get());
    }

    @Test
    void saturatedRetirementRunsOnSubmittingThread() throws Exception {
        PoolLifecycleDispatcher dispatcher =
                dispatcher(1, 2, PoolLifecycleDispatcher.Saturation.CALLER_RUNS, "test-saturated-");
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch queuedFinished = new CountDownLatch(1);
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try {
            dispatcher.execute(() -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
            dispatcher.execute(queuedFinished::countDown);

            dispatcher.execute(() -> executionThread.set(Thread.currentThread()));

            assertSame(caller, executionThread.get());
        } finally {
            releaseBlocker.countDown();
        }
        assertTrue(queuedFinished.await(1, TimeUnit.SECONDS));
    }

    @Test
    void ownerStartupFailureFailsConstructionAndStopsStartedOwners() throws Exception {
        assertStarterFailure(new IllegalStateException("owner launch failed"));
        assertStarterFailure(new AssertionError("owner launch failed fatally"));
    }

    @Test
    void nullOwnerFailsConstructionAndStopsStartedOwners() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch firstOwnerExited = new CountDownLatch(1);
        ThreadFactory factory = task -> {
            if (launches.incrementAndGet() == 2) {
                return null;
            }
            return Threading.unstartedPlatformNonInheriting("test-null-launch-", () -> {
                try {
                    task.run();
                } finally {
                    firstOwnerExited.countDown();
                }
            });
        };

        NullPointerException observed = assertThrows(
                NullPointerException.class,
                () -> new PoolLifecycleDispatcher(2, 16, factory, PoolLifecycleDispatcher.Saturation.BLOCK));

        assertEquals("thread factory returned null", observed.getMessage());
        assertTrue(firstOwnerExited.await(1, TimeUnit.SECONDS));
        assertEquals(2, launches.get());
    }

    @Test
    void sharedReportAndRetirementUseIndependentOwners() throws Exception {
        CountDownLatch publicationStarted = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch retirementFinished = new CountDownLatch(1);
        PoolLifecycleDispatcher.report(() -> {
            publicationStarted.countDown();
            await(releasePublication);
        });
        assertTrue(publicationStarted.await(1, TimeUnit.SECONDS));

        PoolLifecycleDispatcher.executeRetirementBatch(retirementFinished::countDown);

        assertTrue(retirementFinished.await(1, TimeUnit.SECONDS));
        releasePublication.countDown();
    }

    @Test
    void reportAdmissionBlocksAtItsTotalTaskCapacity() throws Exception {
        AtomicInteger ownerStarts = new AtomicInteger();
        ThreadFactory factory = task -> {
            ownerStarts.incrementAndGet();
            return Threading.unstartedPlatformNonInheriting("test-bounded-report-", task);
        };
        PoolLifecycleDispatcher dispatcher =
                new PoolLifecycleDispatcher(1, 3, factory, PoolLifecycleDispatcher.Saturation.BLOCK);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(4);
        ExecutorService blockedSubmitter = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < 3; index++) {
                dispatcher.execute(() -> {
                    firstStarted.countDown();
                    await(releaseTasks);
                    allFinished.countDown();
                });
            }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            Future<?> blocked = blockedSubmitter.submit(() -> dispatcher.execute(allFinished::countDown));
            assertThrows(TimeoutException.class, () -> blocked.get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, ownerStarts.get());

            releaseTasks.countDown();
            blocked.get(1, TimeUnit.SECONDS);
            assertTrue(allFinished.await(1, TimeUnit.SECONDS));
        } finally {
            releaseTasks.countDown();
            blockedSubmitter.shutdownNow();
            assertTrue(blockedSubmitter.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static PoolLifecycleDispatcher dispatcher(int parallelism) {
        return dispatcher(
                parallelism,
                Math.max(16, parallelism * 8),
                PoolLifecycleDispatcher.Saturation.BLOCK,
                "test-lifecycle-");
    }

    private static PoolLifecycleDispatcher dispatcher(
            int parallelism, int capacity, PoolLifecycleDispatcher.Saturation saturation, String threadPrefix) {
        AtomicInteger sequence = new AtomicInteger();
        return new PoolLifecycleDispatcher(
                parallelism,
                capacity,
                task -> Threading.unstartedPlatformNonInheriting(threadPrefix + sequence.getAndIncrement(), task),
                saturation);
    }

    private static void assertStarterFailure(Throwable expected) throws Exception {
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch firstOwnerExited = new CountDownLatch(1);
        ThreadFactory factory = task -> {
            if (launches.incrementAndGet() == 2) {
                return throwUnchecked(expected);
            }
            return Threading.unstartedPlatformNonInheriting("test-launch-", () -> {
                try {
                    task.run();
                } finally {
                    firstOwnerExited.countDown();
                }
            });
        };

        Throwable observed = assertThrows(
                expected.getClass(),
                () -> new PoolLifecycleDispatcher(2, 16, factory, PoolLifecycleDispatcher.Saturation.BLOCK));

        assertSame(expected, observed);
        assertTrue(firstOwnerExited.await(1, TimeUnit.SECONDS));
        assertEquals(2, launches.get());
    }

    private static <T> T throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("unsupported test failure", failure);
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

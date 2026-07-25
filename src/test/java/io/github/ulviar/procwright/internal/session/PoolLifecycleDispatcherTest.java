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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PoolLifecycleDispatcherTest {

    @Test
    void blockedOwnerDoesNotBlockIndependentTask() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstFinished = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        dispatcher.dispatch(() -> {
            firstStarted.countDown();
            awaitIgnoringInterrupt(releaseFirst);
            firstFinished.countDown();
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        dispatcher.dispatch(secondFinished::countDown);

        assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
        releaseFirst.countDown();
        assertTrue(firstFinished.await(1, TimeUnit.SECONDS));
    }

    @Test
    void multipleMandatoryTasksQueueBehindBusyOwnerAndRunExactlyOnce() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(1);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(4);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        dispatcher.dispatch(() -> {
            ownerStarted.countDown();
            awaitIgnoringInterrupt(releaseOwner);
        });
        assertTrue(ownerStarted.await(1, TimeUnit.SECONDS));

        for (int index = 0; index < 4; index++) {
            int taskId = index;
            dispatcher.dispatch(() -> {
                order.add(taskId);
                completed.countDown();
            });
        }

        assertEquals(List.of(), order);
        releaseOwner.countDown();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(0, 1, 2, 3), order);
    }

    @Test
    void taskErrorDoesNotReduceBoundedOwnerCapacity() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(1);
        AssertionError failure = new AssertionError("mandatory task failed");
        CountDownLatch failedTaskRan = new CountDownLatch(1);
        CountDownLatch followingRan = new CountDownLatch(1);

        dispatcher.dispatch(() -> {
            failedTaskRan.countDown();
            throw failure;
        });
        dispatcher.dispatch(followingRan::countDown);

        assertTrue(failedTaskRan.await(1, TimeUnit.SECONDS));
        assertTrue(followingRan.await(1, TimeUnit.SECONDS));
    }

    @Test
    void ownerCanSynchronouslyDispatchIntoItsOwnDomain() throws Exception {
        PoolLifecycleDispatcher dispatcher =
                new PoolLifecycleDispatcher(1, Threading::start, "test-reentrant-dispatch-", 1);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);

        dispatcher.dispatch(() -> {
            dispatcher.dispatch(runs::incrementAndGet);
            runs.incrementAndGet();
            finished.countDown();
        });

        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertEquals(2, runs.get());
    }

    @Test
    void retirementBatchQueuesWithoutTaskPermit() throws Exception {
        PoolLifecycleDispatcher dispatcher =
                new PoolLifecycleDispatcher(1, Threading::start, "test-permit-free-batch-", 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        dispatcher.dispatch(() -> {
            blockerStarted.countDown();
            awaitIgnoringInterrupt(releaseBlocker);
        });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

        dispatcher.dispatchRetirementBatch(completed::countDown);

        releaseBlocker.countDown();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void runtimeStarterFailureFailsConstructionBeforeTaskAcceptance() throws Exception {
        assertStarterFailure(new IllegalStateException("owner launch failed"));
    }

    @Test
    void errorStarterFailureFailsConstructionBeforeTaskAcceptance() throws Exception {
        assertStarterFailure(new AssertionError("owner launch failed fatally"));
    }

    @Test
    void sharedReportAndRetirementUseIndependentOwners() throws Exception {
        CountDownLatch publicationStarted = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch publicationFinished = new CountDownLatch(1);
        CountDownLatch retirementFinished = new CountDownLatch(1);
        PoolLifecycleDispatcher.report(() -> {
            publicationStarted.countDown();
            awaitIgnoringInterrupt(releasePublication);
            publicationFinished.countDown();
        });
        assertTrue(publicationStarted.await(1, TimeUnit.SECONDS));

        PoolLifecycleDispatcher.executeRetirementBatch(retirementFinished::countDown);

        assertTrue(retirementFinished.await(1, TimeUnit.SECONDS));
        releasePublication.countDown();
        assertTrue(publicationFinished.await(1, TimeUnit.SECONDS));
    }

    @Test
    void multipleReportsQueueBehindAllBusyOwnersAndRunExactlyOnce() throws Exception {
        int parallelism = 8;
        CountDownLatch ownersStarted = new CountDownLatch(parallelism);
        CountDownLatch releaseOwners = new CountDownLatch(1);
        CountDownLatch blockersFinished = new CountDownLatch(parallelism);
        CountDownLatch publicationsFinished = new CountDownLatch(2);
        AtomicInteger publications = new AtomicInteger();
        for (int index = 0; index < parallelism; index++) {
            PoolLifecycleDispatcher.report(() -> {
                ownersStarted.countDown();
                awaitIgnoringInterrupt(releaseOwners);
                blockersFinished.countDown();
            });
        }
        try {
            assertTrue(ownersStarted.await(1, TimeUnit.SECONDS));
            PoolLifecycleDispatcher.report(() -> {
                publications.incrementAndGet();
                publicationsFinished.countDown();
            });
            PoolLifecycleDispatcher.report(() -> {
                publications.incrementAndGet();
                publicationsFinished.countDown();
            });

            releaseOwners.countDown();

            assertTrue(blockersFinished.await(1, TimeUnit.SECONDS));
            assertTrue(publicationsFinished.await(1, TimeUnit.SECONDS));
            assertEquals(2, publications.get());
        } finally {
            releaseOwners.countDown();
        }
    }

    @Test
    void taskPermitsBoundQueueAndBackpressureArbitraryAttempts() throws Exception {
        AtomicInteger ownerStarts = new AtomicInteger();
        PoolLifecycleDispatcher dispatcher = new PoolLifecycleDispatcher(
                1,
                (prefix, task) -> {
                    ownerStarts.incrementAndGet();
                    return Threading.start(prefix, task);
                },
                "test-bounded-retirement-",
                3);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(4);
        AtomicInteger taskRuns = new AtomicInteger();
        ExecutorService blockedSubmitter = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < 3; index++) {
                dispatcher.dispatch(() -> {
                    taskRuns.incrementAndGet();
                    firstStarted.countDown();
                    awaitIgnoringInterrupt(releaseTasks);
                    allFinished.countDown();
                });
            }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            Future<?> blocked = blockedSubmitter.submit(() -> dispatcher.dispatch(() -> {
                taskRuns.incrementAndGet();
                allFinished.countDown();
            }));
            assertThrows(TimeoutException.class, () -> blocked.get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, ownerStarts.get(), "task-permit pressure must not create fallback owner threads");

            releaseTasks.countDown();
            blocked.get(1, TimeUnit.SECONDS);
            assertTrue(allFinished.await(1, TimeUnit.SECONDS));
            assertEquals(4, taskRuns.get(), "every accepted cleanup must run exactly once");
        } finally {
            releaseTasks.countDown();
            blockedSubmitter.shutdownNow();
            assertTrue(blockedSubmitter.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static PoolLifecycleDispatcher dispatcher(int parallelism) {
        return new PoolLifecycleDispatcher(parallelism, Threading::start, "test-lifecycle-");
    }

    private static void assertStarterFailure(Throwable expected) throws Exception {
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch firstOwnerExited = new CountDownLatch(1);
        PoolLifecycleDispatcher.TaskStarter starter = (prefix, task) -> {
            if (launches.incrementAndGet() == 2) {
                return throwUnchecked(expected);
            }
            return Threading.start(prefix, () -> {
                try {
                    task.run();
                } finally {
                    firstOwnerExited.countDown();
                }
            });
        };

        Throwable observed =
                assertThrows(expected.getClass(), () -> new PoolLifecycleDispatcher(2, starter, "test-launch-"));

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

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
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

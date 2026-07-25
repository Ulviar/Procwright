/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.Threading;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PoolLifecycleDispatcherTest {

    @Test
    void blockedOwnerDoesNotBlockIndependentTask() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        PoolLifecycleDispatcher.Ownership first = dispatcher.dispatch(() -> {
            firstStarted.countDown();
            awaitIgnoringInterrupt(releaseFirst);
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        PoolLifecycleDispatcher.Ownership second = dispatcher.dispatch(secondFinished::countDown);

        assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
        second.completion().get(1, TimeUnit.SECONDS);
        releaseFirst.countDown();
        first.completion().get(1, TimeUnit.SECONDS);
        dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
    }

    @Test
    void multipleMandatoryTasksQueueBehindBusyOwnerAndRunExactlyOnce() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(1);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        List<PoolLifecycleDispatcher.Ownership> queued = new ArrayList<>();
        PoolLifecycleDispatcher.Ownership owner = dispatcher.dispatch(() -> {
            ownerStarted.countDown();
            awaitIgnoringInterrupt(releaseOwner);
        });
        assertTrue(ownerStarted.await(1, TimeUnit.SECONDS));

        for (int index = 0; index < 4; index++) {
            int taskId = index;
            queued.add(dispatcher.dispatch(() -> order.add(taskId)));
        }

        assertEquals(List.of(), order);
        queued.forEach(ownership -> assertFalse(ownership.started().isDone()));
        releaseOwner.countDown();
        owner.completion().get(1, TimeUnit.SECONDS);
        for (PoolLifecycleDispatcher.Ownership ownership : queued) {
            ownership.completion().get(1, TimeUnit.SECONDS);
        }
        dispatcher.whenIdle().get(1, TimeUnit.SECONDS);

        assertEquals(List.of(0, 1, 2, 3), order);
    }

    @Test
    void taskErrorDoesNotReduceBoundedOwnerCapacity() throws Exception {
        PoolLifecycleDispatcher dispatcher = dispatcher(1);
        AssertionError failure = new AssertionError("mandatory task failed");
        PoolLifecycleDispatcher.Ownership failed = dispatcher.dispatch(() -> {
            throw failure;
        });
        PoolLifecycleDispatcher.Ownership following = dispatcher.dispatch(() -> {});

        assertSame(failure, exceptionalCause(failed.completion()));
        following.completion().get(1, TimeUnit.SECONDS);
        dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
    }

    @Test
    void ownerCanSynchronouslyDispatchIntoItsOwnDomain() throws Exception {
        PoolLifecycleDispatcher dispatcher =
                new PoolLifecycleDispatcher(new BoundedTaskLimiter(1), Threading::start, "test-reentrant-dispatch-", 1);
        AtomicInteger runs = new AtomicInteger();

        PoolLifecycleDispatcher.Ownership outer = dispatcher.dispatch(() -> {
            PoolLifecycleDispatcher.Ownership inner = dispatcher.dispatch(runs::incrementAndGet);
            inner.completion().join();
            runs.incrementAndGet();
        });

        outer.completion().get(1, TimeUnit.SECONDS);
        dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
        assertEquals(2, runs.get());
        assertEquals(1, dispatcher.availableAdmissions());
    }

    @Test
    void retirementBatchDoesNotNeedAnAdmissionHeldByTheRetiringWorker() throws Exception {
        PoolLifecycleDispatcher dispatcher = new PoolLifecycleDispatcher(
                new BoundedTaskLimiter(1), Threading::start, "test-admission-free-batch-", 1);
        PoolLifecycleDispatcher.Admission workerAdmission = dispatcher.tryAdmit();
        assertTrue(workerAdmission != null);
        CountDownLatch completed = new CountDownLatch(1);
        try {
            assertEquals(0, dispatcher.availableAdmissions());

            PoolLifecycleDispatcher.Ownership retirement = dispatcher.dispatchRetirementBatch(completed::countDown);

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            retirement.completion().get(1, TimeUnit.SECONDS);
        } finally {
            workerAdmission.close();
        }
        assertEquals(1, dispatcher.availableAdmissions());
    }

    @Test
    void runtimeStarterFailureFailsConstructionBeforeAdmission() throws Exception {
        assertStarterFailure(new IllegalStateException("owner launch failed"));
    }

    @Test
    void errorStarterFailureFailsConstructionBeforeAdmission() throws Exception {
        assertStarterFailure(new AssertionError("owner launch failed fatally"));
    }

    @Test
    void workerCloseRuntimeStarterFailurePreservesAdmissionAndExactOutcome() throws Exception {
        assertWorkerCloseStarterFailure(new IllegalStateException("worker close owner launch failed"));
    }

    @Test
    void workerCloseErrorStarterFailurePreservesAdmissionAndExactOutcome() throws Exception {
        assertWorkerCloseStarterFailure(new AssertionError("worker close owner launch failed fatally"));
    }

    @Test
    void sharedReportAndRetirementUseIndependentOwners() throws Exception {
        CountDownLatch publicationStarted = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch retirementFinished = new CountDownLatch(1);
        PoolLifecycleDispatcher.Ownership publication = PoolLifecycleDispatcher.report(() -> {
            publicationStarted.countDown();
            awaitIgnoringInterrupt(releasePublication);
        });
        assertTrue(publicationStarted.await(1, TimeUnit.SECONDS));

        PoolLifecycleDispatcher.Ownership retirement = PoolLifecycleDispatcher.execute(retirementFinished::countDown);

        assertTrue(retirementFinished.await(1, TimeUnit.SECONDS));
        retirement.completion().get(1, TimeUnit.SECONDS);
        releasePublication.countDown();
        publication.completion().get(1, TimeUnit.SECONDS);
        PoolLifecycleDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
    }

    @Test
    void multipleReportsQueueBehindAllBusyOwnersAndRunExactlyOnce() throws Exception {
        int parallelism = 8;
        CountDownLatch ownersStarted = new CountDownLatch(parallelism);
        CountDownLatch releaseOwners = new CountDownLatch(1);
        List<PoolLifecycleDispatcher.Ownership> blockers = new ArrayList<>();
        AtomicInteger publications = new AtomicInteger();
        for (int index = 0; index < parallelism; index++) {
            blockers.add(PoolLifecycleDispatcher.report(() -> {
                ownersStarted.countDown();
                awaitIgnoringInterrupt(releaseOwners);
            }));
        }
        try {
            assertTrue(ownersStarted.await(1, TimeUnit.SECONDS));
            PoolLifecycleDispatcher.Ownership first = PoolLifecycleDispatcher.report(publications::incrementAndGet);
            PoolLifecycleDispatcher.Ownership second = PoolLifecycleDispatcher.report(publications::incrementAndGet);

            assertFalse(first.started().isDone());
            assertFalse(second.started().isDone());
            releaseOwners.countDown();

            first.completion().get(1, TimeUnit.SECONDS);
            second.completion().get(1, TimeUnit.SECONDS);
            for (PoolLifecycleDispatcher.Ownership blocker : blockers) {
                blocker.completion().get(1, TimeUnit.SECONDS);
            }
            PoolLifecycleDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
            assertEquals(2, publications.get());
        } finally {
            releaseOwners.countDown();
        }
    }

    @Test
    void admittedNonCooperativeTasksBoundQueueAndBackpressureArbitraryAttempts() throws Exception {
        AtomicInteger ownerStarts = new AtomicInteger();
        PoolLifecycleDispatcher dispatcher = new PoolLifecycleDispatcher(
                new BoundedTaskLimiter(1),
                (prefix, task) -> {
                    ownerStarts.incrementAndGet();
                    return Threading.start(prefix, task);
                },
                "test-bounded-retirement-",
                3);
        CountDownLatch tasksStarted = new CountDownLatch(1);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        AtomicInteger taskRuns = new AtomicInteger();
        List<PoolLifecycleDispatcher.Admission> admissions = new ArrayList<>();
        List<PoolLifecycleDispatcher.Ownership> ownerships = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                PoolLifecycleDispatcher.Admission admission = dispatcher.tryAdmit();
                assertTrue(admission != null);
                admissions.add(admission);
                ownerships.add(dispatcher.dispatch(admission, () -> {
                    taskRuns.incrementAndGet();
                    tasksStarted.countDown();
                    awaitIgnoringInterrupt(releaseTasks);
                }));
            }
            assertTrue(tasksStarted.await(1, TimeUnit.SECONDS));

            for (int attempt = 0; attempt < 1_000; attempt++) {
                assertNull(dispatcher.tryAdmit(), "non-cooperative owners must backpressure later resources");
            }
            assertEquals(1, ownerStarts.get(), "admission pressure must not create fallback owner threads");

            releaseTasks.countDown();
            for (PoolLifecycleDispatcher.Ownership ownership : ownerships) {
                ownership.completion().get(1, TimeUnit.SECONDS);
            }
            assertEquals(3, taskRuns.get(), "every admitted cleanup must run exactly once");
        } finally {
            releaseTasks.countDown();
            admissions.forEach(PoolLifecycleDispatcher.Admission::close);
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
        }
        assertEquals(3, dispatcher.availableAdmissions());
    }

    private static PoolLifecycleDispatcher dispatcher(int parallelism) {
        return new PoolLifecycleDispatcher(
                new BoundedTaskLimiter(parallelism), Threading::start, "test-terminal-retirement-");
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

        Throwable observed = assertThrows(
                expected.getClass(),
                () -> new PoolLifecycleDispatcher(new BoundedTaskLimiter(2), starter, "test-launch-"));

        assertSame(expected, observed);
        assertTrue(firstOwnerExited.await(1, TimeUnit.SECONDS));
        assertEquals(2, launches.get());
    }

    private static void assertWorkerCloseStarterFailure(Throwable expected) throws Exception {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        PoolLifecycleDispatcher.Admission admission = admissions.tryAcquire();
        AtomicInteger taskRuns = new AtomicInteger();

        CompletableFuture<WorkerRetirement.Outcome> retirement = WorkerCloseSupport.closeOutcome(
                taskRuns::incrementAndGet,
                java.util.concurrent.CompletableFuture.completedFuture(null),
                java.util.concurrent.CompletableFuture.completedFuture(null),
                admission,
                (ownedAdmission, task) -> PoolLifecycleDispatcher.executeWorkerClose(
                        ownedAdmission, task, (prefix, owner) -> throwUnchecked(expected)));

        WorkerRetirement.Outcome outcome = retirement.get(1, TimeUnit.SECONDS);
        assertSame(expected, outcome.failure());
        assertEquals(0, taskRuns.get());
        assertEquals(0, admissions.availablePermits(), "the worker still owns its admitted close after failure");

        admission.close();
        admission.close();
        assertEquals(1, admissions.availablePermits(), "dispatch rollback must allow exactly one admission return");
    }

    private static Throwable exceptionalCause(java.util.concurrent.Future<?> future) throws Exception {
        return assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS))
                .getCause();
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

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PoolRetirementDispatcherTest {

    @Test
    void blockedOwnerDoesNotBlockIndependentTask() throws Exception {
        PoolRetirementDispatcher dispatcher = dispatcher(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        PoolRetirementDispatcher.Ownership first = dispatcher.dispatch(() -> {
            firstStarted.countDown();
            awaitIgnoringInterrupt(releaseFirst);
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        PoolRetirementDispatcher.Ownership second = dispatcher.dispatch(secondFinished::countDown);

        assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
        second.completion().get(1, TimeUnit.SECONDS);
        releaseFirst.countDown();
        first.completion().get(1, TimeUnit.SECONDS);
        dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
    }

    @Test
    void multipleMandatoryTasksQueueBehindBusyOwnerAndRunExactlyOnce() throws Exception {
        PoolRetirementDispatcher dispatcher = dispatcher(1);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        List<PoolRetirementDispatcher.Ownership> queued = new ArrayList<>();
        PoolRetirementDispatcher.Ownership owner = dispatcher.dispatch(() -> {
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
        for (PoolRetirementDispatcher.Ownership ownership : queued) {
            ownership.completion().get(1, TimeUnit.SECONDS);
        }
        dispatcher.whenIdle().get(1, TimeUnit.SECONDS);

        assertEquals(List.of(0, 1, 2, 3), order);
    }

    @Test
    void taskErrorDoesNotReduceBoundedOwnerCapacity() throws Exception {
        PoolRetirementDispatcher dispatcher = dispatcher(1);
        AssertionError failure = new AssertionError("mandatory task failed");
        PoolRetirementDispatcher.Ownership failed = dispatcher.dispatch(() -> {
            throw failure;
        });
        PoolRetirementDispatcher.Ownership following = dispatcher.dispatch(() -> {});

        assertSame(failure, exceptionalCause(failed.completion()));
        following.completion().get(1, TimeUnit.SECONDS);
        dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
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
        PoolRetirementDispatcher.Ownership publication = PoolRetirementDispatcher.report(() -> {
            publicationStarted.countDown();
            awaitIgnoringInterrupt(releasePublication);
        });
        assertTrue(publicationStarted.await(1, TimeUnit.SECONDS));

        PoolRetirementDispatcher.Ownership retirement = PoolRetirementDispatcher.execute(retirementFinished::countDown);

        assertTrue(retirementFinished.await(1, TimeUnit.SECONDS));
        retirement.completion().get(1, TimeUnit.SECONDS);
        releasePublication.countDown();
        publication.completion().get(1, TimeUnit.SECONDS);
        PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
    }

    @Test
    void multipleReportsQueueBehindAllBusyOwnersAndRunExactlyOnce() throws Exception {
        int parallelism = 8;
        CountDownLatch ownersStarted = new CountDownLatch(parallelism);
        CountDownLatch releaseOwners = new CountDownLatch(1);
        List<PoolRetirementDispatcher.Ownership> blockers = new ArrayList<>();
        AtomicInteger publications = new AtomicInteger();
        for (int index = 0; index < parallelism; index++) {
            blockers.add(PoolRetirementDispatcher.report(() -> {
                ownersStarted.countDown();
                awaitIgnoringInterrupt(releaseOwners);
            }));
        }
        try {
            assertTrue(ownersStarted.await(1, TimeUnit.SECONDS));
            PoolRetirementDispatcher.Ownership first = PoolRetirementDispatcher.report(publications::incrementAndGet);
            PoolRetirementDispatcher.Ownership second = PoolRetirementDispatcher.report(publications::incrementAndGet);

            assertFalse(first.started().isDone());
            assertFalse(second.started().isDone());
            releaseOwners.countDown();

            first.completion().get(1, TimeUnit.SECONDS);
            second.completion().get(1, TimeUnit.SECONDS);
            for (PoolRetirementDispatcher.Ownership blocker : blockers) {
                blocker.completion().get(1, TimeUnit.SECONDS);
            }
            PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
            assertEquals(2, publications.get());
        } finally {
            releaseOwners.countDown();
        }
    }

    @Test
    void admittedNonCooperativeTasksBoundQueueAndBackpressureArbitraryAttempts() throws Exception {
        AtomicInteger ownerStarts = new AtomicInteger();
        PoolRetirementDispatcher dispatcher = new PoolRetirementDispatcher(
                new BoundedTaskRunner.Limiter(1),
                (prefix, task) -> {
                    ownerStarts.incrementAndGet();
                    return Threading.start(prefix, task);
                },
                "test-bounded-retirement-",
                3);
        CountDownLatch tasksStarted = new CountDownLatch(1);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        AtomicInteger taskRuns = new AtomicInteger();
        List<PoolRetirementDispatcher.Admission> admissions = new ArrayList<>();
        List<PoolRetirementDispatcher.Ownership> ownerships = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                PoolRetirementDispatcher.Admission admission = dispatcher.tryAdmit();
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
            for (PoolRetirementDispatcher.Ownership ownership : ownerships) {
                ownership.completion().get(1, TimeUnit.SECONDS);
            }
            assertEquals(3, taskRuns.get(), "every admitted cleanup must run exactly once");
        } finally {
            releaseTasks.countDown();
            admissions.forEach(PoolRetirementDispatcher.Admission::close);
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
        }
        assertEquals(3, dispatcher.availableAdmissions());
    }

    private static PoolRetirementDispatcher dispatcher(int parallelism) {
        return new PoolRetirementDispatcher(
                new BoundedTaskRunner.Limiter(parallelism), Threading::start, "test-terminal-retirement-");
    }

    private static void assertStarterFailure(Throwable expected) throws Exception {
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch firstOwnerExited = new CountDownLatch(1);
        PoolRetirementDispatcher.TaskStarter starter = (prefix, task) -> {
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
                () -> new PoolRetirementDispatcher(new BoundedTaskRunner.Limiter(2), starter, "test-launch-"));

        assertSame(expected, observed);
        assertTrue(firstOwnerExited.await(1, TimeUnit.SECONDS));
        assertEquals(2, launches.get());
    }

    private static void assertWorkerCloseStarterFailure(Throwable expected) throws Exception {
        PoolRetirementDispatcher.AdmissionPool admissions = new PoolRetirementDispatcher.AdmissionPool(1);
        PoolRetirementDispatcher.Admission admission = admissions.tryAcquire();
        AtomicInteger taskRuns = new AtomicInteger();

        WorkerPoolController.CloseObservation observation = WorkerCloseSupport.initiateCloseAndObserve(
                taskRuns::incrementAndGet,
                java.util.concurrent.CompletableFuture.completedFuture(null),
                java.util.concurrent.CompletableFuture.completedFuture(null),
                admission,
                (ownedAdmission, task) -> PoolRetirementDispatcher.executeWorkerClose(
                        ownedAdmission, task, (prefix, owner) -> throwUnchecked(expected)));

        WorkerPoolController.CloseOutcome outcome = observation.outcome().get(1, TimeUnit.SECONDS);
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

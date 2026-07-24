/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerRetirementCoordinatorTest {

    @Test
    void batchInitiatesEveryCloseBeforeObservingAnyOutcome() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(2);
        AtomicInteger initiated = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (worker, outcome) -> {
                    assertEquals(2, initiated.get());
                    worker.releaseRetirementAdmission();
                    completed.incrementAndGet();
                    return null;
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        WorkerRetirementCoordinator.Batch<String> batch = coordinator.newBatch();
        batch.add(worker(admissions, initiated));
        batch.add(worker(admissions, initiated));

        batch.dispatch();
        batch.dispatch();

        assertEquals(2, completed.get());
        assertEquals(2, admissions.availablePermits());
    }

    @Test
    void dispatcherFailureFallsBackInlineWithoutAbandoningRetirements() {
        IllegalStateException dispatchFailure = new IllegalStateException("dispatcher unavailable");
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        AtomicInteger initiated = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                task -> {
                    throw dispatchFailure;
                },
                (worker, outcome) -> {
                    worker.releaseRetirementAdmission();
                    completed.incrementAndGet();
                    return null;
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        WorkerRetirementCoordinator.Batch<String> batch = coordinator.newBatch();
        batch.add(worker(admissions, initiated));

        IllegalStateException observed = assertThrows(IllegalStateException.class, batch::dispatch);

        assertSame(dispatchFailure, observed);
        assertEquals(1, initiated.get());
        assertEquals(1, completed.get());
        assertEquals(1, admissions.availablePermits());
    }

    @Test
    void exceptionalCloseFutureIsDeliveredAsNormalizedOutcome() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        PoolLifecycleDispatcher.Admission admission = admissions.tryAcquire();
        PoolWorker<String> worker = new PoolWorker<>();
        CompletableFuture<WorkerRetirement.Outcome> outcome = new CompletableFuture<>();
        worker.retirementAdmission(admission);
        worker.accept("worker", (session, ignored) -> () -> outcome);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (completedWorker, completedOutcome) -> {
                    assertSame(worker, completedWorker);
                    completedWorker.releaseRetirementAdmission();
                    observed.set(completedOutcome.failure());
                    return null;
                },
                (failedWorker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        WorkerRetirementCoordinator.Batch<String> batch = coordinator.newBatch();
        batch.add(worker);
        IllegalStateException failure = new IllegalStateException("close observation failed");

        batch.dispatch();
        outcome.completeExceptionally(failure);

        assertSame(failure, observed.get());
        assertEquals(1, admissions.availablePermits());
    }

    private static PoolWorker<String> worker(
            PoolLifecycleDispatcher.AdmissionPool admissions, AtomicInteger initiated) {
        PoolLifecycleDispatcher.Admission admission = admissions.tryAcquire();
        PoolWorker<String> worker = new PoolWorker<>();
        worker.retirementAdmission(admission);
        worker.accept("worker", (session, ignored) -> {
            initiated.incrementAndGet();
            return () -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
        });
        return worker;
    }
}

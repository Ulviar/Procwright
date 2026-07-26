/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerRetirementCoordinatorTest {

    @Test
    void dispatchInitiatesEveryCloseBeforeSchedulingOutcomeProcessing() {
        AtomicInteger initiated = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<Runnable> outcomeProcessing = new AtomicReference<>();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                outcomeProcessing::set,
                (worker, outcome) -> {
                    completed.incrementAndGet();
                    return null;
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        PoolWorker<String> first = worker(initiated);
        PoolWorker<String> second = worker(initiated);

        coordinator.dispatch(List.of(first, second));

        assertEquals(2, initiated.get());
        assertEquals(0, completed.get());

        outcomeProcessing.get().run();

        assertEquals(2, completed.get());
    }

    @Test
    void blockedStarterFallbackDoesNotDelayAnotherWorkerClose() throws Exception {
        IllegalStateException starterFailure = new IllegalStateException("worker close owner unavailable");
        CountDownLatch firstCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstClose = new CountDownLatch(1);
        CountDownLatch secondCloseStarted = new CountDownLatch(1);
        CountDownLatch outcomesProcessed = new CountDownLatch(2);
        WorkerRetirement.Action<String> closeAction = session -> WorkerCloseSupport.closeOutcome(
                () -> {
                    if (session.equals("first")) {
                        firstCloseStarted.countDown();
                        try {
                            releaseFirstClose.await();
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        secondCloseStarted.countDown();
                    }
                },
                CompletableFuture.completedFuture(null),
                (prefix, task) -> {
                    throw starterFailure;
                });
        PoolWorker<String> first = worker("first", closeAction);
        PoolWorker<String> second = worker("second", closeAction);
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (worker, outcome) -> {
                    outcomesProcessed.countDown();
                    return null;
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});

        try {
            coordinator.dispatch(List.of(first, second));

            assertTrue(firstCloseStarted.await(1, TimeUnit.SECONDS));
            assertTrue(secondCloseStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirstClose.countDown();
        }
        assertTrue(outcomesProcessed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void batchInitiatesEveryCloseBeforeObservingAnyOutcome() {
        AtomicInteger initiated = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger reported = new AtomicInteger();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (worker, outcome) -> {
                    assertEquals(2, initiated.get());
                    completed.incrementAndGet();
                    return new FailureReport(
                            BoundedFailureReporter.captureFailureTarget(),
                            new IllegalStateException("retirement report"));
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {
                    assertEquals(2, completed.get());
                    reported.incrementAndGet();
                });
        coordinator.dispatch(List.of(worker(initiated), worker(initiated)));

        assertEquals(2, completed.get());
        assertEquals(2, reported.get());
    }

    @Test
    void dispatcherFailureFallsBackInlineWithoutAbandoningRetirements() {
        IllegalStateException dispatchFailure = new IllegalStateException("dispatcher unavailable");
        AtomicInteger initiated = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                task -> {
                    throw dispatchFailure;
                },
                (worker, outcome) -> {
                    completed.incrementAndGet();
                    return null;
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> coordinator.dispatch(List.of(worker(initiated))));

        assertSame(dispatchFailure, observed);
        assertEquals(1, initiated.get());
        assertEquals(1, completed.get());
    }

    @Test
    void dispatcherFailureAfterInlineExecutionDoesNotRepeatOutcomeProcessing() {
        IllegalStateException dispatchFailure = new IllegalStateException("dispatcher failed after execution");
        AtomicInteger initiated = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                task -> {
                    task.run();
                    throw dispatchFailure;
                },
                (worker, outcome) -> {
                    completed.incrementAndGet();
                    return null;
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> coordinator.dispatch(List.of(worker(initiated))));

        assertSame(dispatchFailure, observed);
        assertEquals(1, initiated.get());
        assertEquals(1, completed.get());
    }

    @Test
    void exceptionalCloseFutureIsDeliveredAsNormalizedOutcome() {
        CompletableFuture<WorkerRetirement.Outcome> outcome = new CompletableFuture<>();
        PoolWorker<String> worker = new PoolWorker<>(session -> outcome, PoolWorker.StartupPurpose.DEMAND);
        worker.accept("worker", 0);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (completedWorker, completedOutcome) -> {
                    assertSame(worker, completedWorker);
                    observed.set(completedOutcome.failure());
                    return null;
                },
                (failedWorker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        IllegalStateException failure = new IllegalStateException("close completion failed");

        coordinator.dispatch(List.of(worker));
        outcome.completeExceptionally(failure);

        assertSame(failure, observed.get());
    }

    @Test
    void nullCloseOutcomeIsNormalized() {
        PoolWorker<String> worker =
                new PoolWorker<>(session -> CompletableFuture.completedFuture(null), PoolWorker.StartupPurpose.DEMAND);
        worker.accept("worker", 0);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (completedWorker, completedOutcome) -> {
                    observed.set(completedOutcome.failure());
                    return null;
                },
                (failedWorker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        coordinator.dispatch(List.of(worker));

        assertEquals("worker close future returned null", observed.get().getMessage());
    }

    private static PoolWorker<String> worker(AtomicInteger initiated) {
        PoolWorker<String> worker = new PoolWorker<>(
                session -> {
                    initiated.incrementAndGet();
                    return CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
                },
                PoolWorker.StartupPurpose.DEMAND);
        worker.accept("worker", 0);
        return worker;
    }

    private static PoolWorker<String> worker(String session, WorkerRetirement.Action<String> closeAction) {
        PoolWorker<String> worker = new PoolWorker<>(closeAction, PoolWorker.StartupPurpose.DEMAND);
        worker.accept(session, 0);
        return worker;
    }
}

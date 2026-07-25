/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.time.Duration;
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
        AtomicInteger reported = new AtomicInteger();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (worker, outcome) -> {
                    assertEquals(2, initiated.get());
                    releaseAdmission(worker);
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
        PoolStateEffects<String> effects = effects(coordinator);
        effects.retire(worker(admissions, initiated));
        effects.retire(worker(admissions, initiated));

        effects.close();
        effects.close();

        assertEquals(2, completed.get());
        assertEquals(2, reported.get());
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
                    releaseAdmission(worker);
                    completed.incrementAndGet();
                    return null;
                },
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        PoolStateEffects<String> effects = effects(coordinator);
        effects.retire(worker(admissions, initiated));

        IllegalStateException observed = assertThrows(IllegalStateException.class, effects::close);

        assertSame(dispatchFailure, observed);
        assertEquals(1, initiated.get());
        assertEquals(1, completed.get());
        assertEquals(1, admissions.availablePermits());
    }

    @Test
    void exceptionalCloseFutureIsDeliveredAsNormalizedOutcome() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        PoolLifecycleDispatcher.Admission admission = admissions.tryAcquire();
        CompletableFuture<WorkerRetirement.Outcome> outcome = new CompletableFuture<>();
        PoolWorker<String> worker = new PoolWorker<>((session, ignored) -> () -> outcome);
        worker.retirementAdmission(admission);
        worker.accept("worker");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                Runnable::run,
                (completedWorker, completedOutcome) -> {
                    assertSame(worker, completedWorker);
                    releaseAdmission(completedWorker);
                    observed.set(completedOutcome.failure());
                    return null;
                },
                (failedWorker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
        PoolStateEffects<String> effects = effects(coordinator);
        effects.retire(worker);
        IllegalStateException failure = new IllegalStateException("close observation failed");

        effects.close();
        outcome.completeExceptionally(failure);

        assertSame(failure, observed.get());
        assertEquals(1, admissions.availablePermits());
    }

    @Test
    void effectFailureDoesNotSkipAdmissionReleaseOrTerminalPublication() throws Exception {
        IllegalStateException dispatchFailure = new IllegalStateException("dispatcher unavailable");
        WorkerPoolState<String> state = state();
        WorkerRetirementCoordinator<String> coordinator = new WorkerRetirementCoordinator<>(
                task -> {
                    throw dispatchFailure;
                },
                (worker, outcome) -> null,
                (worker, failure) -> {},
                report -> {});
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        PoolStateEffects<String> effects = new PoolStateEffects<>(state, coordinator);
        effects.release(admissions.acquireUninterruptibly());
        effects.retire(worker(new PoolLifecycleDispatcher.AdmissionPool(1), new AtomicInteger()));
        state.beginClose(null, effects);

        IllegalStateException observed = assertThrows(IllegalStateException.class, effects::close);

        assertSame(dispatchFailure, observed);
        assertEquals(1, admissions.availablePermits());
        state.terminationView().get();
        assertTrue(state.terminationView().isDone());
    }

    private static PoolWorker<String> worker(
            PoolLifecycleDispatcher.AdmissionPool admissions, AtomicInteger initiated) {
        PoolLifecycleDispatcher.Admission admission = admissions.tryAcquire();
        PoolWorker<String> worker = new PoolWorker<>((session, ignored) -> {
            initiated.incrementAndGet();
            return () -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
        });
        worker.retirementAdmission(admission);
        worker.accept("worker");
        return worker;
    }

    private static void releaseAdmission(PoolWorker<?> worker) {
        PoolLifecycleDispatcher.Admission admission = worker.detachRetirementAdmission();
        if (admission != null) {
            admission.close();
        }
    }

    private static PoolStateEffects<String> effects(WorkerRetirementCoordinator<String> retirements) {
        return new PoolStateEffects<>(state(), retirements);
    }

    private static WorkerPoolState<String> state() {
        return new WorkerPoolState<>(new WorkerPoolPolicy(TestOptions.INSTANCE), new PoolTermination(), () -> {
            throw new AssertionError("unused reservation factory");
        });
    }

    private enum TestOptions implements WorkerPoolPolicy.Options {
        INSTANCE;

        @Override
        public int maxSize() {
            return 1;
        }

        @Override
        public int warmupSize() {
            return 0;
        }

        @Override
        public int minIdle() {
            return 0;
        }

        @Override
        public Duration acquireTimeout() {
            return Duration.ofSeconds(1);
        }

        @Override
        public Duration maxWorkerAge() {
            return Duration.ZERO;
        }

        @Override
        public int maxRequestsPerWorker() {
            return 0;
        }

        @Override
        public boolean backgroundReplenishment() {
            return false;
        }
    }
}

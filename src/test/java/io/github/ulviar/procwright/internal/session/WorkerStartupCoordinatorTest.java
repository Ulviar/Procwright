/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WorkerStartupCoordinatorTest extends WorkerPoolControllerTestSupport {

    @Test
    void successfulStartupReturnsTheCreatedWorker() {
        StartupState state = new StartupState();
        PoolWorker<String> worker = worker(() -> "ready");

        WorkerStartup.CreatedWorker<String> created =
                coordinator(state).start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));

        assertEquals("ready", created.session());
        assertEquals(
                WorkerStartup.TerminalDecision.FACTORY_COMPLETED,
                worker.startup().terminalDecision());
    }

    @Test
    void independentWorkerStartupsDoNotShareAdmissionCapacity() throws Exception {
        int startupCount = 17;
        CountDownLatch factoriesEntered = new CountDownLatch(startupCount);
        CountDownLatch releaseFactories = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(startupCount);
        List<Future<WorkerStartup.CreatedWorker<String>>> startups = new ArrayList<>(startupCount);
        try {
            for (int index = 0; index < startupCount; index++) {
                PoolWorker<String> worker = worker(() -> {
                    factoriesEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactories);
                    return "ready";
                });
                startups.add(callers.submit(() -> coordinator(new StartupState())
                        .start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))));
            }

            assertTrue(factoriesEntered.await(5, TimeUnit.SECONDS));
            releaseFactories.countDown();
            for (Future<WorkerStartup.CreatedWorker<String>> startup : startups) {
                assertEquals("ready", startup.get(1, TimeUnit.SECONDS).session());
            }
        } finally {
            releaseFactories.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void factoryFailureIsMappedAfterPoolStateAccountsForIt() {
        StartupState state = new StartupState();
        IllegalStateException factoryFailure = new IllegalStateException("factory failed");
        PoolWorker<String> worker = worker(
                () -> {
                    throw factoryFailure;
                },
                PoolWorker.StartupPurpose.REPLENISHMENT);

        PoolFailure observed = assertThrows(PoolFailure.class, () -> coordinator(state)
                .start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));

        assertEquals(FailureKind.STARTUP_FAILED, observed.kind);
        assertSame(factoryFailure, observed.getCause());
        assertEquals(1, state.factoryFailures.get());
    }

    @Test
    void launchClaimFailureDoesNotCallFactory() {
        IllegalStateException claimFailure = new IllegalStateException("claim failed");
        StartupState state = new StartupState();
        state.claimFailure = claimFailure;
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });

        IllegalStateException observed = assertThrows(IllegalStateException.class, () -> coordinator(state)
                .start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));

        assertSame(claimFailure, observed);
        assertEquals(0, factoryCalls.get());
        assertEquals(1, state.discards.get());
    }

    @Test
    void postClaimLaunchFailureInvokesPoolCleanup() {
        IllegalStateException launchFailure = new IllegalStateException("thread launch failed");
        AtomicInteger factoryCalls = new AtomicInteger();
        StartupState state = new StartupState();
        PoolWorker<String> worker = new PoolWorker<>(
                session -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success()),
                PoolWorker.StartupPurpose.DEMAND);
        worker.startup(new WorkerStartup<>(
                () -> {
                    factoryCalls.incrementAndGet();
                    return "unexpected";
                },
                "test-startup-coordinator-",
                completion -> {},
                (prefix, task) -> {
                    throw launchFailure;
                }));

        IllegalStateException observed = assertThrows(IllegalStateException.class, () -> coordinator(state)
                .start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));

        assertSame(launchFailure, observed);
        assertEquals(0, factoryCalls.get());
        assertEquals(1, state.discards.get());
    }

    @Test
    void closedLaunchClaimPreventsFactory() {
        StartupState state = new StartupState();
        state.decision = WorkerStartupCoordinator.StartupDecision.CLOSED;
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });

        PoolFailure observed = assertThrows(PoolFailure.class, () -> coordinator(state)
                .start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));

        assertEquals(FailureKind.CLOSED, observed.kind);
        assertEquals(WorkerStartup.TerminalDecision.CLOSED, worker.startup().terminalDecision());
        assertEquals(0, factoryCalls.get());
        assertEquals(1, state.discards.get());
    }

    @Test
    void timedOutLaunchClaimPreventsFactoryAndUsesDemandTimeoutTaxonomy() {
        StartupState state = new StartupState();
        state.decision = WorkerStartupCoordinator.StartupDecision.TIMED_OUT;
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });

        PoolFailure observed = assertThrows(PoolFailure.class, () -> coordinator(state)
                .start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));

        assertEquals(FailureKind.ACQUIRE_TIMEOUT, observed.kind);
        assertEquals(WorkerStartup.TerminalDecision.TIMED_OUT, worker.startup().terminalDecision());
        assertEquals(0, factoryCalls.get());
        assertEquals(1, state.discards.get());
    }

    private static WorkerStartupCoordinator<String> coordinator(StartupState state) {
        return new WorkerStartupCoordinator<>(Failures.INSTANCE, "test worker", state);
    }

    private static PoolWorker<String> worker(java.util.function.Supplier<String> factory) {
        return worker(factory, PoolWorker.StartupPurpose.DEMAND);
    }

    private static PoolWorker<String> worker(
            java.util.function.Supplier<String> factory, PoolWorker.StartupPurpose purpose) {
        PoolWorker<String> worker = new PoolWorker<>(
                session -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success()), purpose);
        worker.startup(new WorkerStartup<>(factory, "test-startup-coordinator-", completion -> {}));
        return worker;
    }

    private static final class StartupState implements WorkerStartupCoordinator.PoolState<String> {

        private final AtomicInteger factoryFailures = new AtomicInteger();
        private final AtomicInteger launchClaims = new AtomicInteger();
        private final AtomicInteger discards = new AtomicInteger();
        private WorkerStartupCoordinator.StartupDecision decision = WorkerStartupCoordinator.StartupDecision.RUN;
        private RuntimeException claimFailure;

        @Override
        public WorkerStartupCoordinator.StartupDecision preflight(PoolWorker<String> worker, long deadlineNanos) {
            launchClaims.incrementAndGet();
            if (claimFailure != null) {
                throw claimFailure;
            }
            if (decision == WorkerStartupCoordinator.StartupDecision.CLOSED) {
                worker.startup().signalClosed();
            } else if (decision == WorkerStartupCoordinator.StartupDecision.TIMED_OUT) {
                worker.startup().signalTimeout();
            }
            return decision;
        }

        @Override
        public boolean factoryFailed(PoolWorker<String> worker, Throwable failure) {
            factoryFailures.incrementAndGet();
            return false;
        }

        @Override
        public void discardStartingWorker(PoolWorker<String> worker) {
            discards.incrementAndGet();
        }
    }
}

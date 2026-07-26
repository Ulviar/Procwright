/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerStartupCoordinatorTest extends WorkerPoolControllerTestSupport {

    @Test
    void successfulStartupReturnsTheCreatedWorkerAndRestoresAdmission() {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        StartupState state = new StartupState();
        PoolWorker<String> worker = worker(() -> "ready");

        WorkerStartup.CreatedWorker<String> created =
                coordinator(state).start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));

        assertEquals("ready", created.session());
        assertEquals(
                WorkerStartup.TerminalDecision.FACTORY_COMPLETED,
                worker.startup().terminalDecision());
        assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
    }

    @Test
    void startupAdmissionTimeoutPreventsFactory() {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        List<BoundedTaskPermit> occupied = occupyStartupPermits(permitsBefore);
        StartupState state = new StartupState();
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });

        try {
            PoolFailure observed = assertThrows(PoolFailure.class, () -> coordinator(state)
                    .start(worker, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25)));

            assertEquals(FailureKind.ACQUIRE_TIMEOUT, observed.kind);
            assertEquals(
                    WorkerStartup.TerminalDecision.TIMED_OUT, worker.startup().terminalDecision());
            assertEquals(0, factoryCalls.get());
            assertEquals(0, state.launchClaims.get());
            assertEquals(1, state.discards.get());
        } finally {
            occupied.forEach(BoundedTaskPermit::close);
        }
        assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
    }

    @Test
    void startupAdmissionInterruptionPreventsFactoryAndPreservesInterruptStatus() throws InterruptedException {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        List<BoundedTaskPermit> occupied = occupyStartupPermits(permitsBefore);
        StartupState state = new StartupState();
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                coordinator(state).start(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
            } catch (Throwable failure) {
                observed.set(failure);
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.setDaemon(true);

        try {
            caller.start();
            awaitStartupAdmissionWait(caller, state);
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(caller.isAlive());
        } finally {
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));
            occupied.forEach(BoundedTaskPermit::close);
        }

        PoolFailure failure = assertInstanceOf(PoolFailure.class, observed.get());
        assertEquals(FailureKind.INTERRUPTED, failure.kind);
        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertTrue(interruptPreserved.get());
        assertEquals(
                WorkerStartup.TerminalDecision.INTERRUPTED, worker.startup().terminalDecision());
        assertEquals(0, factoryCalls.get());
        assertEquals(0, state.launchClaims.get());
        assertEquals(1, state.discards.get());
        assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
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
    void launchClaimFailureRestoresStartupAdmissionWithoutCallingFactory() {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
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
        assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        assertEquals(1, state.discards.get());
    }

    @Test
    void postClaimLaunchFailureInvokesPoolCleanupAndRestoresAdmission() {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
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
        assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
    }

    @Test
    void closedLaunchClaimPreventsFactory() {
        StartupState state = new StartupState();
        state.claim = WorkerStartupCoordinator.StartupClaim.CLOSED;
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
        state.claim = WorkerStartupCoordinator.StartupClaim.TIMED_OUT;
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

    private static List<BoundedTaskPermit> occupyStartupPermits(int capacity) {
        List<BoundedTaskPermit> occupied = new ArrayList<>(capacity);
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskLimits.WORKER_STARTUPS.acquireUninterruptibly());
        }
        return occupied;
    }

    private static void awaitStartupAdmissionWait(Thread caller, StartupState state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            Thread.State callerState = caller.getState();
            if (state.launchClaims.get() == 0
                    && (callerState == Thread.State.WAITING || callerState == Thread.State.TIMED_WAITING)) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("caller did not block on startup admission");
    }

    private static final class StartupState implements WorkerStartupCoordinator.PoolState<String> {

        private final AtomicInteger factoryFailures = new AtomicInteger();
        private final AtomicInteger launchClaims = new AtomicInteger();
        private final AtomicInteger discards = new AtomicInteger();
        private WorkerStartupCoordinator.StartupClaim claim = WorkerStartupCoordinator.StartupClaim.RUN;
        private RuntimeException claimFailure;

        @Override
        public WorkerStartupCoordinator.StartupClaim claimLaunch(PoolWorker<String> worker, long deadlineNanos) {
            launchClaims.incrementAndGet();
            if (claimFailure != null) {
                throw claimFailure;
            }
            if (claim == WorkerStartupCoordinator.StartupClaim.CLOSED) {
                worker.startup().signalClosed();
            } else if (claim == WorkerStartupCoordinator.StartupClaim.TIMED_OUT) {
                worker.startup().signalTimeout();
            }
            return claim;
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

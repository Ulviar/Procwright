/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WorkerStartupCoordinatorTest extends WorkerPoolControllerTestSupport {

    @Test
    void successfulStartupOwnsAdmissionLaunchAndTerminalMapping() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        StartupState state = new StartupState();
        PoolWorker<String> worker = worker(() -> "ready");
        WorkerStartupCoordinator<String> coordinator = coordinator(admissions, state);
        WorkerStartupCoordinator.Reservation<String> reservation = reservation(worker);

        WorkerStartupCoordinator.Completion<String> completion = coordinator.start(
                reservation, System.nanoTime() + TimeUnit.SECONDS.toNanos(1), PoolWorker.StartupPurpose.DEMAND);

        assertEquals("ready", completion.createdWorker().session());
        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, completion.decision());
        assertThrows(IllegalStateException.class, reservation::worker);
        assertEquals(0, admissions.availablePermits());
        releaseAdmission(worker);
        assertEquals(1, admissions.availablePermits());
    }

    @Test
    void rejectedAdmissionAttachmentReturnsAdmissionWithoutInvokingFactory() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        StartupState state = new StartupState();
        state.acceptAdmission = false;
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });
        WorkerStartupCoordinator<String> coordinator = coordinator(admissions, state);
        WorkerStartupCoordinator.Reservation<String> reservation = reservation(worker);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertEquals(FailureKind.CLOSED, observed.kind);
        assertEquals(0, factoryCalls.get());
        assertEquals(1, admissions.availablePermits());
        assertTrue(reservation.canRollback());
    }

    @Test
    void factoryFailureIsMappedAfterPoolStateAccountsForIt() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        StartupState state = new StartupState();
        IllegalStateException factoryFailure = new IllegalStateException("factory failed");
        PoolWorker<String> worker = worker(() -> {
            throw factoryFailure;
        });
        WorkerStartupCoordinator<String> coordinator = coordinator(admissions, state);
        WorkerStartupCoordinator.Reservation<String> reservation = reservation(worker);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.REPLENISHMENT));

        assertEquals(FailureKind.STARTUP_FAILED, observed.kind);
        assertSame(factoryFailure, observed.getCause());
        assertEquals(1, state.factoryFailures.get());
        assertEquals(1, admissions.availablePermits());
        assertFalse(reservation.canRollback());
        assertThrows(IllegalStateException.class, reservation::stateWorker);
    }

    @Test
    void launchClaimFailureRestoresGlobalPermitAndLeavesReservationForCallerRollback() {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        IllegalStateException claimFailure = new IllegalStateException("claim failed");
        StartupState state = new StartupState();
        state.claimFailure = claimFailure;
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });
        WorkerStartupCoordinator<String> coordinator = coordinator(admissions, state);
        WorkerStartupCoordinator.Reservation<String> reservation = reservation(worker);

        IllegalStateException observed = assertThrows(
                IllegalStateException.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertSame(claimFailure, observed);
        assertEquals(0, factoryCalls.get());
        assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        assertTrue(reservation.canRollback());
        assertEquals(0, admissions.availablePermits());
        releaseAdmission(worker);
        assertEquals(1, admissions.availablePermits());
    }

    @Test
    void closedLaunchClaimPreventsFactoryAndKeepsRollbackOwnershipWithCaller() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        StartupState state = new StartupState();
        state.claim = WorkerStartupCoordinator.StartupClaim.CLOSED;
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });
        WorkerStartupCoordinator<String> coordinator = coordinator(admissions, state);
        WorkerStartupCoordinator.Reservation<String> reservation = reservation(worker);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertEquals(FailureKind.CLOSED, observed.kind);
        assertEquals(WorkerStartup.TerminalDecision.CLOSED, worker.startup().terminalDecision());
        assertEquals(0, factoryCalls.get());
        assertTrue(reservation.canRollback());
        releaseAdmission(worker);
        assertEquals(1, admissions.availablePermits());
    }

    @Test
    void timedOutLaunchClaimPreventsFactoryAndUsesDemandTimeoutTaxonomy() {
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        StartupState state = new StartupState();
        state.claim = WorkerStartupCoordinator.StartupClaim.TIMED_OUT;
        AtomicInteger factoryCalls = new AtomicInteger();
        PoolWorker<String> worker = worker(() -> {
            factoryCalls.incrementAndGet();
            return "unexpected";
        });
        WorkerStartupCoordinator<String> coordinator = coordinator(admissions, state);
        WorkerStartupCoordinator.Reservation<String> reservation = reservation(worker);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertEquals(FailureKind.ACQUIRE_TIMEOUT, observed.kind);
        assertEquals(WorkerStartup.TerminalDecision.TIMED_OUT, worker.startup().terminalDecision());
        assertEquals(0, factoryCalls.get());
        assertTrue(reservation.canRollback());
        releaseAdmission(worker);
        assertEquals(1, admissions.availablePermits());
    }

    private static WorkerStartupCoordinator<String> coordinator(
            PoolLifecycleDispatcher.AdmissionPool admissions, StartupState state) {
        return new WorkerStartupCoordinator<>(
                Failures.INSTANCE,
                "test worker",
                deadlineNanos -> {
                    PoolLifecycleDispatcher.Admission admission = admissions.tryAcquire();
                    if (admission == null) {
                        throw new java.util.concurrent.TimeoutException("test admission unavailable");
                    }
                    return admission;
                },
                state);
    }

    private static PoolWorker<String> worker(java.util.function.Supplier<String> factory) {
        PoolWorker<String> worker = new PoolWorker<>(
                (session, admission) -> () -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success()));
        worker.startup(new WorkerStartup<>(factory, "test-startup-coordinator-", completion -> {}));
        return worker;
    }

    private static WorkerStartupCoordinator.Reservation<String> reservation(PoolWorker<String> worker) {
        WorkerRetirementCoordinator<String> retirements = new WorkerRetirementCoordinator<>(
                Runnable::run, (retired, outcome) -> null, (retired, failure) -> {}, report -> {});
        WorkerPoolState<String> state =
                new WorkerPoolState<>(new WorkerPoolPolicy(TestOptions.INSTANCE), new PoolTermination(), () -> {
                    throw new AssertionError("unused reservation factory");
                });
        return new WorkerStartupCoordinator.Reservation<>(worker, new PoolStateEffects<>(state, retirements));
    }

    private static void releaseAdmission(PoolWorker<?> worker) {
        PoolLifecycleDispatcher.Admission admission = worker.detachRetirementAdmission();
        if (admission != null) {
            admission.close();
        }
    }

    private static final class StartupState implements WorkerStartupCoordinator.PoolState<String> {

        private final AtomicInteger factoryFailures = new AtomicInteger();
        private boolean acceptAdmission = true;
        private WorkerStartupCoordinator.StartupClaim claim = WorkerStartupCoordinator.StartupClaim.RUN;
        private RuntimeException claimFailure;

        @Override
        public boolean attachAdmission(
                WorkerStartupCoordinator.Reservation<String> reservation,
                PoolLifecycleDispatcher.Admission admission,
                PoolWorker.StartupPurpose purpose) {
            if (!acceptAdmission) {
                return false;
            }
            reservation.stateWorker().retirementAdmission(admission);
            reservation.stateWorker().startupPurpose(purpose);
            return true;
        }

        @Override
        public WorkerStartupCoordinator.StartupClaim claimLaunch(
                WorkerStartupCoordinator.Reservation<String> reservation, long deadlineNanos) {
            if (claimFailure != null) {
                throw claimFailure;
            }
            if (claim == WorkerStartupCoordinator.StartupClaim.CLOSED) {
                reservation.stateWorker().startup().signalClosed();
            } else if (claim == WorkerStartupCoordinator.StartupClaim.TIMED_OUT) {
                reservation.stateWorker().startup().signalTimeout();
            } else {
                reservation.stateWorker().startupStage(PoolWorker.StartupStage.RUNNING);
            }
            return claim;
        }

        @Override
        public void launchFailed(WorkerStartupCoordinator.Reservation<String> reservation) {
            releaseAdmission(reservation.stateWorker());
        }

        @Override
        public boolean factoryFailed(WorkerStartupCoordinator.Reservation<String> reservation, Throwable failure) {
            factoryFailures.incrementAndGet();
            releaseAdmission(reservation.stateWorker());
            reservation.completeWithoutLease();
            return false;
        }
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

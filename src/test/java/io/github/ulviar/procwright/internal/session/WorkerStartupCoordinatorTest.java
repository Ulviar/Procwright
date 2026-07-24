/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        WorkerStartupCoordinator.Reservation<String> reservation = coordinator.newReservation();
        reservation.register(worker);

        WorkerStartupCoordinator.Completion<String> completion = coordinator.start(
                reservation, System.nanoTime() + TimeUnit.SECONDS.toNanos(1), PoolWorker.StartupPurpose.DEMAND);

        assertEquals("ready", completion.createdWorker().session());
        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, completion.decision());
        assertThrows(IllegalStateException.class, reservation::worker);
        assertEquals(0, admissions.availablePermits());
        worker.releaseRetirementAdmission();
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
        WorkerStartupCoordinator.Reservation<String> reservation = coordinator.newReservation();
        reservation.register(worker);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertEquals(FailureKind.CLOSED, observed.kind);
        assertEquals(0, factoryCalls.get());
        assertEquals(1, admissions.availablePermits());
        assertSame(worker, reservation.releaseForFailure());
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
        WorkerStartupCoordinator.Reservation<String> reservation = coordinator.newReservation();
        reservation.register(worker);

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
        assertNull(reservation.releaseForFailure());
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
        WorkerStartupCoordinator.Reservation<String> reservation = coordinator.newReservation();
        reservation.register(worker);

        IllegalStateException observed = assertThrows(
                IllegalStateException.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertSame(claimFailure, observed);
        assertEquals(0, factoryCalls.get());
        assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        assertSame(worker, reservation.releaseForFailure());
        assertEquals(0, admissions.availablePermits());
        worker.releaseRetirementAdmission();
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
        WorkerStartupCoordinator.Reservation<String> reservation = coordinator.newReservation();
        reservation.register(worker);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertEquals(FailureKind.CLOSED, observed.kind);
        assertEquals(WorkerStartup.TerminalDecision.CLOSED, worker.startup().terminalDecision());
        assertEquals(0, factoryCalls.get());
        assertSame(worker, reservation.releaseForFailure());
        worker.releaseRetirementAdmission();
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
        WorkerStartupCoordinator.Reservation<String> reservation = coordinator.newReservation();
        reservation.register(worker);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> coordinator.start(
                        reservation,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                        PoolWorker.StartupPurpose.DEMAND));

        assertEquals(FailureKind.ACQUIRE_TIMEOUT, observed.kind);
        assertEquals(WorkerStartup.TerminalDecision.TIMED_OUT, worker.startup().terminalDecision());
        assertEquals(0, factoryCalls.get());
        assertSame(worker, reservation.releaseForFailure());
        worker.releaseRetirementAdmission();
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
        PoolWorker<String> worker = new PoolWorker<>();
        worker.startup(new WorkerStartup<>(factory, "test-startup-coordinator-", completion -> {}));
        return worker;
    }

    private static final class StartupState implements WorkerStartupCoordinator.PoolState<String> {

        private final AtomicInteger factoryFailures = new AtomicInteger();
        private boolean acceptAdmission = true;
        private WorkerStartupCoordinator.StartupClaim claim = WorkerStartupCoordinator.StartupClaim.RUN;
        private RuntimeException claimFailure;

        @Override
        public boolean attachAdmission(
                PoolWorker<String> reservation,
                PoolLifecycleDispatcher.Admission admission,
                PoolWorker.StartupPurpose purpose) {
            if (!acceptAdmission) {
                return false;
            }
            reservation.retirementAdmission(admission);
            reservation.startupPurpose(purpose);
            return true;
        }

        @Override
        public WorkerStartupCoordinator.StartupClaim claimLaunch(PoolWorker<String> reservation, long deadlineNanos) {
            if (claimFailure != null) {
                throw claimFailure;
            }
            if (claim == WorkerStartupCoordinator.StartupClaim.CLOSED) {
                reservation.startup().signalClosed();
            } else if (claim == WorkerStartupCoordinator.StartupClaim.TIMED_OUT) {
                reservation.startup().signalTimeout();
            } else {
                reservation.startupStage(PoolWorker.StartupStage.RUNNING);
            }
            return claim;
        }

        @Override
        public void launchFailed(PoolWorker<String> reservation) {
            reservation.releaseRetirementAdmission();
        }

        @Override
        public boolean factoryFailed(PoolWorker<String> reservation) {
            factoryFailures.incrementAndGet();
            reservation.releaseRetirementAdmission();
            return false;
        }
    }
}

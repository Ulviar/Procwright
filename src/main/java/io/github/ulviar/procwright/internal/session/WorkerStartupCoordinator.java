/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Owns admission, launch, wait, abandonment, and failure mapping for one worker startup. */
final class WorkerStartupCoordinator<S> {

    private final WorkerPoolController.FailureFactory failures;
    private final String workerLabel;
    private final WorkerPoolController.RetirementAdmissionProvider admissions;
    private final PoolState<S> poolState;

    WorkerStartupCoordinator(
            WorkerPoolController.FailureFactory failures,
            String workerLabel,
            WorkerPoolController.RetirementAdmissionProvider admissions,
            PoolState<S> poolState) {
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.poolState = Objects.requireNonNull(poolState, "poolState");
    }

    Completion<S> start(Reservation<S> reservation, long deadlineNanos, PoolWorker.StartupPurpose purpose) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(purpose, "purpose");

        PoolWorker<S> worker = reservation.worker();
        BoundedTaskPermit permit = acquireResources(reservation, deadlineNanos, purpose);
        WorkerStartup<S> owner = launch(reservation, permit, deadlineNanos, purpose);
        WorkerStartup.CreatedWorker<S> createdWorker = await(owner, reservation, deadlineNanos, purpose);
        return new Completion<>(createdWorker, owner.terminalDecision());
    }

    RuntimeException rejectedCompletion(PoolWorker.StartupPurpose purpose, WorkerStartup.TerminalDecision decision) {
        if (decision == WorkerStartup.TerminalDecision.CLOSED) {
            return failures.closed("Pool is closed");
        }
        if (decision == WorkerStartup.TerminalDecision.TIMED_OUT
                || decision == WorkerStartup.TerminalDecision.INTERRUPTED) {
            return startupTimeout(
                    purpose, new TimeoutException("worker startup completed after its terminal decision was selected"));
        }
        return new IllegalStateException("successful worker startup has incompatible terminal decision: " + decision);
    }

    private BoundedTaskPermit acquireResources(
            Reservation<S> reservation, long deadlineNanos, PoolWorker.StartupPurpose purpose) {
        PoolLifecycleDispatcher.Admission admission = null;
        try {
            admission = admissions.acquire(deadlineNanos);
            if (!poolState.attachAdmission(reservation, admission, purpose)) {
                admission.close();
                admission = null;
                throw failures.closed("Pool is closed");
            }
            admission = null;
            return BoundedTaskLimits.WORKER_STARTUPS.acquire(deadlineNanos);
        } catch (TimeoutException failure) {
            close(admission);
            throw preLaunchTimeout(reservation.worker(), purpose, failure);
        } catch (InterruptedException failure) {
            close(admission);
            Thread.currentThread().interrupt();
            throw preLaunchInterruption(reservation.worker(), purpose, failure);
        } catch (RuntimeException | Error failure) {
            close(admission);
            throw failure;
        }
    }

    private WorkerStartup<S> launch(
            Reservation<S> reservation,
            BoundedTaskPermit permit,
            long deadlineNanos,
            PoolWorker.StartupPurpose purpose) {
        boolean transferred = false;
        PoolWorker<S> worker = reservation.worker();
        try {
            StartupClaim claim = poolState.claimLaunch(reservation, deadlineNanos);
            if (claim == StartupClaim.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            if (claim == StartupClaim.TIMED_OUT) {
                throw startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed before launch"));
            }
            WorkerStartup<S> owner = worker.startup();
            reservation.transferToAttempt();
            transferred = true;
            try {
                owner.start(permit);
            } catch (RuntimeException | Error failure) {
                Throwable terminalFailure = failure;
                try {
                    poolState.launchFailed(reservation);
                } catch (RuntimeException | Error cleanupFailure) {
                    terminalFailure = FailureAggregation.combine(
                            terminalFailure,
                            cleanupFailure,
                            "Worker startup launch and reservation cleanup both failed");
                }
                rethrow(terminalFailure);
                throw new AssertionError("unreachable");
            }
            return owner;
        } finally {
            if (!transferred) {
                permit.close();
            }
        }
    }

    private WorkerStartup.CreatedWorker<S> await(
            WorkerStartup<S> owner, Reservation<S> reservation, long deadlineNanos, PoolWorker.StartupPurpose purpose) {
        try {
            return owner.await(deadlineNanos);
        } catch (TimeoutException failure) {
            WorkerStartup.TerminalDecision decision = owner.terminalDecision();
            PooledWorkerRetireReason reason = abandonmentReason(decision, "worker startup timeout");
            owner.abandon(reason);
            if (decision == WorkerStartup.TerminalDecision.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            throw startupTimeout(purpose, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            WorkerStartup.TerminalDecision decision = owner.terminalDecision();
            PooledWorkerRetireReason reason = abandonmentReason(decision, "worker startup interruption");
            owner.abandon(reason);
            if (decision == WorkerStartup.TerminalDecision.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            if (decision == WorkerStartup.TerminalDecision.TIMED_OUT) {
                throw startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed"));
            }
            throw failures.acquireInterrupted("Interrupted while starting " + workerLabel, failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            boolean closed = poolState.factoryFailed(reservation, cause);
            if (cause instanceof Error error) {
                throw error;
            }
            if (closed) {
                RuntimeException closedFailure = failures.closed("Pool is closed");
                throw (RuntimeException) failures.expose(FailureAggregation.combine(
                        closedFailure, cause, "Pool closed while its worker factory failed"));
            }
            throw failures.startupFailed("Could not start " + workerLabel, cause);
        }
    }

    private RuntimeException preLaunchTimeout(
            PoolWorker<S> reservation, PoolWorker.StartupPurpose purpose, TimeoutException failure) {
        WorkerStartup.TerminalDecision decision = reservation.startup().signalTimeout();
        if (decision == WorkerStartup.TerminalDecision.CLOSED) {
            return failures.closed("Pool is closed");
        }
        if (decision != WorkerStartup.TerminalDecision.TIMED_OUT) {
            return new IllegalStateException("queued worker timeout has incompatible terminal decision: " + decision);
        }
        return startupTimeout(purpose, failure);
    }

    private RuntimeException preLaunchInterruption(
            PoolWorker<S> reservation, PoolWorker.StartupPurpose purpose, InterruptedException failure) {
        WorkerStartup.TerminalDecision decision = reservation.startup().signalInterrupted();
        return switch (decision) {
            case CLOSED -> failures.closed("Pool is closed");
            case TIMED_OUT ->
                startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed before launch"));
            case INTERRUPTED -> failures.acquireInterrupted("Interrupted while starting " + workerLabel, failure);
            case FACTORY_COMPLETED, UNDECIDED ->
                new IllegalStateException("pre-launch interruption has incompatible terminal decision: " + decision);
        };
    }

    private RuntimeException startupTimeout(PoolWorker.StartupPurpose purpose, TimeoutException cause) {
        if (purpose == PoolWorker.StartupPurpose.WARMUP) {
            return failures.startupFailed("Timed out warming " + workerLabel, cause);
        }
        return failures.acquireTimeout("Timed out starting " + workerLabel);
    }

    private static PooledWorkerRetireReason abandonmentReason(
            WorkerStartup.TerminalDecision decision, String operation) {
        return switch (decision) {
            case CLOSED -> PooledWorkerRetireReason.CLOSED;
            case TIMED_OUT -> PooledWorkerRetireReason.STARTUP_TIMEOUT;
            case INTERRUPTED -> PooledWorkerRetireReason.STARTUP_INTERRUPTED;
            case FACTORY_COMPLETED, UNDECIDED ->
                throw new IllegalStateException(operation + " has incompatible terminal decision: " + decision);
        };
    }

    private static void close(PoolLifecycleDispatcher.Admission admission) {
        if (admission != null) {
            admission.close();
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Worker startup produced a checked failure", failure);
    }

    interface PoolState<S> {

        boolean attachAdmission(
                Reservation<S> reservation,
                PoolLifecycleDispatcher.Admission admission,
                PoolWorker.StartupPurpose purpose);

        StartupClaim claimLaunch(Reservation<S> reservation, long deadlineNanos);

        void launchFailed(Reservation<S> reservation);

        boolean factoryFailed(Reservation<S> reservation, Throwable failure);
    }

    enum StartupClaim {
        RUN,
        CLOSED,
        TIMED_OUT
    }

    static final class Reservation<S> {

        private final PoolWorker<S> worker;
        private final PoolStateEffects<S> effects;
        private Ownership ownership = Ownership.RESERVATION;
        private WorkerPoolState.Lease<S> lease;

        Reservation(PoolWorker<S> worker, PoolStateEffects<S> effects) {
            this.worker = Objects.requireNonNull(worker, "worker");
            this.effects = Objects.requireNonNull(effects, "effects");
        }

        PoolWorker<S> worker() {
            if (ownership != Ownership.RESERVATION) {
                throw new IllegalStateException("startup reservation has no slot");
            }
            return worker;
        }

        boolean canRollback() {
            return ownership == Ownership.RESERVATION;
        }

        private void transferToAttempt() {
            if (ownership != Ownership.RESERVATION) {
                throw new IllegalStateException("startup reservation has no slot");
            }
            ownership = Ownership.ATTEMPT;
        }

        PoolWorker<S> stateWorker() {
            requireActive();
            return worker;
        }

        PoolStateEffects<S> effects() {
            requireActive();
            return effects;
        }

        void requireEffects(PoolStateEffects<S> candidate) {
            requireActive();
            if (effects != candidate) {
                throw new IllegalArgumentException("startup transition requires its reservation effects");
            }
        }

        void prepareLease(WorkerPoolState.Lease<S> preparedLease) {
            if (ownership != Ownership.RESERVATION || lease != null) {
                throw new IllegalStateException("startup reservation cannot accept a lease");
            }
            lease = Objects.requireNonNull(preparedLease, "preparedLease");
        }

        WorkerPoolState.Lease<S> preparedLease() {
            requireActive();
            if (lease == null) {
                throw new IllegalStateException("startup reservation has no prepared lease");
            }
            return lease;
        }

        WorkerPoolState.Lease<S> completeWithLease() {
            WorkerPoolState.Lease<S> completedLease = preparedLease();
            lease = null;
            ownership = Ownership.TERMINATED;
            return completedLease;
        }

        void completeWithoutLease() {
            requireActive();
            if (lease != null) {
                lease.clear(worker);
                lease = null;
            }
            ownership = Ownership.TERMINATED;
        }

        private void requireActive() {
            if (ownership == Ownership.TERMINATED) {
                throw new IllegalStateException("startup reservation is terminated");
            }
        }

        private enum Ownership {
            RESERVATION,
            ATTEMPT,
            TERMINATED
        }
    }

    record Completion<S>(WorkerStartup.CreatedWorker<S> createdWorker, WorkerStartup.TerminalDecision decision) {

        Completion {
            Objects.requireNonNull(createdWorker, "createdWorker");
            Objects.requireNonNull(decision, "decision");
        }
    }
}

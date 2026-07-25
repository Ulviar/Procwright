/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Owns worker-permit acquisition, launch, wait, abandonment, and failure mapping for one worker startup. */
final class WorkerStartupCoordinator<S> {

    private final WorkerPoolController.FailureFactory failures;
    private final String workerLabel;
    private final WorkerPoolController.WorkerPermitProvider permits;
    private final PoolState<S> poolState;

    WorkerStartupCoordinator(
            WorkerPoolController.FailureFactory failures,
            String workerLabel,
            WorkerPoolController.WorkerPermitProvider permits,
            PoolState<S> poolState) {
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.permits = Objects.requireNonNull(permits, "permits");
        this.poolState = Objects.requireNonNull(poolState, "poolState");
    }

    Completion<S> start(Reservation<S> reservation, long deadlineNanos) {
        Objects.requireNonNull(reservation, "reservation");
        PoolWorker.StartupPurpose purpose = reservation.purpose();

        BoundedTaskPermit startupPermit = acquireResources(reservation, deadlineNanos, purpose);
        WorkerStartup<S> owner = launch(reservation, startupPermit, deadlineNanos, purpose);
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
        BoundedTaskPermit workerPermit = null;
        try {
            workerPermit = permits.acquire(deadlineNanos);
            if (!poolState.attachPermit(reservation, workerPermit)) {
                workerPermit.close();
                workerPermit = null;
                throw failures.closed("Pool is closed");
            }
            workerPermit = null;
            return BoundedTaskLimits.WORKER_STARTUPS.acquire(deadlineNanos);
        } catch (TimeoutException failure) {
            close(workerPermit);
            throw preLaunchTimeout(reservation.worker(), purpose, failure);
        } catch (InterruptedException failure) {
            close(workerPermit);
            Thread.currentThread().interrupt();
            throw preLaunchInterruption(reservation.worker(), purpose, failure);
        } catch (RuntimeException | Error failure) {
            close(workerPermit);
            throw failure;
        }
    }

    private WorkerStartup<S> launch(
            Reservation<S> reservation,
            BoundedTaskPermit startupPermit,
            long deadlineNanos,
            PoolWorker.StartupPurpose purpose) {
        boolean transferred = false;
        PoolWorker<S> worker = reservation.worker();
        WorkerStartup<S> owner = worker.startup();
        try {
            StartupClaim claim = poolState.claimLaunch(reservation, deadlineNanos);
            if (claim == StartupClaim.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            if (claim == StartupClaim.TIMED_OUT) {
                throw startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed before launch"));
            }
            transferred = true;
            try {
                owner.start(startupPermit);
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
                startupPermit.close();
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

    private static void close(BoundedTaskPermit permit) {
        if (permit != null) {
            permit.close();
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

        boolean attachPermit(Reservation<S> reservation, BoundedTaskPermit permit);

        /** A {@link StartupClaim#RUN} result atomically transfers reservation ownership to the startup attempt. */
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
        private WorkerPoolState<S> owner;
        private Ownership ownership = Ownership.RESERVATION;
        private WorkerPoolState.Lease<S> lease;

        Reservation(PoolWorker<S> worker) {
            this.worker = Objects.requireNonNull(worker, "worker");
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

        PoolWorker.StartupPurpose purpose() {
            return worker.startupPurpose();
        }

        void transferToAttempt() {
            requireOwnership(Ownership.RESERVATION);
            ownership = Ownership.ATTEMPT;
        }

        PoolWorker<S> stateWorker() {
            requireActive();
            return worker;
        }

        PoolWorker<S> reservedWorker(WorkerPoolState<S> expectedOwner) {
            requireOwnership(expectedOwner, Ownership.RESERVATION);
            return worker;
        }

        PoolWorker<S> attemptedWorker(WorkerPoolState<S> expectedOwner) {
            requireOwnership(expectedOwner, Ownership.ATTEMPT);
            return worker;
        }

        void bind(WorkerPoolState<S> acceptedOwner, WorkerPoolState.Lease<S> preparedLease) {
            if (owner != null || ownership != Ownership.RESERVATION || lease != null) {
                throw new IllegalStateException("startup reservation cannot accept a lease");
            }
            owner = Objects.requireNonNull(acceptedOwner, "acceptedOwner");
            lease = Objects.requireNonNull(preparedLease, "preparedLease");
        }

        WorkerPoolState.Lease<S> preparedLease() {
            requireActive();
            if (lease == null) {
                throw new IllegalStateException("startup reservation has no prepared lease");
            }
            return lease;
        }

        WorkerPoolState.Lease<S> preparedLease(WorkerPoolState<S> expectedOwner) {
            requireOwnership(expectedOwner, Ownership.ATTEMPT);
            return preparedLease();
        }

        WorkerPoolState.Lease<S> completeWithLease(WorkerPoolState<S> expectedOwner) {
            WorkerPoolState.Lease<S> completedLease = preparedLease(expectedOwner);
            lease = null;
            ownership = Ownership.TERMINATED;
            return completedLease;
        }

        void completeReservation(WorkerPoolState<S> expectedOwner) {
            completeWithoutLease(expectedOwner, Ownership.RESERVATION);
        }

        void completeAttempt() {
            completeWithoutLease(Ownership.ATTEMPT);
        }

        private void completeWithoutLease(WorkerPoolState<S> expectedOwner, Ownership expectedOwnership) {
            requireOwnership(expectedOwner, expectedOwnership);
            completeWithoutLease(expectedOwnership);
        }

        private void completeWithoutLease(Ownership expectedOwnership) {
            requireOwnership(expectedOwnership);
            if (lease != null) {
                lease.clear(worker);
                lease = null;
            }
            ownership = Ownership.TERMINATED;
        }

        private void requireOwnership(Ownership expectedOwnership) {
            if (ownership != expectedOwnership) {
                throw new IllegalStateException(
                        "startup reservation ownership must be " + expectedOwnership + " but was " + ownership);
            }
        }

        private void requireOwnership(WorkerPoolState<S> expectedOwner, Ownership expectedOwnership) {
            if (owner != Objects.requireNonNull(expectedOwner, "expectedOwner")) {
                throw new IllegalArgumentException("startup reservation belongs to another pool");
            }
            requireOwnership(expectedOwnership);
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

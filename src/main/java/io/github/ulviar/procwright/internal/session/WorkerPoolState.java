/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Owns pool-level mutable invariants serialized by one worker-pool monitor.
 *
 * <p>Methods perform logical transitions and register their mandatory post-monitor work with {@link PoolStateEffects}.
 * Physical close, terminal publication, and failure reporting never run under this monitor.
 */
final class WorkerPoolState<S> {

    private final Object monitor = new Object();
    private final WorkerPoolPolicy policy;
    private final PoolPartition<PoolWorker<S>> partition;
    private final PoolMetrics metrics = new PoolMetrics();
    private final PoolTermination termination;
    private final StartupReservationFactory<S> reservations;
    private long revision;

    WorkerPoolState(WorkerPoolPolicy policy, PoolTermination termination, StartupReservationFactory<S> reservations) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.termination = Objects.requireNonNull(termination, "termination");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        partition = new PoolPartition<>(policy.maxSize());
    }

    PoolTermination.ConstructionResult finishConstruction() {
        synchronized (monitor) {
            return termination.finishConstruction();
        }
    }

    List<FailureReport> failConstructionAndClose(PoolStateEffects<S> effects) {
        requireEffects(effects);
        synchronized (monitor) {
            List<FailureReport> reports = termination.failConstruction();
            enterClosingLocked(null, effects);
            changedLocked();
            effects.publish(claimDrainLocked());
            return reports;
        }
    }

    PoolTermination.FailureDisposition beginClose(Throwable failure, PoolStateEffects<S> effects) {
        requireEffects(effects);
        synchronized (monitor) {
            PoolTermination.FailureDisposition disposition = enterClosingLocked(failure, effects);
            changedLocked();
            effects.publish(claimDrainLocked());
            return disposition;
        }
    }

    AcquireResult<S> awaitAcquire(long deadlineNanos, PoolStateEffects<S> effects) {
        requireEffects(effects);
        synchronized (monitor) {
            while (true) {
                if (termination.closing()) {
                    return AcquireResult.closed();
                }
                PoolWorker<S> idle = partition.firstIdle();
                if (idle != null) {
                    PooledWorkerRetireReason reason = policy.retirementReasonFor(idle);
                    if (reason != null) {
                        queueRetirementLocked(idle, reason, effects);
                        changedLocked();
                        return AcquireResult.retry();
                    }
                    Lease<S> lease = new Lease<>(this, idle);
                    AcquireResult<S> result = AcquireResult.leased(lease);
                    PoolWorker<S> leased = partition.leaseIdle();
                    if (leased != idle) {
                        lease.clear(idle);
                        throw new IllegalStateException("pool idle order changed while leasing");
                    }
                    changedLocked();
                    return result;
                }
                if (partition.size() < policy.maxSize()) {
                    WorkerStartupCoordinator.Reservation<S> reservation =
                            prepareReservationLocked(PoolWorker.StartupPurpose.DEMAND);
                    AcquireResult<S> result = AcquireResult.reserved(reservation);
                    commitReservationLocked(reservation);
                    return result;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return AcquireResult.timedOut();
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return AcquireResult.interrupted(failure);
                }
            }
        }
    }

    ReservationResult<S> reserveWarmup() {
        synchronized (monitor) {
            if (termination.closing()) {
                return ReservationResult.closed();
            }
            if (partition.size() >= policy.maxSize()) {
                throw new IllegalStateException("pool capacity exhausted while reserving warmup slot");
            }
            WorkerStartupCoordinator.Reservation<S> reservation =
                    prepareReservationLocked(PoolWorker.StartupPurpose.WARMUP);
            ReservationResult<S> result = ReservationResult.reserved(reservation);
            commitReservationLocked(reservation);
            return result;
        }
    }

    WorkerStartupCoordinator.Reservation<S> tryReserveReplenishment() {
        synchronized (monitor) {
            if (termination.closing() || !needsReplenishmentLocked()) {
                return null;
            }
            WorkerStartupCoordinator.Reservation<S> reservation =
                    prepareReservationLocked(PoolWorker.StartupPurpose.REPLENISHMENT);
            commitReservationLocked(reservation);
            return reservation;
        }
    }

    boolean attachStartupPermit(WorkerStartupCoordinator.Reservation<S> reservation, BoundedTaskPermit permit) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(permit, "permit");
        PoolWorker<S> worker = reservation.reservedWorker(this);
        synchronized (monitor) {
            if (!partition.contains(worker) || termination.closing()) {
                return false;
            }
            partition.requireState(worker, PoolPartition.State.STARTING);
            worker.workerPermit(permit);
            return true;
        }
    }

    WorkerStartupCoordinator.StartupClaim claimStartup(
            WorkerStartupCoordinator.Reservation<S> reservation, long deadlineNanos) {
        Objects.requireNonNull(reservation, "reservation");
        PoolWorker<S> worker = reservation.reservedWorker(this);
        synchronized (monitor) {
            if (!partition.contains(worker) || termination.closing()) {
                worker.startup().signalClosed();
                return WorkerStartupCoordinator.StartupClaim.CLOSED;
            }
            partition.requireState(worker, PoolPartition.State.STARTING);
            if (deadlineNanos - System.nanoTime() <= 0) {
                worker.startup().signalTimeout();
                return WorkerStartupCoordinator.StartupClaim.TIMED_OUT;
            }
            worker.startupStage(PoolWorker.StartupStage.RUNNING);
            reservation.transferToAttempt();
            return WorkerStartupCoordinator.StartupClaim.RUN;
        }
    }

    void launchFailed(WorkerStartupCoordinator.Reservation<S> reservation, PoolStateEffects<S> effects) {
        Objects.requireNonNull(reservation, "reservation");
        requireEffects(effects);
        PoolWorker<S> worker = reservation.attemptedWorker(this);
        synchronized (monitor) {
            if (removeSlotLocked(worker, effects)) {
                changedLocked();
            }
            reservation.completeAttempt();
            effects.publish(claimDrainLocked());
        }
    }

    boolean factoryFailed(
            WorkerStartupCoordinator.Reservation<S> reservation, Throwable failure, PoolStateEffects<S> effects) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(failure, "failure");
        requireEffects(effects);
        PoolWorker<S> worker = reservation.attemptedWorker(this);
        synchronized (monitor) {
            boolean closedStartup =
                    switch (worker.startup().terminalDecision()) {
                        case CLOSED -> true;
                        case FACTORY_COMPLETED -> false;
                        case TIMED_OUT, INTERRUPTED, UNDECIDED ->
                            throw new IllegalStateException("worker factory failure has no completion decision");
                    };
            if (failure instanceof Error && worker.startupPurpose() == PoolWorker.StartupPurpose.REPLENISHMENT) {
                enterClosingLocked(failure, effects);
            }
            if (removeSlotLocked(worker, effects)) {
                metrics.startupFailed();
            }
            changedLocked();
            reservation.completeAttempt();
            effects.publish(claimDrainLocked());
            return closedStartup;
        }
    }

    Lease<S> completeStartup(
            WorkerStartupCoordinator.Reservation<S> reservation,
            WorkerStartup.CreatedWorker<S> createdWorker,
            WorkerStartup.TerminalDecision decision,
            PoolStateEffects<S> effects) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(createdWorker, "createdWorker");
        Objects.requireNonNull(decision, "decision");
        requireEffects(effects);
        PoolWorker<S> worker = reservation.attemptedWorker(this);
        Lease<S> lease = reservation.preparedLease(this);
        synchronized (monitor) {
            partition.requireState(worker, PoolPartition.State.STARTING);
            if (decision != WorkerStartup.TerminalDecision.FACTORY_COMPLETED) {
                effects.retire(worker);
            }
            worker.accept(createdWorker.session());
            metrics.workerCreated(createdWorker.startupNanos());
            switch (decision) {
                case FACTORY_COMPLETED -> {
                    lease.requireWorker(this);
                    partition.startingToLeased(worker);
                }
                case CLOSED -> markRetiringLocked(worker, PooledWorkerRetireReason.CLOSED);
                case TIMED_OUT -> markRetiringLocked(worker, PooledWorkerRetireReason.STARTUP_TIMEOUT);
                case INTERRUPTED -> markRetiringLocked(worker, PooledWorkerRetireReason.STARTUP_INTERRUPTED);
                case UNDECIDED -> throw new AssertionError("unreachable terminal decision");
            }
            changedLocked();
            if (decision == WorkerStartup.TerminalDecision.FACTORY_COMPLETED) {
                return reservation.completeWithLease(this);
            }
            reservation.completeAttempt();
            return null;
        }
    }

    boolean completeAbandonedStartup(
            WorkerStartupCoordinator.Reservation<S> reservation,
            WorkerStartup.LateCompletion<S> completion,
            PoolStateEffects<S> effects) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(completion, "completion");
        requireEffects(effects);
        PoolWorker<S> worker = reservation.attemptedWorker(this);
        synchronized (monitor) {
            if (!partition.contains(worker)) {
                reservation.completeAttempt();
                return false;
            }
            metrics.startupFailed();
            if (completion.session() == null) {
                removeSlotLocked(worker, effects);
            } else {
                effects.retire(worker);
                worker.accept(completion.session());
                metrics.workerCreated(completion.startupNanos());
                markRetiringLocked(worker, completion.reason());
            }
            changedLocked();
            reservation.completeAttempt();
            effects.publish(claimDrainLocked());
            return true;
        }
    }

    void removeFailedStartup(WorkerStartupCoordinator.Reservation<S> reservation, PoolStateEffects<S> effects) {
        Objects.requireNonNull(reservation, "reservation");
        requireEffects(effects);
        PoolWorker<S> worker = reservation.reservedWorker(this);
        synchronized (monitor) {
            if (removeSlotLocked(worker, effects)) {
                changedLocked();
            }
            reservation.completeReservation(this);
            effects.publish(claimDrainLocked());
        }
    }

    PooledWorkerRetireReason recordRequestAndRetirementReason(Lease<S> lease) {
        Objects.requireNonNull(lease, "lease");
        synchronized (monitor) {
            PoolWorker<S> worker = lease.requireWorker(this);
            partition.requireState(worker, PoolPartition.State.LEASED);
            worker.recordRequest();
            return termination.closing() ? PooledWorkerRetireReason.CLOSED : policy.retirementReasonFor(worker);
        }
    }

    void releaseReusable(Lease<S> lease, PoolStateEffects<S> effects) {
        Objects.requireNonNull(lease, "lease");
        requireEffects(effects);
        synchronized (monitor) {
            PoolWorker<S> worker = lease.requireWorker(this);
            partition.requireState(worker, PoolPartition.State.LEASED);
            PooledWorkerRetireReason reason =
                    termination.closing() ? PooledWorkerRetireReason.CLOSED : policy.retirementReasonFor(worker);
            if (reason == null) {
                partition.leasedToIdle(worker);
            } else {
                queueRetirementLocked(worker, reason, effects);
            }
            lease.clear(worker);
            changedLocked();
        }
    }

    void retire(Lease<S> lease, PooledWorkerRetireReason reason, PoolStateEffects<S> effects) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(reason, "reason");
        requireEffects(effects);
        synchronized (monitor) {
            PoolWorker<S> worker = lease.requireWorker(this);
            partition.requireState(worker, PoolPartition.State.LEASED);
            queueRetirementLocked(worker, reason, effects);
            lease.clear(worker);
            changedLocked();
        }
    }

    void retireLeaseIfOwned(
            Lease<S> lease,
            PooledWorkerRetireReason reason,
            boolean closedTakesPrecedence,
            PoolStateEffects<S> effects) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(reason, "reason");
        requireEffects(effects);
        synchronized (monitor) {
            PoolWorker<S> worker = lease.workerIfOwnedBy(this);
            if (worker == null) {
                return;
            }
            PoolPartition.State current = partition.stateOf(worker);
            if (current == null) {
                lease.clear(worker);
                return;
            }
            PooledWorkerRetireReason effectiveReason =
                    closedTakesPrecedence && termination.closing() ? PooledWorkerRetireReason.CLOSED : reason;
            switch (current) {
                case LEASED, IDLE -> queueRetirementLocked(worker, effectiveReason, effects);
                case RETIRING -> {}
                case STARTING -> throw new IllegalStateException("lease still owns a starting worker");
            }
            lease.clear(worker);
            if (current != PoolPartition.State.RETIRING) {
                changedLocked();
            }
        }
    }

    FailureReport completeRetirement(
            PoolWorker<S> worker,
            WorkerRetirement.Outcome outcome,
            FailureReport closeFailureReport,
            PoolStateEffects<S> effects) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(outcome, "outcome");
        requireEffects(effects);
        synchronized (monitor) {
            partition.requireState(worker, PoolPartition.State.RETIRING);
            removeSlotLocked(worker, effects);
            Throwable closeFailure = outcome.failure();
            metrics.workerRetired(worker.retireReason(), closeFailure != null);
            FailureReport lateReport = null;
            if (closeFailure != null) {
                enterClosingLocked(closeFailure, effects);
                lateReport = termination.routeWorkerCloseFailure(
                        Objects.requireNonNull(closeFailureReport, "closeFailureReport"));
            } else if (closeFailureReport != null) {
                throw new IllegalArgumentException("successful retirement cannot carry a failure report");
            }
            changedLocked();
            effects.publish(claimDrainLocked());
            return lateReport;
        }
    }

    void failRetirementCompletion(PoolWorker<S> worker, Throwable failure, PoolStateEffects<S> effects) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(failure, "failure");
        requireEffects(effects);
        synchronized (monitor) {
            enterClosingLocked(failure, effects);
            if (partition.contains(worker) && partition.is(worker, PoolPartition.State.RETIRING)) {
                removeSlotLocked(worker, effects);
                metrics.workerRetired(worker.retireReason(), true);
            }
            changedLocked();
            effects.publish(claimDrainLocked());
        }
    }

    boolean replenishmentNeeded() {
        synchronized (monitor) {
            return !termination.closing() && needsReplenishmentLocked();
        }
    }

    boolean awaitBackoff(Duration backoff) {
        Objects.requireNonNull(backoff, "backoff");
        if (backoff.isZero()) {
            return true;
        }
        long deadlineNanos = DurationSupport.deadlineFromNow(backoff);
        synchronized (monitor) {
            while (!termination.closing()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return true;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    PoolMetrics.Snapshot metrics() {
        synchronized (monitor) {
            return metricsLocked();
        }
    }

    boolean awaitMetrics(Predicate<PoolMetrics.Snapshot> condition, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        long deadlineNanos = DurationSupport.deadlineFromNow(DurationSupport.requirePositive(timeout, "timeout"));
        while (true) {
            PoolMetrics.Snapshot snapshot;
            long observedRevision;
            synchronized (monitor) {
                snapshot = metricsLocked();
                observedRevision = revision;
            }
            if (condition.test(snapshot)) {
                return true;
            }
            synchronized (monitor) {
                if (revision != observedRevision) {
                    continue;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos);
            }
        }
    }

    void recordAcquireWait(long elapsedNanos) {
        synchronized (monitor) {
            metrics.acquired(elapsedNanos);
            changedLocked();
        }
    }

    void recordRequest(boolean successful, long durationNanos) {
        synchronized (monitor) {
            metrics.requestCompleted(successful, durationNanos);
            changedLocked();
        }
    }

    FailureReport routeLateFailure(FailureReport report) {
        synchronized (monitor) {
            return termination.routeLateFailure(Objects.requireNonNull(report, "report"));
        }
    }

    CompletableFuture<Void> terminationView() {
        return termination.view();
    }

    void publish(PoolDrain.Publication publication) {
        if (publication != null) {
            termination.publish(publication);
        }
    }

    private void requireEffects(PoolStateEffects<S> effects) {
        Objects.requireNonNull(effects, "effects").requireOwner(this);
    }

    private WorkerStartupCoordinator.Reservation<S> prepareReservationLocked(PoolWorker.StartupPurpose purpose) {
        WorkerStartupCoordinator.Reservation<S> reservation =
                Objects.requireNonNull(reservations.create(purpose), "startup reservation factory returned null");
        PoolWorker<S> worker = reservation.worker();
        reservation.bind(this, new Lease<>(this, worker));
        return reservation;
    }

    private void commitReservationLocked(WorkerStartupCoordinator.Reservation<S> reservation) {
        partition.addStarting(reservation.reservedWorker(this));
        changedLocked();
    }

    private boolean needsReplenishmentLocked() {
        int replenishingStarts = 0;
        for (PoolWorker<S> worker : partition.startingWorkers()) {
            if (worker.startupPurpose() == PoolWorker.StartupPurpose.REPLENISHMENT) {
                replenishingStarts++;
            }
        }
        return policy.needsReplenishment(partition.size(), partition.idleCount(), replenishingStarts);
    }

    private PoolMetrics.Snapshot metricsLocked() {
        PoolPartition.Counts counts = partition.counts();
        return metrics.snapshot(counts.size(), counts.idle(), counts.leased(), counts.starting(), counts.retiring());
    }

    private PoolTermination.FailureDisposition enterClosingLocked(Throwable failure, PoolStateEffects<S> effects) {
        List<PoolWorker<S>> startingWorkers = partition.startingWorkers();
        List<PoolWorker<S>> idleWorkers = partition.idleWorkers();
        PoolTermination.FailureDisposition disposition = termination.beginClosing(failure);
        for (PoolWorker<S> worker : startingWorkers) {
            if (partition.is(worker, PoolPartition.State.STARTING)) {
                worker.startup().signalClosed();
            }
        }

        for (PoolWorker<S> worker : idleWorkers) {
            markRetiringLocked(worker, PooledWorkerRetireReason.CLOSED);
            effects.retire(worker);
        }

        for (PoolWorker<S> worker : startingWorkers) {
            if (partition.is(worker, PoolPartition.State.STARTING)
                    && worker.startupStage() == PoolWorker.StartupStage.QUEUED) {
                removeSlotLocked(worker, effects);
            }
        }
        return disposition;
    }

    private void queueRetirementLocked(
            PoolWorker<S> worker, PooledWorkerRetireReason reason, PoolStateEffects<S> effects) {
        markRetiringLocked(worker, reason);
        effects.retire(worker);
    }

    private void markRetiringLocked(PoolWorker<S> worker, PooledWorkerRetireReason reason) {
        switch (Objects.requireNonNull(partition.stateOf(worker), "worker state")) {
            case STARTING -> partition.startingToRetiring(worker);
            case IDLE -> partition.idleToRetiring(worker);
            case LEASED -> partition.leasedToRetiring(worker);
            case RETIRING -> throw new IllegalStateException("worker state must not already be RETIRING");
        }
        worker.retireReason(Objects.requireNonNull(reason, "reason"));
    }

    private boolean removeSlotLocked(PoolWorker<S> worker, PoolStateEffects<S> effects) {
        PoolPartition.State state = partition.stateOf(worker);
        if (state == null) {
            return false;
        }
        if (state == PoolPartition.State.IDLE || state == PoolPartition.State.LEASED) {
            throw new IllegalStateException("cannot remove live worker in state " + state);
        }
        BoundedTaskPermit permit = worker.workerPermitOrNull();
        effects.release(permit);
        BoundedTaskPermit detached = worker.detachWorkerPermit();
        if (detached != permit) {
            throw new IllegalStateException("worker permit changed while removing its slot");
        }
        if (state == PoolPartition.State.STARTING) {
            partition.removeStarting(worker);
        } else {
            partition.removeRetiring(worker);
        }
        return true;
    }

    private PoolDrain.Publication claimDrainLocked() {
        return termination.claimDrainIfReady(partition.size());
    }

    private void changedLocked() {
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalStateException("pool state changes must be published under the pool monitor");
        }
        revision++;
        monitor.notifyAll();
    }

    static final class Lease<S> {

        private final WorkerPoolState<S> owner;
        private PoolWorker<S> worker;

        private Lease(WorkerPoolState<S> owner, PoolWorker<S> worker) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.worker = Objects.requireNonNull(worker, "worker");
        }

        S session() {
            return requireWorker(owner).session();
        }

        private PoolWorker<S> requireWorker(WorkerPoolState<S> expectedOwner) {
            PoolWorker<S> owned = workerIfOwnedBy(expectedOwner);
            if (owned == null) {
                throw new IllegalStateException("lease owns no worker for this pool");
            }
            return owned;
        }

        private PoolWorker<S> workerIfOwnedBy(WorkerPoolState<S> expectedOwner) {
            return owner == expectedOwner ? worker : null;
        }

        void clear(PoolWorker<S> expectedWorker) {
            if (worker != expectedWorker) {
                throw new IllegalStateException("lease ownership changed");
            }
            worker = null;
        }
    }

    sealed interface AcquireResult<S>
            permits LeaseAcquired, StartupReserved, RetryAcquire, AcquireClosed, AcquireTimedOut, AcquireInterrupted {

        private static <S> AcquireResult<S> leased(Lease<S> lease) {
            return new LeaseAcquired<>(lease);
        }

        private static <S> AcquireResult<S> reserved(WorkerStartupCoordinator.Reservation<S> reservation) {
            return new StartupReserved<>(reservation);
        }

        private static <S> AcquireResult<S> retry() {
            return new RetryAcquire<>();
        }

        private static <S> AcquireResult<S> closed() {
            return new AcquireClosed<>();
        }

        private static <S> AcquireResult<S> timedOut() {
            return new AcquireTimedOut<>();
        }

        private static <S> AcquireResult<S> interrupted(InterruptedException failure) {
            return new AcquireInterrupted<>(failure);
        }
    }

    record LeaseAcquired<S>(Lease<S> lease) implements AcquireResult<S> {

        LeaseAcquired {
            Objects.requireNonNull(lease, "lease");
        }
    }

    record StartupReserved<S>(WorkerStartupCoordinator.Reservation<S> reservation) implements AcquireResult<S> {

        StartupReserved {
            Objects.requireNonNull(reservation, "reservation");
        }
    }

    record RetryAcquire<S>() implements AcquireResult<S> {}

    record AcquireClosed<S>() implements AcquireResult<S> {}

    record AcquireTimedOut<S>() implements AcquireResult<S> {}

    record AcquireInterrupted<S>(InterruptedException interruption) implements AcquireResult<S> {

        AcquireInterrupted {
            Objects.requireNonNull(interruption, "interruption");
        }
    }

    sealed interface ReservationResult<S> permits SlotReserved, ReservationClosed {

        private static <S> ReservationResult<S> reserved(WorkerStartupCoordinator.Reservation<S> reservation) {
            return new SlotReserved<>(reservation);
        }

        private static <S> ReservationResult<S> closed() {
            return new ReservationClosed<>();
        }
    }

    record SlotReserved<S>(WorkerStartupCoordinator.Reservation<S> reservation) implements ReservationResult<S> {

        SlotReserved {
            Objects.requireNonNull(reservation, "reservation");
        }
    }

    record ReservationClosed<S>() implements ReservationResult<S> {}

    @FunctionalInterface
    interface StartupReservationFactory<S> {

        /** Creates internal owners only; implementations must not launch work or invoke user code. */
        WorkerStartupCoordinator.Reservation<S> create(PoolWorker.StartupPurpose purpose);
    }
}

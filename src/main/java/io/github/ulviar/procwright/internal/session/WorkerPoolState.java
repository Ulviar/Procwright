/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns pool-level mutable invariants serialized by one worker-pool monitor.
 *
 * <p>Each transition selects its mandatory post-commit work under the monitor and executes that
 * work after releasing it. Physical close, terminal publication, and failure reporting never run
 * under this monitor.
 */
final class WorkerPoolState<S> {

    private final Object monitor = new Object();
    private final WorkerPoolPolicy policy;
    private final PoolPartition<PoolWorker<S>> partition;
    private final PoolMetrics metrics = new PoolMetrics();
    private final PoolTermination termination = new PoolTermination();
    private final Function<PoolWorker.StartupPurpose, PoolWorker<S>> workers;
    private final WorkerRetirementCoordinator<S> retirements;

    WorkerPoolState(
            WorkerPoolPolicy policy,
            Function<PoolWorker.StartupPurpose, PoolWorker<S>> workers,
            WorkerRetirementCoordinator<S> retirements) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
        partition = new PoolPartition<>(policy.maxSize());
    }

    PoolTermination.ConstructionResult finishConstruction() {
        synchronized (monitor) {
            return termination.finishConstruction();
        }
    }

    List<FailureReport> failConstructionAndClose() {
        return transition(effects -> {
            List<FailureReport> reports = termination.failConstruction();
            enterClosingLocked(null, effects);
            signalWaitersLocked();
            effects.publish(claimDrainLocked());
            return reports;
        });
    }

    PoolTermination.FailureDisposition beginClose(Throwable failure) {
        return transition(effects -> {
            PoolTermination.FailureDisposition disposition = enterClosingLocked(failure, effects);
            signalWaitersLocked();
            effects.publish(claimDrainLocked());
            return disposition;
        });
    }

    AcquireResult<S> awaitAcquire(long deadlineNanos) {
        while (true) {
            AcquireResult<S> result = transition(effects -> {
                if (termination.closing()) {
                    return AcquireResult.closed();
                }
                PoolWorker<S> idle = partition.firstIdle();
                if (idle != null) {
                    PooledWorkerRetireReason reason = policy.retirementReasonFor(idle);
                    if (reason != null) {
                        queueRetirementLocked(idle, reason, effects);
                        return null;
                    }
                    Lease<S> lease = new Lease<>(this, idle);
                    AcquireResult<S> outcome = AcquireResult.leased(lease);
                    PoolWorker<S> leased = partition.leaseIdle();
                    if (leased != idle) {
                        lease.clear(idle);
                        throw new IllegalStateException("pool idle order changed while leasing");
                    }
                    return outcome;
                }
                if (partition.hasCapacity()) {
                    PoolWorker<S> worker = prepareWorkerLocked(PoolWorker.StartupPurpose.DEMAND);
                    AcquireResult<S> outcome = AcquireResult.reserved(worker);
                    commitWorkerLocked(worker);
                    return outcome;
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
                return null;
            });
            if (result != null) {
                return result;
            }
        }
    }

    PoolWorker<S> reserveWarmup() {
        synchronized (monitor) {
            if (termination.closing()) {
                return null;
            }
            if (!partition.hasCapacity()) {
                throw new IllegalStateException("pool capacity exhausted while reserving warmup slot");
            }
            PoolWorker<S> worker = prepareWorkerLocked(PoolWorker.StartupPurpose.WARMUP);
            commitWorkerLocked(worker);
            return worker;
        }
    }

    PoolWorker<S> tryReserveReplenishment() {
        synchronized (monitor) {
            if (termination.closing() || !needsReplenishmentLocked()) {
                return null;
            }
            PoolWorker<S> worker = prepareWorkerLocked(PoolWorker.StartupPurpose.REPLENISHMENT);
            commitWorkerLocked(worker);
            return worker;
        }
    }

    WorkerStartupCoordinator.StartupDecision preflightStartup(PoolWorker<S> worker, long deadlineNanos) {
        Objects.requireNonNull(worker, "worker");
        synchronized (monitor) {
            if (!partition.contains(worker) || termination.closing()) {
                worker.startup().signalClosed();
                return WorkerStartupCoordinator.StartupDecision.CLOSED;
            }
            partition.requireState(worker, PoolPartition.State.STARTING);
            if (deadlineNanos - System.nanoTime() <= 0) {
                worker.startup().signalTimeout();
                return WorkerStartupCoordinator.StartupDecision.TIMED_OUT;
            }
            return WorkerStartupCoordinator.StartupDecision.RUN;
        }
    }

    void discardStartingWorker(PoolWorker<S> worker) {
        Objects.requireNonNull(worker, "worker");
        runTransition(effects -> {
            PoolPartition.State current = partition.stateOf(worker);
            if (current == PoolPartition.State.STARTING) {
                partition.removeStarting(worker);
                signalWaitersLocked();
            } else if (current != null) {
                throw new IllegalStateException("only a starting worker can be discarded");
            }
            effects.publish(claimDrainLocked());
        });
    }

    boolean factoryFailed(PoolWorker<S> worker, Throwable failure) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(failure, "failure");
        return transition(effects -> {
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
            removeSlotLocked(worker);
            metrics.startupFailed();
            signalWaitersLocked();
            effects.publish(claimDrainLocked());
            return closedStartup;
        });
    }

    Lease<S> completeStartup(PoolWorker<S> worker, WorkerStartup.CreatedWorker<S> createdWorker) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(createdWorker, "createdWorker");
        return transition(effects -> {
            if (!partition.contains(worker)) {
                if (!worker.detachedStartup()) {
                    throw new IllegalStateException("completed startup no longer belongs to the pool");
                }
                worker.accept(createdWorker.session(), createdWorker.startupNanos());
                worker.retireReason(PooledWorkerRetireReason.CLOSED);
                effects.retire(worker);
                return null;
            }
            partition.requireState(worker, PoolPartition.State.STARTING);
            worker.accept(createdWorker.session(), createdWorker.startupNanos());
            metrics.workerCreated(createdWorker.startupNanos());
            partition.startingToLeased(worker);
            return new Lease<>(this, worker);
        });
    }

    boolean completeAbandonedStartup(PoolWorker<S> worker, WorkerStartup.LateCompletion<S> completion) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(completion, "completion");
        return transition(effects -> {
            if (!partition.contains(worker)) {
                if (worker.detachedStartup()) {
                    completeDetachedStartupLocked(worker, completion, effects);
                }
                return worker.detachedStartup();
            }
            metrics.startupFailed();
            if (completion.session() == null) {
                removeSlotLocked(worker);
                signalWaitersLocked();
            } else {
                effects.retire(worker);
                worker.accept(completion.session(), completion.startupNanos());
                metrics.workerCreated(completion.startupNanos());
                markRetiringLocked(worker, completion.reason());
            }
            effects.publish(claimDrainLocked());
            return true;
        });
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

    void releaseReusable(Lease<S> lease) {
        Objects.requireNonNull(lease, "lease");
        runTransition(effects -> {
            PoolWorker<S> worker = lease.requireWorker(this);
            partition.requireState(worker, PoolPartition.State.LEASED);
            PooledWorkerRetireReason reason =
                    termination.closing() ? PooledWorkerRetireReason.CLOSED : policy.retirementReasonFor(worker);
            if (reason == null) {
                partition.leasedToIdle(worker);
                signalWaitersLocked();
            } else {
                queueRetirementLocked(worker, reason, effects);
            }
            lease.clear(worker);
        });
    }

    void retire(Lease<S> lease, PooledWorkerRetireReason reason) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(reason, "reason");
        runTransition(effects -> {
            PoolWorker<S> worker = lease.requireWorker(this);
            partition.requireState(worker, PoolPartition.State.LEASED);
            queueRetirementLocked(worker, reason, effects);
            lease.clear(worker);
        });
    }

    void retireLeaseIfOwned(Lease<S> lease, PooledWorkerRetireReason reason, boolean closedTakesPrecedence) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(reason, "reason");
        runTransition(effects -> {
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
        });
    }

    FailureReport completeRetirement(
            PoolWorker<S> worker, WorkerRetirement.Outcome outcome, FailureReport closeFailureReport) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(outcome, "outcome");
        return transition(effects -> {
            boolean detached = !partition.contains(worker) && worker.detachedStartup();
            if (!detached) {
                partition.requireState(worker, PoolPartition.State.RETIRING);
                removeSlotLocked(worker);
            }
            Throwable closeFailure = outcome.failure();
            if (detached) {
                metrics.lateWorkerRetired(worker.startupNanos(), worker.retireReason(), closeFailure != null);
            } else {
                metrics.workerRetired(worker.retireReason(), closeFailure != null);
            }
            FailureReport lateReport = null;
            if (closeFailure != null) {
                if (!detached) {
                    enterClosingLocked(closeFailure, effects);
                }
                lateReport = termination.routeWorkerCloseFailure(
                        Objects.requireNonNull(closeFailureReport, "closeFailureReport"));
            } else if (closeFailureReport != null) {
                throw new IllegalArgumentException("successful retirement cannot carry a failure report");
            }
            if (!detached) {
                signalWaitersLocked();
                effects.publish(claimDrainLocked());
            }
            return lateReport;
        });
    }

    void failRetirementCompletion(PoolWorker<S> worker, Throwable failure) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(failure, "failure");
        runTransition(effects -> {
            enterClosingLocked(failure, effects);
            if (!partition.contains(worker) && worker.detachedStartup()) {
                metrics.lateWorkerRetired(worker.startupNanos(), worker.retireReason(), true);
            } else if (partition.contains(worker) && partition.is(worker, PoolPartition.State.RETIRING)) {
                removeSlotLocked(worker);
                metrics.workerRetired(worker.retireReason(), true);
            }
            signalWaitersLocked();
            effects.publish(claimDrainLocked());
        });
    }

    boolean replenishmentNeeded() {
        synchronized (monitor) {
            return !termination.closing() && needsReplenishmentLocked();
        }
    }

    PooledSessionMetrics metrics() {
        synchronized (monitor) {
            return metricsLocked();
        }
    }

    void recordAcquireWait(long elapsedNanos) {
        synchronized (monitor) {
            metrics.acquired(elapsedNanos);
        }
    }

    void recordRequest(boolean successful, long durationNanos) {
        synchronized (monitor) {
            metrics.requestCompleted(successful, durationNanos);
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

    private PoolWorker<S> prepareWorkerLocked(PoolWorker.StartupPurpose purpose) {
        return Objects.requireNonNull(workers.apply(purpose), "startup worker factory returned null");
    }

    private void commitWorkerLocked(PoolWorker<S> worker) {
        partition.addStarting(worker);
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

    private PooledSessionMetrics metricsLocked() {
        return metrics.snapshot(partition.counts());
    }

    private PoolTermination.FailureDisposition enterClosingLocked(Throwable failure, PostCommit<S> effects) {
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
            if (partition.is(worker, PoolPartition.State.STARTING)) {
                worker.detachStartup();
                removeSlotLocked(worker);
            }
        }
        return disposition;
    }

    private void completeDetachedStartupLocked(
            PoolWorker<S> worker, WorkerStartup.LateCompletion<S> completion, PostCommit<S> effects) {
        if (completion.failure() != null || completion.reason() != PooledWorkerRetireReason.CLOSED) {
            metrics.startupFailed();
        }
        if (completion.session() == null) {
            return;
        }
        worker.accept(completion.session(), completion.startupNanos());
        worker.retireReason(completion.reason());
        effects.retire(worker);
    }

    private void queueRetirementLocked(PoolWorker<S> worker, PooledWorkerRetireReason reason, PostCommit<S> effects) {
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

    private boolean removeSlotLocked(PoolWorker<S> worker) {
        PoolPartition.State state = partition.stateOf(worker);
        if (state == null) {
            return false;
        }
        if (state == PoolPartition.State.IDLE || state == PoolPartition.State.LEASED) {
            throw new IllegalStateException("cannot remove live worker in state " + state);
        }
        if (state == PoolPartition.State.STARTING) {
            partition.removeStarting(worker);
        } else {
            partition.removeRetiring(worker);
        }
        return true;
    }

    private PoolTermination.Publication claimDrainLocked() {
        return termination.claimDrainIfReady(partition.size());
    }

    private <T> T transition(Function<PostCommit<S>, T> operation) {
        Objects.requireNonNull(operation, "operation");
        PostCommit<S> effects = new PostCommit<>();
        T result = null;
        Throwable failure = null;
        try {
            synchronized (monitor) {
                result = operation.apply(effects);
            }
        } catch (RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }
        try {
            runPostCommit(effects);
        } catch (RuntimeException | Error effectsFailure) {
            failure = combine(failure, effectsFailure, "Pool state transition and its post-commit work both failed");
        }
        if (failure != null) {
            rethrow(failure);
        }
        return result;
    }

    private void runTransition(Consumer<PostCommit<S>> operation) {
        transition(effects -> {
            operation.accept(effects);
            return null;
        });
    }

    private void runPostCommit(PostCommit<S> effects) {
        Throwable failure = null;
        if (effects.workersToRetire != null) {
            try {
                retirements.dispatch(effects.workersToRetire);
            } catch (RuntimeException | Error retirementFailure) {
                failure = retirementFailure;
            }
        }
        if (effects.publication != null) {
            try {
                effects.publication.publish();
            } catch (RuntimeException | Error publicationFailure) {
                failure = combine(
                        failure, publicationFailure, "Pool retirement dispatch and terminal publication both failed");
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static Throwable combine(Throwable first, Throwable second, String message) {
        FailureAccumulator failures = new FailureAccumulator();
        failures.add(first);
        failures.add(second);
        return failures.aggregateErrorFirst(message);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        throw (RuntimeException) failure;
    }

    private void signalWaitersLocked() {
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalStateException("pool state changes must be published under the pool monitor");
        }
        monitor.notifyAll();
    }

    private static final class PostCommit<S> {

        private ArrayList<PoolWorker<S>> workersToRetire;
        private PoolTermination.Publication publication;

        private void retire(PoolWorker<S> worker) {
            if (workersToRetire == null) {
                workersToRetire = new ArrayList<>();
            }
            workersToRetire.add(Objects.requireNonNull(worker, "worker"));
        }

        private void publish(PoolTermination.Publication selected) {
            if (selected == null) {
                return;
            }
            if (publication != null) {
                throw new IllegalStateException("pool transition selected more than one terminal publication");
            }
            publication = selected;
        }
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
            permits LeaseAcquired, StartupReserved, AcquireClosed, AcquireTimedOut, AcquireInterrupted {

        private static <S> AcquireResult<S> leased(Lease<S> lease) {
            return new LeaseAcquired<>(lease);
        }

        private static <S> AcquireResult<S> reserved(PoolWorker<S> worker) {
            return new StartupReserved<>(worker);
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

    record StartupReserved<S>(PoolWorker<S> worker) implements AcquireResult<S> {

        StartupReserved {
            Objects.requireNonNull(worker, "worker");
        }
    }

    record AcquireClosed<S>() implements AcquireResult<S> {}

    record AcquireTimedOut<S>() implements AcquireResult<S> {}

    record AcquireInterrupted<S>(InterruptedException interruption) implements AcquireResult<S> {

        AcquireInterrupted {
            Objects.requireNonNull(interruption, "interruption");
        }
    }
}

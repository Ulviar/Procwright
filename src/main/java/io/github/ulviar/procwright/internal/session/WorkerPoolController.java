/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class WorkerPoolController<S> {

    private final Supplier<S> workerFactory;
    private final WorkerRetirement.Action<S> workerCloser;
    private final WorkerPoolPolicy policy;
    private final FailureFactory failures;
    private final String workerLabel;
    private final String threadPrefix;
    private final PoolFailurePublisher failurePublisher;
    private final NanoClock metricsClock;
    private final RetirementAdmissionProvider retirementAdmissions;
    private final PoolPartition<PoolWorker<S>> partition;
    private final ConstructionLedger construction = new ConstructionLedger();
    private final PoolMetrics cumulativeMetrics = new PoolMetrics();
    private final Object lock = new Object();
    private final PoolDrain drain;
    private final PoolReplenisher replenisher;
    private final WorkerRetirementCoordinator<S> retirements;
    private final WorkerStartupCoordinator<S> startups;

    private boolean closing;
    private long stateRevision;
    private final WorkerCloseFailureAccumulator drainFailures = new WorkerCloseFailureAccumulator();

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerRetirement.Action<S> workerCloser,
            WorkerPoolPolicy.Options options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                Dependencies.defaults(threadPrefix, System::nanoTime));
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerRetirement.Action<S> workerCloser,
            WorkerPoolPolicy.Options options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            NanoClock metricsClock) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                Dependencies.defaults(threadPrefix, metricsClock));
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerRetirement.Action<S> workerCloser,
            WorkerPoolPolicy.Options options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Dependencies dependencies) {
        Dependencies configuredDependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.workerCloser = Objects.requireNonNull(workerCloser, "workerCloser");
        policy = new WorkerPoolPolicy(options);
        partition = new PoolPartition<>(policy.maxSize());
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        failurePublisher = new PoolFailurePublisher(configuredDependencies.lateFailureReporter());
        this.metricsClock = configuredDependencies.metricsClock();
        this.retirementAdmissions = configuredDependencies.retirementAdmissions();
        PoolReplenisher.Waiter configuredWaiter = configuredDependencies.backoffWaiter() == null
                ? this::awaitBackoffWithLock
                : configuredDependencies.backoffWaiter()::await;
        replenisher = new PoolReplenisher(
                policy.replenishmentEnabled(),
                configuredDependencies.replenishmentStarter(),
                this::replenishmentNeeded,
                this::replenishOne,
                configuredWaiter,
                this::failReplenishmentOwner);
        retirements = new WorkerRetirementCoordinator<>(
                task -> PoolLifecycleDispatcher.executeRetirementBatch(task),
                this::processRetirement,
                this::completeUnexpectedRetirementFailure,
                failurePublisher::publish);
        startups = new WorkerStartupCoordinator<>(failures, workerLabel, retirementAdmissions, new StartupPoolState());
        try {
            drain = new PoolDrain(configuredDependencies.terminalPublications().reserve());
        } catch (RuntimeException failure) {
            throw Objects.requireNonNull(
                    failures.startupFailed("Could not reserve pool terminal publication capacity", failure),
                    "startup failure");
        }
        List<FailureReport> commitReports = List.of();
        try {
            warmup();
            ensureReplenishmentOwner();
            boolean failedDuringConstruction;
            Throwable constructionFailure;
            synchronized (lock) {
                failedDuringConstruction = closing;
                constructionFailure = drainFailures.failure();
                if (!failedDuringConstruction) {
                    commitReports = construction.commit();
                }
            }
            if (failedDuringConstruction) {
                if (constructionFailure instanceof Error error) {
                    throw error;
                }
                throw failures.retirementFailed("Pool failed during construction", constructionFailure);
            }
        } catch (RuntimeException | Error failure) {
            cleanupFailedConstruction(failure);
            throw failure;
        }
        failurePublisher.publishAll(commitReports);
    }

    PoolWorker<S> acquire(HealthCheck<S> healthCheck) {
        Objects.requireNonNull(healthCheck, "healthCheck");
        long startedAtNanos = metricsClock.nanoTime();
        long deadlineNanos = DurationSupport.deadlineFromNow(policy.acquireTimeout());
        LeaseHandoff handoff = new LeaseHandoff();
        WorkerStartupCoordinator.Reservation<S> startupReservation = startups.newReservation();
        boolean acquireWaitAttempted = false;
        try {
            while (true) {
                AcquireSelection<S> selection = takeOrReserveWorker(deadlineNanos, handoff, startupReservation);
                PoolWorker<S> worker = selection.worker();
                if (selection.startup()) {
                    worker = openReservedWorker(
                            startupReservation, deadlineNanos, PoolWorker.StartupPurpose.DEMAND, handoff);
                }
                ensureReplenishmentOwner();

                if (deadlineNanos - System.nanoTime() <= 0) {
                    throw acquireTimeoutAfterReturning(handoff);
                }
                HealthOutcome healthOutcome;
                try {
                    healthOutcome = Objects.requireNonNull(
                            healthCheck.test(worker.session(), deadlineNanos), "healthCheck returned null");
                } catch (RuntimeException | Error failure) {
                    handoff.fail(failure, PooledWorkerRetireReason.HEALTH_FAILED);
                    throw failure;
                }
                switch (healthOutcome) {
                    case HEALTHY -> {
                        if (deadlineNanos - System.nanoTime() <= 0) {
                            throw acquireTimeoutAfterReturning(handoff);
                        }
                        acquireWaitAttempted = true;
                        recordAcquireWait(startedAtNanos);
                        return handoff.complete();
                    }
                    case ACQUIRE_TIMEOUT -> throw acquireTimeoutAfterReturning(handoff);
                    case HEALTH_FAILED -> handoff.retire(PooledWorkerRetireReason.HEALTH_FAILED);
                    case PROCESS_EXITED -> handoff.retire(PooledWorkerRetireReason.PROCESS_EXITED);
                }
            }
        } catch (RuntimeException | Error failure) {
            failStartupReservation(startupReservation, failure);
            handoff.fail(failure);
            if (!acquireWaitAttempted) {
                try {
                    recordAcquireWait(startedAtNanos);
                } catch (RuntimeException | Error metricsFailure) {
                    SuppressionSupport.attach(failure, metricsFailure);
                }
            }
            throw failure;
        }
    }

    void release(PoolWorker<S> worker, boolean reusable, PooledWorkerRetireReason failureReason) {
        Objects.requireNonNull(worker, "worker");
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        try {
            synchronized (lock) {
                requireState(worker, PoolPartition.State.LEASED);
                PooledWorkerRetireReason reason = reusable ? retireReasonForPolicy(worker) : failureReason;
                if (closing && reason == null) {
                    reason = PooledWorkerRetireReason.CLOSED;
                }
                if (reason == null) {
                    partition.leasedToIdle(worker);
                } else {
                    markRetiring(worker, reason, retirementBatch);
                }
                stateChangedLocked();
            }
        } finally {
            retirementBatch.dispatch();
        }
        ensureReplenishmentOwner();
        publishDrainIfReady();
    }

    PooledWorkerRetireReason retirementReasonFor(PoolWorker<S> worker) {
        Objects.requireNonNull(worker, "worker");
        synchronized (lock) {
            requireState(worker, PoolPartition.State.LEASED);
            return closing ? PooledWorkerRetireReason.CLOSED : retireReasonForPolicy(worker);
        }
    }

    RequestObservation observeRequest() {
        return new RequestObservation(metricsClock::nanoTime, this::completeRequest);
    }

    PoolMetrics.Snapshot metrics() {
        synchronized (lock) {
            return metricsLocked();
        }
    }

    boolean awaitMetrics(Predicate<PoolMetrics.Snapshot> condition, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        long deadlineNanos = DurationSupport.deadlineFromNow(DurationSupport.requirePositive(timeout, "timeout"));
        while (true) {
            PoolMetrics.Snapshot snapshot;
            long observedRevision;
            synchronized (lock) {
                snapshot = metricsLocked();
                observedRevision = stateRevision;
            }
            if (condition.test(snapshot)) {
                return true;
            }
            synchronized (lock) {
                if (stateRevision != observedRevision) {
                    continue;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
            }
        }
    }

    private PoolMetrics.Snapshot metricsLocked() {
        PoolPartition.Counts counts = partition.counts();
        return cumulativeMetrics.snapshot(
                counts.size(), counts.idle(), counts.leased(), counts.starting(), counts.retiring());
    }

    private void stateChangedLocked() {
        if (!Thread.holdsLock(lock)) {
            throw new IllegalStateException("pool state changes must be published under the pool monitor");
        }
        stateRevision++;
        lock.notifyAll();
    }

    CompletableFuture<Void> closeAsync() {
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        DrainPublication publication;
        try {
            synchronized (lock) {
                enterClosingLocked(null, retirementBatch);
                publication = drainPublication();
            }
        } finally {
            retirementBatch.dispatch();
        }
        publish(publication);
        return drain.view();
    }

    private void warmup() {
        for (int index = 0; index < policy.warmupSize(); index++) {
            LeaseHandoff handoff = new LeaseHandoff();
            WorkerStartupCoordinator.Reservation<S> startupReservation = startups.newReservation();
            try {
                reserveSlot(startupReservation);
                openReservedWorker(
                        startupReservation,
                        DurationSupport.deadlineFromNow(policy.acquireTimeout()),
                        PoolWorker.StartupPurpose.WARMUP,
                        handoff);
                handoff.transferToPoolLifecycle();
            } catch (RuntimeException | Error failure) {
                failStartupReservation(startupReservation, failure);
                handoff.fail(failure);
                throw failure;
            }
        }
    }

    private void cleanupFailedConstruction(Throwable primary) {
        List<FailureReport> completedFailures;
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        try {
            synchronized (lock) {
                completedFailures = construction.fail();
                enterClosingLocked(null, retirementBatch);
            }
        } finally {
            retirementBatch.dispatch();
        }
        publishDrainIfReady();
        awaitFailedConstructionCleanup(primary, completedFailures);
    }

    private void awaitFailedConstructionCleanup(Throwable primary, List<FailureReport> completedFailures) {
        WorkerCloseFailureAccumulator cleanupFailures = new WorkerCloseFailureAccumulator();
        completedFailures.forEach(report -> cleanupFailures.add(report.failure()));
        long deadlineNanos = DurationSupport.deadlineFromNow(policy.closeTimeout());
        boolean restoreInterrupt = false;
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("pool construction cleanup deadline elapsed");
            }
            drain.view().get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            cleanupFailures.add(failure);
        } catch (InterruptedException failure) {
            restoreInterrupt = true;
            cleanupFailures.add(failure);
        } catch (ExecutionException failure) {
            cleanupFailures.add(PoolFailurePublisher.unwrap(failure.getCause()));
        } finally {
            Throwable cleanupFailure = cleanupFailures.failure();
            if (cleanupFailure != null) {
                SuppressionSupport.attach(
                        primary,
                        failures.retirementFailed(
                                "Pool construction cleanup did not complete cleanly", cleanupFailure));
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private AcquireSelection<S> takeOrReserveWorker(
            long deadlineNanos, LeaseHandoff handoff, WorkerStartupCoordinator.Reservation<S> startupReservation) {
        while (true) {
            boolean retirementQueued = false;
            PoolWorker<S> selected = null;
            boolean startup = false;
            AcquireWaitFailure waitFailure = null;
            InterruptedException waitInterruption = null;
            WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
            try {
                synchronized (lock) {
                    if (closing) {
                        waitFailure = AcquireWaitFailure.CLOSED;
                    } else {
                        PoolWorker<S> worker = partition.leaseIdle();
                        if (worker != null) {
                            PooledWorkerRetireReason reason = retireReasonForPolicy(worker);
                            if (reason == null) {
                                ownLeased(worker, handoff);
                                selected = worker;
                            } else {
                                markRetiring(worker, reason, retirementBatch);
                                retirementQueued = true;
                            }
                        } else if (partition.size() < policy.maxSize()) {
                            selected = reserveSlotLocked(startupReservation);
                            startup = true;
                        } else {
                            long remainingNanos = deadlineNanos - System.nanoTime();
                            if (remainingNanos <= 0) {
                                waitFailure = AcquireWaitFailure.TIMED_OUT;
                            } else {
                                try {
                                    TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    waitFailure = AcquireWaitFailure.INTERRUPTED;
                                    waitInterruption = exception;
                                }
                            }
                        }
                    }
                }
            } finally {
                retirementBatch.dispatch();
            }
            if (waitFailure != null) {
                throw switch (waitFailure) {
                    case CLOSED -> failures.closed("Pool is closed");
                    case TIMED_OUT -> failures.acquireTimeout("Timed out waiting for " + workerLabel);
                    case INTERRUPTED ->
                        failures.acquireInterrupted(
                                "Interrupted while waiting for " + workerLabel,
                                Objects.requireNonNull(waitInterruption, "waitInterruption"));
                };
            }
            if (retirementQueued) {
                continue;
            }
            if (selected != null) {
                return new AcquireSelection<>(selected, startup);
            }
        }
    }

    private PoolWorker<S> reserveSlot(WorkerStartupCoordinator.Reservation<S> startupReservation) {
        PoolWorker<S> reservation = null;
        boolean poolClosed;
        synchronized (lock) {
            poolClosed = closing;
            if (!poolClosed && partition.size() >= policy.maxSize()) {
                throw new IllegalStateException("pool capacity exhausted while reserving startup slot");
            }
            if (!poolClosed) {
                reservation = reserveSlotLocked(startupReservation);
            }
        }
        if (poolClosed) {
            throw failures.closed("Pool is closed");
        }
        return Objects.requireNonNull(reservation, "reservation");
    }

    private PoolWorker<S> reserveSlotLocked(WorkerStartupCoordinator.Reservation<S> startupReservation) {
        PoolWorker<S> worker = newStartingWorker();
        startupReservation.register(worker);
        partition.addStarting(worker);
        stateChangedLocked();
        return worker;
    }

    private PoolWorker<S> newStartingWorker() {
        PoolWorker<S> worker = new PoolWorker<>();
        worker.startup(new WorkerStartup<>(
                workerFactory, threadPrefix + "start-", completion -> finishAbandonedStart(worker, completion)));
        return worker;
    }

    private PoolWorker<S> openReservedWorker(
            WorkerStartupCoordinator.Reservation<S> startupReservation,
            long deadlineNanos,
            PoolWorker.StartupPurpose purpose,
            LeaseHandoff handoff) {
        PoolWorker<S> reservation = startupReservation.worker();
        WorkerStartupCoordinator.Completion<S> completion = startups.start(startupReservation, deadlineNanos, purpose);
        WorkerStartup.TerminalDecision decision = completion.decision();
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        try {
            synchronized (lock) {
                requireState(reservation, PoolPartition.State.STARTING);
                WorkerStartup.CreatedWorker<S> createdWorker = completion.createdWorker();
                reservation.accept(createdWorker.session(), workerCloser);
                cumulativeMetrics.workerCreated(createdWorker.startupNanos());
                switch (decision) {
                    case FACTORY_COMPLETED -> transitionToLeased(reservation, handoff);
                    case CLOSED -> markRetiring(reservation, PooledWorkerRetireReason.CLOSED, retirementBatch);
                    case TIMED_OUT ->
                        markRetiring(reservation, PooledWorkerRetireReason.STARTUP_TIMEOUT, retirementBatch);
                    case INTERRUPTED ->
                        markRetiring(reservation, PooledWorkerRetireReason.STARTUP_INTERRUPTED, retirementBatch);
                    case UNDECIDED -> throw new AssertionError("unreachable terminal decision");
                }
                stateChangedLocked();
            }
        } finally {
            retirementBatch.dispatch();
        }
        if (decision == WorkerStartup.TerminalDecision.FACTORY_COMPLETED) {
            return reservation;
        }
        publishDrainIfReady();
        throw startups.rejectedCompletion(purpose, decision);
    }

    private RuntimeException acquireTimeoutAfterReturning(LeaseHandoff handoff) {
        RuntimeException failure = failures.acquireTimeout("Timed out acquiring " + workerLabel);
        try {
            handoff.transferToPoolLifecycle();
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(failure, cleanupFailure);
        }
        return failure;
    }

    private void finishAbandonedStart(PoolWorker<S> reservation, WorkerStartup.LateCompletion<S> completion) {
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        try {
            synchronized (lock) {
                if (!partition.contains(reservation)) {
                    return;
                }
                cumulativeMetrics.startupFailed();
                if (completion.session() == null) {
                    removeSlot(reservation);
                    reservation.releaseRetirementAdmission();
                } else {
                    reservation.accept(completion.session(), workerCloser);
                    cumulativeMetrics.workerCreated(completion.startupNanos());
                    markRetiring(reservation, completion.reason(), retirementBatch);
                }
                stateChangedLocked();
            }
        } finally {
            retirementBatch.dispatch();
        }
        publishDrainIfReady();
        ensureReplenishmentOwner();
        if (completion.errorTarget() != null) {
            reportOrRecordLateError(
                    completion.errorTarget(), Objects.requireNonNull(completion.failure(), "late startup failure"));
        }
    }

    private void retireLeased(PoolWorker<S> worker, PooledWorkerRetireReason reason) {
        retireLeased(worker, reason, false);
    }

    private void retireLeased(PoolWorker<S> worker, PooledWorkerRetireReason reason, boolean closedTakesPrecedence) {
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        try {
            synchronized (lock) {
                requireState(worker, PoolPartition.State.LEASED);
                PooledWorkerRetireReason effectiveReason =
                        closedTakesPrecedence && closing ? PooledWorkerRetireReason.CLOSED : reason;
                markRetiring(worker, effectiveReason, retirementBatch);
                stateChangedLocked();
            }
        } finally {
            retirementBatch.dispatch();
        }
        ensureReplenishmentOwner();
        publishDrainIfReady();
    }

    private void retireAfterFailedHandoff(
            PoolWorker<S> worker,
            PooledWorkerRetireReason reason,
            boolean closedTakesPrecedence,
            Throwable primaryFailure) {
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        try {
            synchronized (lock) {
                if (!partition.contains(worker)) {
                    return;
                }
                PooledWorkerRetireReason effectiveReason =
                        closedTakesPrecedence && closing ? PooledWorkerRetireReason.CLOSED : reason;
                switch (Objects.requireNonNull(partition.stateOf(worker), "worker state")) {
                    case LEASED -> markRetiring(worker, effectiveReason, retirementBatch);
                    case IDLE -> {
                        markRetiring(worker, effectiveReason, retirementBatch);
                    }
                    case RETIRING -> {}
                    case STARTING -> throw new IllegalStateException("handoff still owns a starting worker");
                }
                stateChangedLocked();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        } finally {
            try {
                retirementBatch.dispatch();
            } catch (RuntimeException | Error cleanupFailure) {
                SuppressionSupport.attach(primaryFailure, cleanupFailure);
            }
        }
        try {
            ensureReplenishmentOwner();
            publishDrainIfReady();
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
    }

    private void markRetiring(
            PoolWorker<S> worker,
            PooledWorkerRetireReason reason,
            WorkerRetirementCoordinator.Batch<S> retirementBatch) {
        switch (Objects.requireNonNull(partition.stateOf(worker), "worker state")) {
            case STARTING -> partition.startingToRetiring(worker);
            case IDLE -> partition.idleToRetiring(worker);
            case LEASED -> partition.leasedToRetiring(worker);
            case RETIRING -> throw new IllegalStateException("worker state must not already be RETIRING");
        }
        worker.retireReason(Objects.requireNonNull(reason, "reason"));
        retirementBatch.add(worker);
    }

    private void completeUnexpectedRetirementFailure(PoolWorker<S> worker, Throwable failure) {
        FailureReport failureReport = PoolFailurePublisher.capture(Thread.currentThread(), failure);
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        DrainPublication publication;
        try {
            synchronized (lock) {
                enterClosingLocked(failure, retirementBatch);
                if (partition.contains(worker) && partition.is(worker, PoolPartition.State.RETIRING)) {
                    if (!removeSlot(worker)) {
                        throw new IllegalStateException("failed retiring worker is absent from the slot registry");
                    }
                    worker.releaseRetirementAdmission();
                    cumulativeMetrics.workerRetired(worker.retireReason(), true);
                }
                publication = drainPublication();
                stateChangedLocked();
            }
        } finally {
            retirementBatch.dispatch();
        }
        publish(publication);
        failurePublisher.publish(failureReport);
    }

    private FailureReport processRetirement(PoolWorker<S> worker, WorkerRetirement.Outcome outcome) {
        Throwable closeFailure = outcome.failure();
        FailureReport lateReport = null;
        DrainPublication publication;
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        try {
            synchronized (lock) {
                requireState(worker, PoolPartition.State.RETIRING);
                if (!removeSlot(worker)) {
                    throw new IllegalStateException("retiring worker is absent from the slot registry");
                }
                worker.releaseRetirementAdmission();
                cumulativeMetrics.workerRetired(worker.retireReason(), closeFailure != null);
                if (closeFailure != null) {
                    enterClosingLocked(closeFailure, retirementBatch);
                    if (construction.constructing() && worker.claimFailureReport()) {
                        construction.record(PoolFailurePublisher.capture(Thread.currentThread(), closeFailure));
                    } else if (construction.failed() && worker.claimFailureReport()) {
                        lateReport = PoolFailurePublisher.capture(Thread.currentThread(), closeFailure);
                    }
                }
                publication = drainPublication();
                stateChangedLocked();
            }
        } finally {
            retirementBatch.dispatch();
        }
        publish(publication);
        ensureReplenishmentOwner();
        return lateReport;
    }

    private void ensureReplenishmentOwner() {
        replenisher.ensureStarted();
    }

    private boolean replenishmentNeeded() {
        synchronized (lock) {
            return !closing && needsReplenishment();
        }
    }

    private PoolReplenisher.Step replenishOne() {
        WorkerStartupCoordinator.Reservation<S> startupReservation = startups.newReservation();
        LeaseHandoff handoff = new LeaseHandoff();
        try {
            synchronized (lock) {
                if (closing || !needsReplenishment()) {
                    return PoolReplenisher.Step.STOP;
                }
                reserveSlotLocked(startupReservation);
            }
            openReservedWorker(
                    startupReservation,
                    DurationSupport.deadlineFromNow(policy.acquireTimeout()),
                    PoolWorker.StartupPurpose.REPLENISHMENT,
                    handoff);
            handoff.transferToPoolLifecycle();
            return PoolReplenisher.Step.SUCCESS;
        } catch (RuntimeException failure) {
            failStartupReservation(startupReservation, failure);
            handoff.fail(failure);
            return PoolReplenisher.Step.RETRY;
        } catch (Error failure) {
            failStartupReservation(startupReservation, failure);
            handoff.fail(failure);
            throw failure;
        }
    }

    private void failStartupReservation(
            WorkerStartupCoordinator.Reservation<S> startupReservation, Throwable primaryFailure) {
        Objects.requireNonNull(startupReservation, "startupReservation");
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        PoolWorker<S> failedReservation = startupReservation.releaseForFailure();
        if (failedReservation == null) {
            return;
        }
        try {
            synchronized (lock) {
                removeSlot(failedReservation);
                failedReservation.releaseRetirementAdmission();
                stateChangedLocked();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
        try {
            publishDrainIfReady();
            ensureReplenishmentOwner();
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
    }

    private boolean awaitBackoffWithLock(Duration backoff) {
        if (backoff.isZero()) {
            return true;
        }
        long deadline = DurationSupport.deadlineFromNow(backoff);
        synchronized (lock) {
            while (!closing) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return true;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    private void failReplenishmentOwner(Throwable failure) {
        WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
        DrainPublication publication = null;
        try {
            synchronized (lock) {
                enterClosingLocked(failure, retirementBatch);
                publication = drainPublication();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(failure, cleanupFailure);
        } finally {
            try {
                retirementBatch.dispatch();
            } catch (RuntimeException | Error cleanupFailure) {
                SuppressionSupport.attach(failure, cleanupFailure);
            }
        }
        publish(publication);
        try {
            publishDrainIfReady();
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(failure, cleanupFailure);
        }
    }

    private boolean needsReplenishment() {
        return policy.needsReplenishment(partition);
    }

    private PooledWorkerRetireReason retireReasonForPolicy(PoolWorker<S> worker) {
        return policy.retirementReasonFor(worker);
    }

    private void recordAcquireWait(long startedAtNanos) {
        long elapsedNanos = Math.max(0, metricsClock.nanoTime() - startedAtNanos);
        synchronized (lock) {
            cumulativeMetrics.acquired(elapsedNanos);
            stateChangedLocked();
        }
    }

    private void reportOrRecordLateError(BoundedFailureReporter.FailureTarget failureTarget, Throwable failure) {
        FailureReport report;
        synchronized (lock) {
            report = construction.route(new FailureReport(failureTarget, failure));
        }
        failurePublisher.publish(report);
    }

    private void completeRequest(boolean successful, long durationNanos) {
        synchronized (lock) {
            cumulativeMetrics.requestCompleted(successful, durationNanos);
            stateChangedLocked();
        }
    }

    private void publishDrainIfReady() {
        DrainPublication publication;
        synchronized (lock) {
            publication = drainPublication();
        }
        publish(publication);
    }

    private DrainPublication drainPublication() {
        if (!closing) {
            return null;
        }
        if (partition.size() != 0) {
            return null;
        }
        if (!drain.tryClaim()) {
            return null;
        }
        return new DrainPublication(drainFailures.failure());
    }

    private void publish(DrainPublication publication) {
        if (publication != null) {
            drain.publish(publication.failure());
        }
    }

    private void recordDrainFailure(Throwable failure) {
        drainFailures.add(failure);
    }

    private void enterClosingLocked(Throwable failure, WorkerRetirementCoordinator.Batch<S> retirementBatch) {
        for (PoolWorker<S> worker : partition.startingWorkers()) {
            if (partition.is(worker, PoolPartition.State.STARTING)) {
                worker.startup().signalClosed();
            }
        }
        closing = true;
        if (failure != null) {
            recordDrainFailure(failure);
        }
        for (PoolWorker<S> worker : partition.idleWorkers()) {
            markRetiring(worker, PooledWorkerRetireReason.CLOSED, retirementBatch);
        }
        for (PoolWorker<S> worker : partition.startingWorkers()) {
            if (worker.startupStage() == PoolWorker.StartupStage.QUEUED) {
                partition.removeStarting(worker);
                worker.releaseRetirementAdmission();
            }
        }
        stateChangedLocked();
    }

    private void transitionToLeased(PoolWorker<S> worker, LeaseHandoff handoff) {
        if (!handoff.tryOwn(worker)) {
            throw new IllegalStateException("lease handoff cannot accept another worker");
        }
        try {
            partition.startingToLeased(worker);
            stateChangedLocked();
        } catch (RuntimeException | Error failure) {
            handoff.rollback(worker);
            throw failure;
        }
    }

    private void ownLeased(PoolWorker<S> worker, LeaseHandoff handoff) {
        requireState(worker, PoolPartition.State.LEASED);
        if (!handoff.tryOwn(worker)) {
            throw new IllegalStateException("lease handoff cannot accept another worker");
        }
        stateChangedLocked();
    }

    private void requireState(PoolWorker<S> worker, PoolPartition.State expected) {
        partition.requireState(worker, expected);
    }

    private boolean removeSlot(PoolWorker<S> worker) {
        PoolPartition.State state = partition.stateOf(worker);
        if (state == null) {
            return false;
        }
        switch (state) {
            case STARTING -> partition.removeStarting(worker);
            case RETIRING -> partition.removeRetiring(worker);
            case IDLE, LEASED -> throw new IllegalStateException("cannot remove live worker in state " + state);
        }
        return true;
    }

    interface FailureFactory {

        RuntimeException closed(String message);

        RuntimeException acquireTimeout(String message);

        RuntimeException acquireInterrupted(String message, InterruptedException cause);

        RuntimeException startupFailed(String message, Throwable cause);

        RuntimeException retirementFailed(String message, Throwable cause);
    }

    enum HealthOutcome {
        HEALTHY,
        HEALTH_FAILED,
        PROCESS_EXITED,
        ACQUIRE_TIMEOUT
    }

    interface HealthCheck<S> {

        HealthOutcome test(S session, long acquireDeadlineNanos);
    }

    @FunctionalInterface
    interface LateFailureReporter {

        void report(Thread thread, Throwable failure);
    }

    @FunctionalInterface
    interface NanoClock {

        long nanoTime();
    }

    @FunctionalInterface
    interface BackoffWaiter {

        boolean await(Duration backoff);
    }

    record Dependencies(
            Consumer<Runnable> replenishmentStarter,
            LateFailureReporter lateFailureReporter,
            NanoClock metricsClock,
            BackoffWaiter backoffWaiter,
            PoolTerminalPublisher.Capacity terminalPublications,
            RetirementAdmissionProvider retirementAdmissions) {

        Dependencies {
            Objects.requireNonNull(replenishmentStarter, "replenishmentStarter");
            Objects.requireNonNull(lateFailureReporter, "lateFailureReporter");
            Objects.requireNonNull(metricsClock, "metricsClock");
            Objects.requireNonNull(terminalPublications, "terminalPublications");
            Objects.requireNonNull(retirementAdmissions, "retirementAdmissions");
        }

        static Dependencies defaults(String threadPrefix, NanoClock metricsClock) {
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            return new Dependencies(
                    task -> PoolLifecycleDispatcher.replenish(task),
                    PoolFailurePublisher::reportBounded,
                    metricsClock,
                    null,
                    PoolTerminalPublisher.sharedCapacity(),
                    PoolLifecycleDispatcher::admit);
        }
    }

    @FunctionalInterface
    interface RetirementAdmissionProvider {

        PoolLifecycleDispatcher.Admission acquire(long deadlineNanos) throws TimeoutException, InterruptedException;
    }

    private final class StartupPoolState implements WorkerStartupCoordinator.PoolState<S> {

        @Override
        public boolean attachAdmission(
                PoolWorker<S> reservation,
                PoolLifecycleDispatcher.Admission admission,
                PoolWorker.StartupPurpose purpose) {
            synchronized (lock) {
                if (!partition.contains(reservation) || closing) {
                    return false;
                }
                requireState(reservation, PoolPartition.State.STARTING);
                reservation.retirementAdmission(admission);
                reservation.startupPurpose(purpose);
                return true;
            }
        }

        @Override
        public WorkerStartupCoordinator.StartupClaim claimLaunch(PoolWorker<S> reservation, long deadlineNanos) {
            synchronized (lock) {
                if (!partition.contains(reservation) || closing) {
                    reservation.startup().signalClosed();
                    return WorkerStartupCoordinator.StartupClaim.CLOSED;
                }
                requireState(reservation, PoolPartition.State.STARTING);
                if (deadlineNanos - System.nanoTime() <= 0) {
                    reservation.startup().signalTimeout();
                    return WorkerStartupCoordinator.StartupClaim.TIMED_OUT;
                }
                reservation.startupStage(PoolWorker.StartupStage.RUNNING);
                return WorkerStartupCoordinator.StartupClaim.RUN;
            }
        }

        @Override
        public void launchFailed(PoolWorker<S> reservation) {
            synchronized (lock) {
                if (removeSlot(reservation)) {
                    reservation.releaseRetirementAdmission();
                    stateChangedLocked();
                }
            }
            publishDrainIfReady();
            ensureReplenishmentOwner();
        }

        @Override
        public boolean factoryFailed(PoolWorker<S> reservation) {
            boolean closedStartup;
            synchronized (lock) {
                closedStartup = switch (reservation.startup().terminalDecision()) {
                    case CLOSED -> true;
                    case FACTORY_COMPLETED -> false;
                    case TIMED_OUT, INTERRUPTED, UNDECIDED ->
                        throw new IllegalStateException("worker factory failure has no completion decision");
                };
                if (removeSlot(reservation)) {
                    cumulativeMetrics.startupFailed();
                    reservation.releaseRetirementAdmission();
                    stateChangedLocked();
                }
            }
            publishDrainIfReady();
            ensureReplenishmentOwner();
            return closedStartup;
        }
    }

    private final class LeaseHandoff {

        private PoolWorker<S> worker;

        private boolean tryOwn(PoolWorker<S> leasedWorker) {
            if (worker != null || leasedWorker == null) {
                return false;
            }
            worker = leasedWorker;
            return true;
        }

        private void rollback(PoolWorker<S> leasedWorker) {
            if (worker == leasedWorker) {
                worker = null;
            }
        }

        private PoolWorker<S> complete() {
            PoolWorker<S> completedWorker = Objects.requireNonNull(worker, "lease handoff has no worker");
            worker = null;
            return completedWorker;
        }

        private void retire(PooledWorkerRetireReason reason) {
            PoolWorker<S> retiredWorker = complete();
            retireLeased(retiredWorker, reason);
        }

        private void transferToPoolLifecycle() {
            PoolWorker<S> transferredWorker = Objects.requireNonNull(worker, "lease handoff has no worker");
            WorkerRetirementCoordinator.Batch<S> retirementBatch = retirements.newBatch();
            try {
                synchronized (lock) {
                    requireState(transferredWorker, PoolPartition.State.LEASED);
                    PooledWorkerRetireReason reason =
                            closing ? PooledWorkerRetireReason.CLOSED : retireReasonForPolicy(transferredWorker);
                    if (reason != null) {
                        markRetiring(transferredWorker, reason, retirementBatch);
                    } else {
                        partition.leasedToIdle(transferredWorker);
                    }
                    worker = null;
                    stateChangedLocked();
                }
            } finally {
                retirementBatch.dispatch();
            }
        }

        private void fail(Throwable primaryFailure) {
            fail(primaryFailure, PooledWorkerRetireReason.WORKER_FAILED, true);
        }

        private void fail(Throwable primaryFailure, PooledWorkerRetireReason reason) {
            fail(primaryFailure, reason, false);
        }

        private void fail(Throwable primaryFailure, PooledWorkerRetireReason reason, boolean closedTakesPrecedence) {
            Objects.requireNonNull(primaryFailure, "primaryFailure");
            if (worker == null) {
                return;
            }
            PoolWorker<S> failedWorker = complete();
            retireAfterFailedHandoff(failedWorker, reason, closedTakesPrecedence, primaryFailure);
        }
    }

    private enum AcquireWaitFailure {
        CLOSED,
        TIMED_OUT,
        INTERRUPTED
    }

    private record AcquireSelection<S>(PoolWorker<S> worker, boolean startup) {

        private AcquireSelection {
            Objects.requireNonNull(worker, "worker");
        }
    }

    private record DrainPublication(Throwable failure) {}
}

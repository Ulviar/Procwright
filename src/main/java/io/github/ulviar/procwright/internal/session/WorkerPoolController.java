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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Coordinates process-pool work that must execute outside {@link WorkerPoolState}.
 *
 * <p>Worker factories, health/reset hooks, physical close, failure reporting, and future completion never run under
 * the pool-state monitor.
 */
final class WorkerPoolController<S> implements WorkerStartupCoordinator.PoolState<S> {

    private final Supplier<S> workerFactory;
    private final WorkerRetirement.Action<S> workerCloser;
    private final WorkerPoolPolicy policy;
    private final FailureFactory failures;
    private final String workerLabel;
    private final String threadPrefix;
    private final PoolFailurePublisher failurePublisher;
    private final LongSupplier metricsClock;
    private final RetirementAdmissionProvider retirementAdmissions;
    private final WorkerPoolState<S> state;
    private final PoolReplenisher replenisher;
    private final WorkerRetirementCoordinator<S> retirements;
    private final WorkerStartupCoordinator<S> startups;

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
                Dependencies.defaults(System::nanoTime));
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerRetirement.Action<S> workerCloser,
            WorkerPoolPolicy.Options options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            LongSupplier metricsClock) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                Dependencies.defaults(metricsClock));
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
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        failurePublisher = new PoolFailurePublisher(configuredDependencies.lateFailureReporter());
        metricsClock = configuredDependencies.metricsClock();
        retirementAdmissions = configuredDependencies.retirementAdmissions();
        retirements = new WorkerRetirementCoordinator<>(
                task -> PoolLifecycleDispatcher.executeRetirementBatch(task),
                this::processRetirement,
                this::completeUnexpectedRetirementFailure,
                failurePublisher::publish);
        PoolTerminalPublisher terminalPublisher;
        try {
            terminalPublisher = configuredDependencies.terminalPublications().reserve();
        } catch (RuntimeException failure) {
            throw Objects.requireNonNull(
                    failures.startupFailed("Could not reserve pool terminal publication capacity", failure),
                    "startup failure");
        }
        try {
            state = new WorkerPoolState<>(policy, new PoolTermination(terminalPublisher), this::newStartupReservation);
            PoolReplenisher.Waiter configuredWaiter = configuredDependencies.backoffWaiter() == null
                    ? state::awaitBackoff
                    : configuredDependencies.backoffWaiter();
            replenisher = new PoolReplenisher(
                    policy.replenishmentEnabled(),
                    configuredDependencies.replenishmentStarter(),
                    state::replenishmentNeeded,
                    this::replenishOne,
                    configuredWaiter,
                    this::failReplenishmentOwner);
            startups = new WorkerStartupCoordinator<>(failures, workerLabel, retirementAdmissions, this);
        } catch (RuntimeException | Error failure) {
            try {
                terminalPublisher.abort();
            } catch (RuntimeException | Error abortFailure) {
                SuppressionSupport.attach(failure, abortFailure);
            }
            throw failure;
        }

        List<FailureReport> commitReports = List.of();
        try {
            warmup();
            ensureReplenishmentOwner();
            PoolTermination.ConstructionResult constructionResult = state.finishConstruction();
            commitReports = constructionResult.reports();
            if (!constructionResult.successful()) {
                if (constructionResult.failure() instanceof Error error) {
                    throw error;
                }
                throw failures.retirementFailed("Pool failed during construction", constructionResult.failure());
            }
        } catch (RuntimeException | Error failure) {
            cleanupFailedConstruction(failure);
            throw failure;
        }
        failurePublisher.publishAll(commitReports);
    }

    WorkerPoolState.Lease<S> acquire(HealthCheck<S> healthCheck) {
        Objects.requireNonNull(healthCheck, "healthCheck");
        long startedAtNanos = metricsClock.getAsLong();
        long deadlineNanos = DurationSupport.deadlineFromNow(policy.acquireTimeout());
        boolean acquireWaitRecorded = false;
        try {
            while (true) {
                WorkerPoolState.Lease<S> lease = takeOrStartLease(deadlineNanos);
                try {
                    ensureReplenishmentOwner();
                    if (deadlineNanos - System.nanoTime() <= 0) {
                        throw acquireTimeoutAfterReturning(lease);
                    }
                    HealthOutcome healthOutcome;
                    try {
                        healthOutcome = Objects.requireNonNull(
                                healthCheck.test(lease.session(), deadlineNanos), "healthCheck returned null");
                    } catch (RuntimeException | Error failure) {
                        retireFailedLease(lease, failure, PooledWorkerRetireReason.HEALTH_FAILED, false);
                        throw failure;
                    }
                    switch (healthOutcome) {
                        case HEALTHY -> {
                            if (deadlineNanos - System.nanoTime() <= 0) {
                                throw acquireTimeoutAfterReturning(lease);
                            }
                            acquireWaitRecorded = true;
                            recordAcquireWait(startedAtNanos);
                            return lease;
                        }
                        case ACQUIRE_TIMEOUT -> throw acquireTimeoutAfterReturning(lease);
                        case HEALTH_FAILED -> retireLease(lease, PooledWorkerRetireReason.HEALTH_FAILED);
                        case PROCESS_EXITED -> retireLease(lease, PooledWorkerRetireReason.PROCESS_EXITED);
                    }
                } catch (RuntimeException | Error failure) {
                    retireFailedLease(lease, failure, PooledWorkerRetireReason.WORKER_FAILED, true);
                    throw failure;
                }
            }
        } catch (RuntimeException | Error failure) {
            if (!acquireWaitRecorded) {
                try {
                    recordAcquireWait(startedAtNanos);
                } catch (RuntimeException | Error metricsFailure) {
                    SuppressionSupport.attach(failure, metricsFailure);
                }
            }
            throw failure;
        }
    }

    void releaseReusable(WorkerPoolState.Lease<S> lease) {
        try (PoolStateEffects<S> effects = newEffects()) {
            state.releaseReusable(lease, effects);
        }
        ensureReplenishmentOwner();
    }

    void retire(WorkerPoolState.Lease<S> lease, PooledWorkerRetireReason reason) {
        try (PoolStateEffects<S> effects = newEffects()) {
            state.retire(lease, reason, effects);
        }
        ensureReplenishmentOwner();
    }

    PooledWorkerRetireReason recordRequestAndRetirementReason(WorkerPoolState.Lease<S> lease) {
        return state.recordRequestAndRetirementReason(lease);
    }

    RequestObservation observeRequest() {
        return new RequestObservation(metricsClock, state::recordRequest);
    }

    PoolMetrics.Snapshot metrics() {
        return state.metrics();
    }

    boolean awaitMetrics(Predicate<PoolMetrics.Snapshot> condition, Duration timeout) throws InterruptedException {
        return state.awaitMetrics(condition, timeout);
    }

    CompletableFuture<Void> closeAsync() {
        try (PoolStateEffects<S> effects = newEffects()) {
            state.beginClose(null, effects);
        }
        return state.terminationView();
    }

    private void warmup() {
        for (int index = 0; index < policy.warmupSize(); index++) {
            WorkerStartupCoordinator.Reservation<S> reservation = null;
            WorkerPoolState.Lease<S> lease = null;
            try {
                reservation = reserveSlot();
                lease = openReservedWorker(
                        reservation,
                        DurationSupport.deadlineFromNow(policy.acquireTimeout()),
                        PoolWorker.StartupPurpose.WARMUP);
                returnLease(lease);
            } catch (RuntimeException | Error failure) {
                if (reservation != null) {
                    failStartupReservation(reservation, failure);
                }
                if (lease != null) {
                    retireFailedLease(lease, failure, PooledWorkerRetireReason.WORKER_FAILED, true);
                }
                throw failure;
            }
        }
    }

    private void cleanupFailedConstruction(Throwable primary) {
        List<FailureReport> completedFailures;
        try (PoolStateEffects<S> effects = newEffects()) {
            completedFailures = state.failConstructionAndClose(effects);
        }
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
            state.terminationView().get(remainingNanos, TimeUnit.NANOSECONDS);
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

    private WorkerPoolState.Lease<S> takeOrStartLease(long deadlineNanos) {
        while (true) {
            WorkerPoolState.AcquireResult<S> acquisition;
            try (PoolStateEffects<S> effects = newEffects()) {
                acquisition = state.awaitAcquire(deadlineNanos, effects);
            }
            switch (acquisition.status()) {
                case LEASED -> {
                    return acquisition.lease();
                }
                case RETRY -> {
                    continue;
                }
                case CLOSED -> throw failures.closed("Pool is closed");
                case TIMED_OUT -> throw failures.acquireTimeout("Timed out waiting for " + workerLabel);
                case INTERRUPTED ->
                    throw failures.acquireInterrupted(
                            "Interrupted while waiting for " + workerLabel,
                            Objects.requireNonNull(acquisition.interruption(), "interruption"));
                case RESERVED -> {
                    WorkerStartupCoordinator.Reservation<S> reservation =
                            Objects.requireNonNull(acquisition.reservation(), "reservation");
                    try {
                        return openReservedWorker(reservation, deadlineNanos, PoolWorker.StartupPurpose.DEMAND);
                    } catch (RuntimeException | Error failure) {
                        failStartupReservation(reservation, failure);
                        throw failure;
                    }
                }
            }
        }
    }

    private WorkerStartupCoordinator.Reservation<S> reserveSlot() {
        WorkerPoolState.ReservationResult<S> result = state.reserve();
        if (result.status() == WorkerPoolState.ReserveStatus.RESERVED) {
            return Objects.requireNonNull(result.reservation(), "reservation");
        }
        if (result.status() == WorkerPoolState.ReserveStatus.CLOSED) {
            throw failures.closed("Pool is closed");
        }
        throw new IllegalStateException("pool capacity exhausted while reserving startup slot");
    }

    private WorkerStartupCoordinator.Reservation<S> newStartupReservation() {
        PoolWorker<S> worker = new PoolWorker<>(workerCloser);
        WorkerStartupCoordinator.Reservation<S> reservation =
                new WorkerStartupCoordinator.Reservation<>(worker, newEffects());
        worker.startup(new WorkerStartup<>(
                workerFactory, threadPrefix + "start-", completion -> finishAbandonedStart(reservation, completion)));
        return reservation;
    }

    private WorkerPoolState.Lease<S> openReservedWorker(
            WorkerStartupCoordinator.Reservation<S> reservation,
            long deadlineNanos,
            PoolWorker.StartupPurpose purpose) {
        WorkerStartupCoordinator.Completion<S> completion = startups.start(reservation, deadlineNanos, purpose);
        WorkerStartup.TerminalDecision decision = completion.decision();
        WorkerPoolState.Lease<S> lease;
        try (PoolStateEffects<S> effects = reservation.effects()) {
            lease = state.completeStartup(reservation, completion.createdWorker(), decision, effects);
        }
        if (decision == WorkerStartup.TerminalDecision.FACTORY_COMPLETED) {
            return Objects.requireNonNull(lease, "completed startup lease");
        }
        throw startups.rejectedCompletion(purpose, decision);
    }

    private RuntimeException acquireTimeoutAfterReturning(WorkerPoolState.Lease<S> lease) {
        RuntimeException failure = failures.acquireTimeout("Timed out acquiring " + workerLabel);
        try {
            returnLease(lease);
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(failure, cleanupFailure);
        }
        return failure;
    }

    private void finishAbandonedStart(
            WorkerStartupCoordinator.Reservation<S> reservation, WorkerStartup.LateCompletion<S> completion) {
        boolean present;
        try (PoolStateEffects<S> effects = reservation.effects()) {
            present = state.completeAbandonedStartup(reservation, completion, effects);
        }
        if (!present) {
            return;
        }
        ensureReplenishmentOwner();
        if (completion.errorTarget() != null) {
            reportOrRecordLateError(
                    completion.errorTarget(), Objects.requireNonNull(completion.failure(), "late startup failure"));
        }
    }

    private void retireLease(WorkerPoolState.Lease<S> lease, PooledWorkerRetireReason reason) {
        try (PoolStateEffects<S> effects = newEffects()) {
            state.retireLeaseIfOwned(lease, reason, false, effects);
        }
        ensureReplenishmentOwner();
    }

    private void retireFailedLease(
            WorkerPoolState.Lease<S> lease,
            Throwable primaryFailure,
            PooledWorkerRetireReason reason,
            boolean closedTakesPrecedence) {
        try (PoolStateEffects<S> effects = newEffects()) {
            try {
                state.retireLeaseIfOwned(lease, reason, closedTakesPrecedence, effects);
            } catch (RuntimeException | Error cleanupFailure) {
                SuppressionSupport.attach(primaryFailure, cleanupFailure);
            }
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
        try {
            ensureReplenishmentOwner();
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
    }

    private void returnLease(WorkerPoolState.Lease<S> lease) {
        try (PoolStateEffects<S> effects = newEffects()) {
            state.releaseReusable(lease, effects);
        }
    }

    private void completeUnexpectedRetirementFailure(PoolWorker<S> worker, Throwable failure) {
        FailureReport failureReport = PoolFailurePublisher.capture(Thread.currentThread(), failure);
        try (PoolStateEffects<S> effects = newEffects()) {
            state.failRetirementObservation(worker, failure, effects);
        }
        failurePublisher.publish(failureReport);
    }

    private FailureReport processRetirement(PoolWorker<S> worker, WorkerRetirement.Outcome outcome) {
        FailureReport closeFailureReport = outcome.failure() == null
                ? null
                : PoolFailurePublisher.capture(Thread.currentThread(), outcome.failure());
        FailureReport lateReport;
        try (PoolStateEffects<S> effects = newEffects()) {
            lateReport = state.completeRetirement(worker, outcome, closeFailureReport, effects);
        }
        ensureReplenishmentOwner();
        return lateReport;
    }

    private PoolStateEffects<S> newEffects() {
        return new PoolStateEffects<>(state, retirements);
    }

    private void ensureReplenishmentOwner() {
        replenisher.ensureStarted();
    }

    private PoolReplenisher.Step replenishOne() {
        if (!state.replenishmentNeeded()) {
            return PoolReplenisher.Step.STOP;
        }
        WorkerPoolState.ReservationResult<S> reservationResult = state.reserveReplenishment();
        if (reservationResult.status() != WorkerPoolState.ReserveStatus.RESERVED) {
            return PoolReplenisher.Step.STOP;
        }
        WorkerStartupCoordinator.Reservation<S> reservation =
                Objects.requireNonNull(reservationResult.reservation(), "reservation");
        WorkerPoolState.Lease<S> lease = null;
        try {
            lease = openReservedWorker(
                    reservation,
                    DurationSupport.deadlineFromNow(policy.acquireTimeout()),
                    PoolWorker.StartupPurpose.REPLENISHMENT);
            returnLease(lease);
            return PoolReplenisher.Step.SUCCESS;
        } catch (RuntimeException failure) {
            failStartupReservation(reservation, failure);
            if (lease != null) {
                retireFailedLease(lease, failure, PooledWorkerRetireReason.WORKER_FAILED, true);
            }
            return PoolReplenisher.Step.RETRY;
        } catch (Error failure) {
            failStartupReservation(reservation, failure);
            if (lease != null) {
                retireFailedLease(lease, failure, PooledWorkerRetireReason.WORKER_FAILED, true);
            }
            throw failure;
        }
    }

    private void failStartupReservation(WorkerStartupCoordinator.Reservation<S> reservation, Throwable primaryFailure) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        if (!reservation.canRollback()) {
            return;
        }
        try (PoolStateEffects<S> effects = reservation.effects()) {
            state.removeFailedStartup(reservation, effects);
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
        try {
            ensureReplenishmentOwner();
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
    }

    private void failReplenishmentOwner(Throwable failure) {
        FailureReport lateReport = PoolFailurePublisher.capture(Thread.currentThread(), failure);
        PoolTermination.FailureDisposition disposition = null;
        try (PoolStateEffects<S> effects = newEffects()) {
            disposition = state.beginClose(failure, effects);
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(failure, cleanupFailure);
        }
        if (disposition == PoolTermination.FailureDisposition.LATE) {
            failurePublisher.publish(lateReport);
        }
    }

    private void recordAcquireWait(long startedAtNanos) {
        long elapsedNanos = Math.max(0, metricsClock.getAsLong() - startedAtNanos);
        state.recordAcquireWait(elapsedNanos);
    }

    private void reportOrRecordLateError(BoundedFailureReporter.FailureTarget failureTarget, Throwable failure) {
        FailureReport report = state.routeLateFailure(new FailureReport(failureTarget, failure));
        failurePublisher.publish(report);
    }

    @Override
    public boolean attachAdmission(
            WorkerStartupCoordinator.Reservation<S> reservation,
            PoolLifecycleDispatcher.Admission admission,
            PoolWorker.StartupPurpose purpose) {
        return state.attachStartupAdmission(reservation, admission, purpose);
    }

    @Override
    public WorkerStartupCoordinator.StartupClaim claimLaunch(
            WorkerStartupCoordinator.Reservation<S> reservation, long deadlineNanos) {
        return state.claimStartup(reservation, deadlineNanos);
    }

    @Override
    public void launchFailed(WorkerStartupCoordinator.Reservation<S> reservation) {
        try (PoolStateEffects<S> effects = reservation.effects()) {
            state.launchFailed(reservation, effects);
        }
        ensureReplenishmentOwner();
    }

    @Override
    public boolean factoryFailed(WorkerStartupCoordinator.Reservation<S> reservation, Throwable failure) {
        boolean closedStartup;
        try (PoolStateEffects<S> effects = reservation.effects()) {
            closedStartup = state.factoryFailed(reservation, failure, effects);
        }
        ensureReplenishmentOwner();
        return closedStartup;
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

    record Dependencies(
            Consumer<Runnable> replenishmentStarter,
            BiConsumer<Thread, Throwable> lateFailureReporter,
            LongSupplier metricsClock,
            PoolReplenisher.Waiter backoffWaiter,
            PoolTerminalPublisher.Capacity terminalPublications,
            RetirementAdmissionProvider retirementAdmissions) {

        Dependencies {
            Objects.requireNonNull(replenishmentStarter, "replenishmentStarter");
            Objects.requireNonNull(lateFailureReporter, "lateFailureReporter");
            Objects.requireNonNull(metricsClock, "metricsClock");
            Objects.requireNonNull(terminalPublications, "terminalPublications");
            Objects.requireNonNull(retirementAdmissions, "retirementAdmissions");
        }

        static Dependencies defaults(LongSupplier metricsClock) {
            return new Dependencies(
                    PoolLifecycleDispatcher::replenish,
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
}

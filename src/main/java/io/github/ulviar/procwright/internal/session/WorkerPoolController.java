/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
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
    private final WorkerPoolState<S> state;
    private final PoolReplenisher replenisher;
    private final WorkerRetirementCoordinator<S> retirements;
    private final WorkerStartupCoordinator<S> startups;

    static <S> WorkerPoolController<S> fromSettings(
            Supplier<S> workerFactory,
            WorkerRetirement.Action<S> workerCloser,
            WorkerPoolSettings<?> settings,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            LongSupplier metricsClock) {
        return fromSettings(
                workerFactory,
                workerCloser,
                settings,
                failures,
                workerLabel,
                threadPrefix,
                Dependencies.defaults(metricsClock));
    }

    static <S> WorkerPoolController<S> fromSettings(
            Supplier<S> workerFactory,
            WorkerRetirement.Action<S> workerCloser,
            WorkerPoolSettings<?> settings,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Dependencies dependencies) {
        WorkerPoolSettings<?> configuredSettings =
                Objects.requireNonNull(settings, "settings").validateForOpen();
        return new WorkerPoolController<>(
                workerFactory,
                workerCloser,
                new WorkerPoolPolicy(configuredSettings),
                failures,
                workerLabel,
                threadPrefix,
                dependencies);
    }

    private WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerRetirement.Action<S> workerCloser,
            WorkerPoolPolicy policy,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Dependencies dependencies) {
        Dependencies configuredDependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.workerCloser = Objects.requireNonNull(workerCloser, "workerCloser");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        failurePublisher = new PoolFailurePublisher(configuredDependencies.lateFailureSink());
        metricsClock = configuredDependencies.metricsClock();
        retirements = new WorkerRetirementCoordinator<>(
                task -> PoolLifecycleDispatcher.executeRetirementBatch(task),
                this::processRetirement,
                this::completeUnexpectedRetirementFailure,
                failurePublisher::publish);
        state = new WorkerPoolState<>(policy, this::newStartupWorker, retirements);
        replenisher = new PoolReplenisher(
                configuredDependencies.replenishmentScheduler(),
                state::replenishmentNeeded,
                this::replenishOne,
                this::failReplenishment);
        startups = new WorkerStartupCoordinator<>(failures, workerLabel, this);

        List<FailureReport> commitReports = new WorkerPoolConstruction<>(
                        state,
                        policy.closeTimeout(),
                        failures,
                        this::failConstructionAndClose,
                        this::warmup,
                        this::ensureReplenishment)
                .commit();
        failurePublisher.publishAll(commitReports);
    }

    WorkerPoolState.Lease<S> acquire(HealthCheck<S> healthCheck) {
        Objects.requireNonNull(healthCheck, "healthCheck");
        long startedAtNanos = metricsClock.getAsLong();
        long deadlineNanos = DurationSupport.deadlineFromNow(policy.acquireTimeout());
        try {
            while (true) {
                WorkerPoolState.Lease<S> lease = takeOrStartLease(deadlineNanos);
                try {
                    ensureReplenishment();
                } catch (RuntimeException | Error failure) {
                    rethrow(retireFailedLease(lease, failure, PooledWorkerRetireReason.WORKER_FAILED, true));
                    throw new AssertionError("unreachable");
                }
                if (deadlineNanos - System.nanoTime() <= 0) {
                    throw acquireTimeoutAfterReturning(lease);
                }
                HealthOutcome healthOutcome;
                try {
                    healthOutcome = Objects.requireNonNull(
                            healthCheck.test(lease.session(), deadlineNanos), "healthCheck returned null");
                } catch (RuntimeException | Error failure) {
                    rethrow(retireFailedLease(lease, failure, PooledWorkerRetireReason.HEALTH_FAILED, false));
                    throw new AssertionError("unreachable");
                }
                switch (healthOutcome) {
                    case HEALTHY -> {
                        if (deadlineNanos - System.nanoTime() <= 0) {
                            throw acquireTimeoutAfterReturning(lease);
                        }
                        return lease;
                    }
                    case ACQUIRE_TIMEOUT -> throw acquireTimeoutAfterReturning(lease);
                    case HEALTH_FAILED -> retireLease(lease, PooledWorkerRetireReason.HEALTH_FAILED);
                    case PROCESS_EXITED -> retireLease(lease, PooledWorkerRetireReason.PROCESS_EXITED);
                }
            }
        } catch (RuntimeException | Error failure) {
            rethrow(failures.expose(failure));
            throw new AssertionError("unreachable");
        } finally {
            recordAcquireWait(startedAtNanos);
        }
    }

    void releaseReusable(WorkerPoolState.Lease<S> lease) {
        state.releaseReusable(lease);
        ensureReplenishment();
    }

    void retire(WorkerPoolState.Lease<S> lease, PooledWorkerRetireReason reason) {
        state.retire(lease, reason);
        ensureReplenishment();
    }

    PooledWorkerRetireReason recordRequestAndRetirementReason(WorkerPoolState.Lease<S> lease) {
        return state.recordRequestAndRetirementReason(lease);
    }

    RequestObservation observeRequest() {
        return new RequestObservation(metricsClock, state::recordRequest);
    }

    PooledSessionMetrics metrics() {
        return state.metrics();
    }

    CompletableFuture<Void> closeAsync() {
        try {
            state.beginClose(null);
        } finally {
            replenisher.stop();
        }
        return state.terminationView();
    }

    private void warmup() {
        for (int index = 0; index < policy.warmupSize(); index++) {
            WorkerPoolState.Lease<S> lease = null;
            try {
                PoolWorker<S> worker = reserveWarmupSlot();
                lease = openReservedWorker(worker, DurationSupport.deadlineFromNow(policy.acquireTimeout()));
                returnLease(lease);
            } catch (RuntimeException | Error failure) {
                Throwable terminalFailure = failure;
                if (lease != null) {
                    terminalFailure =
                            retireFailedLease(lease, terminalFailure, PooledWorkerRetireReason.WORKER_FAILED, true);
                }
                rethrow(terminalFailure);
                throw new AssertionError("unreachable");
            }
        }
    }

    private List<FailureReport> failConstructionAndClose() {
        try {
            return state.failConstructionAndClose();
        } finally {
            replenisher.stop();
        }
    }

    private WorkerPoolState.Lease<S> takeOrStartLease(long deadlineNanos) {
        while (true) {
            WorkerPoolState.AcquireResult<S> acquisition;
            acquisition = state.awaitAcquire(deadlineNanos);
            if (acquisition instanceof WorkerPoolState.LeaseAcquired<S> acquired) {
                return acquired.lease();
            }
            if (acquisition instanceof WorkerPoolState.AcquireClosed<S>) {
                throw failures.closed("Pool is closed");
            }
            if (acquisition instanceof WorkerPoolState.AcquireTimedOut<S>) {
                throw failures.acquireTimeout("Timed out waiting for " + workerLabel);
            }
            if (acquisition instanceof WorkerPoolState.AcquireInterrupted<S> interrupted) {
                throw failures.acquireInterrupted(
                        "Interrupted while waiting for " + workerLabel, interrupted.interruption());
            }
            if (acquisition instanceof WorkerPoolState.StartupReserved<S> reserved) {
                return openReservedWorker(reserved.worker(), deadlineNanos);
            }
            throw new AssertionError("Unknown pool acquisition result: " + acquisition);
        }
    }

    private PoolWorker<S> reserveWarmupSlot() {
        PoolWorker<S> worker = state.reserveWarmup();
        if (worker == null) {
            throw failures.closed("Pool is closed");
        }
        return worker;
    }

    private PoolWorker<S> newStartupWorker(PoolWorker.StartupPurpose purpose) {
        PoolWorker<S> worker = new PoolWorker<>(workerCloser, purpose);
        worker.startup(new WorkerStartup<>(
                workerFactory, threadPrefix + "start-", completion -> finishAbandonedStart(worker, completion)));
        return worker;
    }

    private WorkerPoolState.Lease<S> openReservedWorker(PoolWorker<S> worker, long deadlineNanos) {
        WorkerStartup.CreatedWorker<S> createdWorker = startups.start(worker, deadlineNanos);
        WorkerPoolState.Lease<S> lease = state.completeStartup(worker, createdWorker);
        if (lease == null) {
            throw failures.closed("Pool is closed");
        }
        return lease;
    }

    private RuntimeException acquireTimeoutAfterReturning(WorkerPoolState.Lease<S> lease) {
        RuntimeException failure = failures.acquireTimeout("Timed out acquiring " + workerLabel);
        Throwable terminalFailure = failure;
        try {
            returnLease(lease);
        } catch (RuntimeException | Error cleanupFailure) {
            terminalFailure =
                    combineErrorFirst(failure, cleanupFailure, "Pool acquisition timeout and lease return both failed");
        }
        Throwable exposed = failures.expose(terminalFailure);
        if (exposed instanceof Error error) {
            throw error;
        }
        return (RuntimeException) exposed;
    }

    private void finishAbandonedStart(PoolWorker<S> worker, WorkerStartup.LateCompletion<S> completion) {
        boolean present = state.completeAbandonedStartup(worker, completion);
        if (!present) {
            return;
        }
        ensureReplenishment();
        if (completion.failureTarget() != null) {
            reportLateFailure(
                    completion.failureTarget(), Objects.requireNonNull(completion.failure(), "late startup failure"));
        }
    }

    private void retireLease(WorkerPoolState.Lease<S> lease, PooledWorkerRetireReason reason) {
        state.retireLeaseIfOwned(lease, reason, false);
        ensureReplenishment();
    }

    private Throwable retireFailedLease(
            WorkerPoolState.Lease<S> lease,
            Throwable primaryFailure,
            PooledWorkerRetireReason reason,
            boolean closedTakesPrecedence) {
        FailureAccumulator cleanupFailures = new FailureAccumulator();
        try {
            state.retireLeaseIfOwned(lease, reason, closedTakesPrecedence);
        } catch (RuntimeException | Error cleanupFailure) {
            cleanupFailures.add(cleanupFailure);
        }
        try {
            ensureReplenishment();
        } catch (RuntimeException | Error cleanupFailure) {
            cleanupFailures.add(cleanupFailure);
        }
        return combineErrorFirst(
                primaryFailure,
                cleanupFailures.aggregateErrorFirst("Multiple failed-lease cleanup operations failed"),
                "Pool request and failed-lease cleanup both failed");
    }

    private void returnLease(WorkerPoolState.Lease<S> lease) {
        state.releaseReusable(lease);
    }

    private void completeUnexpectedRetirementFailure(PoolWorker<S> worker, Throwable failure) {
        FailureReport failureReport = PoolFailurePublisher.capture(Thread.currentThread(), failure);
        state.failRetirementCompletion(worker, failure);
        failurePublisher.publish(failureReport);
    }

    private FailureReport processRetirement(PoolWorker<S> worker, WorkerRetirement.Outcome outcome) {
        FailureReport closeFailureReport = outcome.failure() == null
                ? null
                : PoolFailurePublisher.capture(Thread.currentThread(), outcome.failure());
        FailureReport lateReport = state.completeRetirement(worker, outcome, closeFailureReport);
        ensureReplenishment();
        return lateReport;
    }

    private void ensureReplenishment() {
        replenisher.ensureStarted();
    }

    private PoolReplenisher.Step replenishOne() {
        PoolWorker<S> worker = state.tryReserveReplenishment();
        if (worker == null) {
            return PoolReplenisher.Step.STOP;
        }
        WorkerPoolState.Lease<S> lease = null;
        try {
            lease = openReservedWorker(worker, DurationSupport.deadlineFromNow(policy.acquireTimeout()));
            returnLease(lease);
            return PoolReplenisher.Step.SUCCESS;
        } catch (RuntimeException | Error failure) {
            Throwable terminalFailure = failure;
            if (lease != null) {
                terminalFailure =
                        retireFailedLease(lease, terminalFailure, PooledWorkerRetireReason.WORKER_FAILED, true);
            }
            if (terminalFailure instanceof Error error) {
                throw error;
            }
            if (!state.replenishmentNeeded()) {
                return PoolReplenisher.Step.STOP;
            }
            failurePublisher.publish(PoolFailurePublisher.capture(Thread.currentThread(), failure));
            reportAdditionalFailures(failure, terminalFailure);
            return PoolReplenisher.Step.RETRY;
        }
    }

    private void failReplenishment(Throwable failure) {
        FailureReport lateReport = PoolFailurePublisher.capture(Thread.currentThread(), failure);
        PoolTermination.FailureDisposition disposition = null;
        Throwable cleanupFailure = null;
        try {
            disposition = state.beginClose(failure);
        } catch (RuntimeException | Error failureToClose) {
            cleanupFailure = failureToClose;
        } finally {
            replenisher.stop();
        }
        if (disposition == PoolTermination.FailureDisposition.REPORT) {
            failurePublisher.publish(lateReport);
        }
        if (cleanupFailure != null) {
            failurePublisher.publish(PoolFailurePublisher.capture(Thread.currentThread(), cleanupFailure));
        }
    }

    private void recordAcquireWait(long startedAtNanos) {
        long elapsedNanos = Math.max(0, metricsClock.getAsLong() - startedAtNanos);
        state.recordAcquireWait(elapsedNanos);
    }

    private void reportLateFailure(BoundedFailureReporter.FailureTarget failureTarget, Throwable failure) {
        FailureReport report = state.routeLateFailure(new FailureReport(failureTarget, failure));
        failurePublisher.publish(report);
    }

    private void reportAdditionalFailures(Throwable primary, Throwable aggregate) {
        FailureAccumulator additional = new FailureAccumulator();
        for (Throwable source : FailureAggregation.sources(aggregate)) {
            if (source != primary) {
                additional.add(source);
            }
        }
        Throwable failure = additional.aggregateErrorFirst("Multiple pool cleanup operations failed");
        if (failure != null) {
            failurePublisher.publish(PoolFailurePublisher.capture(Thread.currentThread(), failure));
        }
    }

    @Override
    public WorkerStartupCoordinator.StartupDecision preflight(PoolWorker<S> worker, long deadlineNanos) {
        return state.preflightStartup(worker, deadlineNanos);
    }

    @Override
    public void factoryFailed(PoolWorker<S> worker, Throwable failure) {
        state.factoryFailed(worker, failure);
        ensureReplenishment();
    }

    @Override
    public void discardStartingWorker(PoolWorker<S> worker) {
        state.discardStartingWorker(worker);
        ensureReplenishment();
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Pool lifecycle produced a checked failure", failure);
    }

    private static Throwable combineErrorFirst(Throwable first, Throwable second, String message) {
        FailureAccumulator failures = new FailureAccumulator();
        failures.add(first);
        failures.add(second);
        return failures.aggregateErrorFirst(message);
    }

    interface FailureFactory {

        RuntimeException closed(String message);

        RuntimeException acquireTimeout(String message);

        RuntimeException acquireInterrupted(String message, InterruptedException cause);

        RuntimeException startupFailed(String message, Throwable cause);

        RuntimeException retirementFailed(String message, Throwable cause);

        default Throwable exposeAggregate(RuntimeException primary, Throwable aggregate) {
            return aggregate;
        }

        default Throwable expose(Throwable failure) {
            Throwable aggregate = Objects.requireNonNull(failure, "failure");
            if (FailureAggregation.sources(aggregate).size() == 1) {
                return aggregate;
            }
            Throwable primary = FailureAggregation.primary(aggregate);
            return primary instanceof RuntimeException runtimeFailure
                    ? exposeAggregate(runtimeFailure, aggregate)
                    : aggregate;
        }
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
            PoolReplenisher.Scheduler replenishmentScheduler,
            Consumer<FailureReport> lateFailureSink,
            LongSupplier metricsClock) {

        Dependencies {
            Objects.requireNonNull(replenishmentScheduler, "replenishmentScheduler");
            Objects.requireNonNull(lateFailureSink, "lateFailureSink");
            Objects.requireNonNull(metricsClock, "metricsClock");
        }

        static Dependencies defaults(LongSupplier metricsClock) {
            return new Dependencies(
                    PoolReplenishmentScheduler::schedule, PoolFailurePublisher::reportBounded, metricsClock);
        }
    }
}

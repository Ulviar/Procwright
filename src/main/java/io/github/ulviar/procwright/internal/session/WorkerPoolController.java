/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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
        failurePublisher = new PoolFailurePublisher(configuredDependencies.lateFailureReporter());
        metricsClock = configuredDependencies.metricsClock();
        retirements = new WorkerRetirementCoordinator<>(
                task -> PoolLifecycleDispatcher.executeRetirementBatch(task),
                this::processRetirement,
                this::completeUnexpectedRetirementFailure,
                failurePublisher::publish);
        state = new WorkerPoolState<>(policy, new PoolTermination(), this::newStartupReservation);
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
        startups = new WorkerStartupCoordinator<>(failures, workerLabel, this);

        List<FailureReport> commitReports = new WorkerPoolConstruction<>(
                        state,
                        policy.closeTimeout(),
                        failures,
                        this::failConstructionAndClose,
                        this::warmup,
                        this::ensureReplenishmentOwner)
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
                    ensureReplenishmentOwner();
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
        runEffects(newEffects(), effects -> {
            state.releaseReusable(lease, effects);
        });
        ensureReplenishmentOwner();
    }

    void retire(WorkerPoolState.Lease<S> lease, PooledWorkerRetireReason reason) {
        runEffects(newEffects(), effects -> {
            state.retire(lease, reason, effects);
        });
        ensureReplenishmentOwner();
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

    boolean awaitMetrics(Predicate<PooledSessionMetrics> condition, Duration timeout) throws InterruptedException {
        return state.awaitMetrics(condition, timeout);
    }

    CompletableFuture<Void> closeAsync() {
        runEffects(newEffects(), effects -> {
            state.beginClose(null, effects);
        });
        return state.terminationView();
    }

    private void warmup() {
        for (int index = 0; index < policy.warmupSize(); index++) {
            WorkerStartupCoordinator.Reservation<S> reservation = null;
            WorkerPoolState.Lease<S> lease = null;
            try {
                reservation = reserveWarmupSlot();
                lease = openReservedWorker(reservation, DurationSupport.deadlineFromNow(policy.acquireTimeout()));
                returnLease(lease);
            } catch (RuntimeException | Error failure) {
                Throwable terminalFailure = failure;
                if (reservation != null) {
                    terminalFailure = failStartupReservation(reservation, terminalFailure);
                }
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
        return applyEffects(newEffects(), state::failConstructionAndClose);
    }

    private WorkerPoolState.Lease<S> takeOrStartLease(long deadlineNanos) {
        while (true) {
            WorkerPoolState.AcquireResult<S> acquisition;
            acquisition = applyEffects(newEffects(), effects -> state.awaitAcquire(deadlineNanos, effects));
            if (acquisition instanceof WorkerPoolState.LeaseAcquired<S> acquired) {
                return acquired.lease();
            }
            if (acquisition instanceof WorkerPoolState.RetryAcquire<S>) {
                continue;
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
                WorkerStartupCoordinator.Reservation<S> reservation = reserved.reservation();
                try {
                    return openReservedWorker(reservation, deadlineNanos);
                } catch (RuntimeException | Error failure) {
                    rethrow(failStartupReservation(reservation, failure));
                    throw new AssertionError("unreachable");
                }
            }
            throw new AssertionError("Unknown pool acquisition result: " + acquisition);
        }
    }

    private WorkerStartupCoordinator.Reservation<S> reserveWarmupSlot() {
        WorkerPoolState.ReservationResult<S> result = state.reserveWarmup();
        if (result instanceof WorkerPoolState.SlotReserved<S> reserved) {
            return reserved.reservation();
        }
        if (result instanceof WorkerPoolState.ReservationClosed<S>) {
            throw failures.closed("Pool is closed");
        }
        throw new AssertionError("Unknown warmup reservation result: " + result);
    }

    private WorkerStartupCoordinator.Reservation<S> newStartupReservation(PoolWorker.StartupPurpose purpose) {
        PoolWorker<S> worker = new PoolWorker<>(workerCloser, purpose);
        WorkerStartupCoordinator.Reservation<S> reservation = new WorkerStartupCoordinator.Reservation<>(worker);
        worker.startup(new WorkerStartup<>(
                workerFactory, threadPrefix + "start-", completion -> finishAbandonedStart(reservation, completion)));
        return reservation;
    }

    private WorkerPoolState.Lease<S> openReservedWorker(
            WorkerStartupCoordinator.Reservation<S> reservation, long deadlineNanos) {
        PoolWorker.StartupPurpose purpose = reservation.purpose();
        WorkerStartupCoordinator.Completion<S> completion = startups.start(reservation, deadlineNanos);
        WorkerStartup.TerminalDecision decision = completion.decision();
        WorkerPoolState.Lease<S> lease = applyEffects(
                newEffects(),
                effects -> state.completeStartup(reservation, completion.createdWorker(), decision, effects));
        if (decision == WorkerStartup.TerminalDecision.FACTORY_COMPLETED) {
            return Objects.requireNonNull(lease, "completed startup lease");
        }
        throw startups.rejectedCompletion(purpose, decision);
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

    private void finishAbandonedStart(
            WorkerStartupCoordinator.Reservation<S> reservation, WorkerStartup.LateCompletion<S> completion) {
        boolean present =
                applyEffects(newEffects(), effects -> state.completeAbandonedStartup(reservation, completion, effects));
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
        runEffects(newEffects(), effects -> {
            state.retireLeaseIfOwned(lease, reason, false, effects);
        });
        ensureReplenishmentOwner();
    }

    private Throwable retireFailedLease(
            WorkerPoolState.Lease<S> lease,
            Throwable primaryFailure,
            PooledWorkerRetireReason reason,
            boolean closedTakesPrecedence) {
        FailureAccumulator cleanupFailures = new FailureAccumulator();
        try {
            runEffects(newEffects(), effects -> {
                state.retireLeaseIfOwned(lease, reason, closedTakesPrecedence, effects);
            });
        } catch (RuntimeException | Error cleanupFailure) {
            cleanupFailures.add(cleanupFailure);
        }
        try {
            ensureReplenishmentOwner();
        } catch (RuntimeException | Error cleanupFailure) {
            cleanupFailures.add(cleanupFailure);
        }
        return combineErrorFirst(
                primaryFailure,
                cleanupFailures.aggregateErrorFirst("Multiple failed-lease cleanup operations failed"),
                "Pool request and failed-lease cleanup both failed");
    }

    private void returnLease(WorkerPoolState.Lease<S> lease) {
        runEffects(newEffects(), effects -> {
            state.releaseReusable(lease, effects);
        });
    }

    private void completeUnexpectedRetirementFailure(PoolWorker<S> worker, Throwable failure) {
        FailureReport failureReport = PoolFailurePublisher.capture(Thread.currentThread(), failure);
        runEffects(newEffects(), effects -> {
            state.failRetirementCompletion(worker, failure, effects);
        });
        failurePublisher.publish(failureReport);
    }

    private FailureReport processRetirement(PoolWorker<S> worker, WorkerRetirement.Outcome outcome) {
        FailureReport closeFailureReport = outcome.failure() == null
                ? null
                : PoolFailurePublisher.capture(Thread.currentThread(), outcome.failure());
        FailureReport lateReport = applyEffects(
                newEffects(), effects -> state.completeRetirement(worker, outcome, closeFailureReport, effects));
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
        WorkerStartupCoordinator.Reservation<S> reservation = state.tryReserveReplenishment();
        if (reservation == null) {
            return PoolReplenisher.Step.STOP;
        }
        WorkerPoolState.Lease<S> lease = null;
        try {
            lease = openReservedWorker(reservation, DurationSupport.deadlineFromNow(policy.acquireTimeout()));
            returnLease(lease);
            return PoolReplenisher.Step.SUCCESS;
        } catch (RuntimeException | Error failure) {
            Throwable terminalFailure = failStartupReservation(reservation, failure);
            if (lease != null) {
                terminalFailure =
                        retireFailedLease(lease, terminalFailure, PooledWorkerRetireReason.WORKER_FAILED, true);
            }
            if (terminalFailure instanceof Error error) {
                throw error;
            }
            reportAdditionalFailures(failure, terminalFailure);
            return PoolReplenisher.Step.RETRY;
        }
    }

    private Throwable failStartupReservation(
            WorkerStartupCoordinator.Reservation<S> reservation, Throwable primaryFailure) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        if (!reservation.canRollback()) {
            return primaryFailure;
        }
        FailureAccumulator cleanupFailures = new FailureAccumulator();
        try {
            runEffects(newEffects(), effects -> state.removeFailedStartup(reservation, effects));
        } catch (RuntimeException | Error cleanupFailure) {
            cleanupFailures.add(cleanupFailure);
        }
        try {
            ensureReplenishmentOwner();
        } catch (RuntimeException | Error cleanupFailure) {
            cleanupFailures.add(cleanupFailure);
        }
        return combineErrorFirst(
                primaryFailure,
                cleanupFailures.aggregateErrorFirst("Multiple startup reservation cleanup operations failed"),
                "Worker startup and reservation cleanup both failed");
    }

    private void failReplenishmentOwner(Throwable failure) {
        FailureReport lateReport = PoolFailurePublisher.capture(Thread.currentThread(), failure);
        PoolTermination.FailureDisposition disposition = null;
        Throwable cleanupFailure = null;
        try {
            disposition = applyEffects(newEffects(), effects -> state.beginClose(failure, effects));
        } catch (RuntimeException | Error failureToClose) {
            cleanupFailure = failureToClose;
        }
        if (disposition == PoolTermination.FailureDisposition.LATE) {
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

    private void reportOrRecordLateError(BoundedFailureReporter.FailureTarget failureTarget, Throwable failure) {
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
    public WorkerStartupCoordinator.StartupClaim claimLaunch(
            WorkerStartupCoordinator.Reservation<S> reservation, long deadlineNanos) {
        return state.claimStartup(reservation, deadlineNanos);
    }

    @Override
    public void launchFailed(WorkerStartupCoordinator.Reservation<S> reservation) {
        runEffects(newEffects(), effects -> {
            state.launchFailed(reservation, effects);
        });
        ensureReplenishmentOwner();
    }

    @Override
    public boolean factoryFailed(WorkerStartupCoordinator.Reservation<S> reservation, Throwable failure) {
        boolean closedStartup =
                applyEffects(newEffects(), effects -> state.factoryFailed(reservation, failure, effects));
        ensureReplenishmentOwner();
        return closedStartup;
    }

    private <T> T applyEffects(PoolStateEffects<S> effects, Function<PoolStateEffects<S>, T> operation) {
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(operation, "operation");
        T result = null;
        Throwable failure = null;
        try {
            result = operation.apply(effects);
        } catch (RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }
        try {
            effects.close();
        } catch (RuntimeException | Error closeFailure) {
            failure = combineErrorFirst(failure, closeFailure, "Pool state transition and its effects both failed");
        }
        if (failure != null) {
            rethrow(failure);
        }
        return result;
    }

    private void runEffects(PoolStateEffects<S> effects, Consumer<PoolStateEffects<S>> operation) {
        applyEffects(effects, selected -> {
            operation.accept(selected);
            return null;
        });
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
            Consumer<Runnable> replenishmentStarter,
            BiConsumer<Thread, Throwable> lateFailureReporter,
            LongSupplier metricsClock,
            PoolReplenisher.Waiter backoffWaiter) {

        Dependencies {
            Objects.requireNonNull(replenishmentStarter, "replenishmentStarter");
            Objects.requireNonNull(lateFailureReporter, "lateFailureReporter");
            Objects.requireNonNull(metricsClock, "metricsClock");
        }

        static Dependencies defaults(LongSupplier metricsClock) {
            return new Dependencies(
                    PoolLifecycleDispatcher::replenish, PoolFailurePublisher::reportBounded, metricsClock, null);
        }
    }
}

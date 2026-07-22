/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class WorkerPoolController<S> {

    private static final Duration MIN_REPLENISH_BACKOFF = Duration.ofMillis(10);
    private static final Duration MAX_REPLENISH_BACKOFF = Duration.ofMillis(250);

    private final Supplier<S> workerFactory;
    private final WorkerCloseAction<S> workerCloser;
    private final int maxSize;
    private final int warmupSize;
    private final int minIdle;
    private final Duration acquireTimeout;
    private final Duration closeTimeout;
    private final int maxRequestsPerWorker;
    private final Duration maxWorkerAge;
    private final boolean backgroundReplenishment;
    private final FailureFactory failures;
    private final String workerLabel;
    private final String threadPrefix;
    private final Consumer<Runnable> replenishmentStarter;
    private final TerminalRetirementDispatcher retirementDispatcher;
    private final LateFailureReporter lateFailureReporter;
    private final NanoClock metricsClock;
    private final BackoffWaiter backoffWaiter;
    private final TestHooks testHooks;
    private final RetirementAdmissionProvider poolCompletionAdmissions;
    private final RetirementAdmissionProvider retirementAdmissions;
    private final PoolRetirementDispatcher.Admission poolAdmission;
    private final PoolCompletionOwner completionOwner;
    private final Thread constructionThread;
    private final Set<Worker<S>> slots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<Worker<S>> idle = new ArrayDeque<>();
    private final ArrayDeque<Worker<S>> retirementQueue = new ArrayDeque<>();
    private final ArrayDeque<RetirementOwnerCompletion> retirementOwnerCompletions = new ArrayDeque<>();
    private final ConstructionLedger construction = new ConstructionLedger();
    private final RetirementScheduling retirementScheduling = new RetirementScheduling();
    private final EnumMap<PooledWorkerRetireReason, Long> retireReasons = new EnumMap<>(PooledWorkerRetireReason.class);
    private final Object lock = new Object();
    private final Object retirementCompletionLock = new Object();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final CompletableFuture<Void> slotsReleased = new CompletableFuture<>();

    private boolean closing;
    private boolean replenishmentOwnerActive;
    private boolean drainPublicationClaimed;
    private boolean retirementCompletionProcessorActive;
    private long created;
    private long retired;
    private long completedRequests;
    private long failedRequests;
    private long failedStartups;
    private long failedWorkerCloses;
    private long totalAcquireWaitNanos;
    private long totalRequestDurationNanos;
    private long totalWorkerStartupNanos;
    private final WorkerCloseFailureAccumulator drainFailures = new WorkerCloseFailureAccumulator();

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
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
                task -> Threading.start(threadPrefix + "replenish-", task),
                sharedRetirementDispatcher(),
                WorkerPoolController::reportBounded,
                System::nanoTime,
                null,
                TestHooks.NONE);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            NanoClock metricsClock,
            TerminalRetirementDispatcher terminalDispatcher) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                task -> Threading.start(threadPrefix + "replenish-", task),
                terminalDispatcher,
                WorkerPoolController::reportBounded,
                metricsClock,
                null,
                TestHooks.NONE);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Consumer<Runnable> replenishmentStarter,
            Consumer<Runnable> retirementStarter) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                replenishmentStarter,
                adaptRetirementStarter(retirementStarter),
                WorkerPoolController::reportBounded,
                System::nanoTime,
                null,
                TestHooks.NONE);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
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
                task -> Threading.start(threadPrefix + "replenish-", task),
                sharedRetirementDispatcher(),
                WorkerPoolController::reportBounded,
                metricsClock,
                null,
                TestHooks.NONE);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Consumer<Runnable> replenishmentStarter,
            Consumer<Runnable> retirementStarter,
            LateFailureReporter lateFailureReporter,
            NanoClock metricsClock,
            BackoffWaiter backoffWaiter) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                replenishmentStarter,
                adaptRetirementStarter(retirementStarter),
                lateFailureReporter,
                metricsClock,
                backoffWaiter,
                TestHooks.NONE);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Consumer<Runnable> replenishmentStarter,
            Consumer<Runnable> retirementStarter,
            LateFailureReporter lateFailureReporter,
            NanoClock metricsClock,
            BackoffWaiter backoffWaiter,
            TestHooks testHooks) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                replenishmentStarter,
                adaptRetirementStarter(retirementStarter),
                lateFailureReporter,
                metricsClock,
                backoffWaiter,
                testHooks);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Consumer<Runnable> replenishmentStarter,
            TerminalRetirementDispatcher retirementDispatcher,
            LateFailureReporter lateFailureReporter,
            NanoClock metricsClock,
            BackoffWaiter backoffWaiter,
            TestHooks testHooks) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                replenishmentStarter,
                retirementDispatcher,
                lateFailureReporter,
                metricsClock,
                backoffWaiter,
                testHooks,
                PoolRetirementDispatcher::admitPoolCompletion,
                PoolRetirementDispatcher::admit);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Consumer<Runnable> replenishmentStarter,
            TerminalRetirementDispatcher retirementDispatcher,
            LateFailureReporter lateFailureReporter,
            NanoClock metricsClock,
            BackoffWaiter backoffWaiter,
            TestHooks testHooks,
            RetirementAdmissionProvider retirementAdmissions) {
        this(
                workerFactory,
                workerCloser,
                options,
                failures,
                workerLabel,
                threadPrefix,
                replenishmentStarter,
                retirementDispatcher,
                lateFailureReporter,
                metricsClock,
                backoffWaiter,
                testHooks,
                retirementAdmissions,
                retirementAdmissions);
    }

    WorkerPoolController(
            Supplier<S> workerFactory,
            WorkerCloseAction<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String threadPrefix,
            Consumer<Runnable> replenishmentStarter,
            TerminalRetirementDispatcher retirementDispatcher,
            LateFailureReporter lateFailureReporter,
            NanoClock metricsClock,
            BackoffWaiter backoffWaiter,
            TestHooks testHooks,
            RetirementAdmissionProvider poolCompletionAdmissions,
            RetirementAdmissionProvider retirementAdmissions) {
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.workerCloser = Objects.requireNonNull(workerCloser, "workerCloser");
        PoolOptions configuredOptions = Objects.requireNonNull(options, "options");
        maxSize = configuredOptions.maxSize();
        warmupSize = configuredOptions.warmupSize();
        minIdle = configuredOptions.minIdle();
        acquireTimeout = configuredOptions.acquireTimeout();
        closeTimeout = configuredOptions.closeTimeout();
        maxRequestsPerWorker = configuredOptions.maxRequestsPerWorker();
        maxWorkerAge = configuredOptions.maxWorkerAge();
        backgroundReplenishment = configuredOptions.backgroundReplenishment();
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        this.replenishmentStarter = Objects.requireNonNull(replenishmentStarter, "replenishmentStarter");
        this.retirementDispatcher = Objects.requireNonNull(retirementDispatcher, "retirementDispatcher");
        this.lateFailureReporter = Objects.requireNonNull(lateFailureReporter, "lateFailureReporter");
        this.metricsClock = Objects.requireNonNull(metricsClock, "metricsClock");
        this.backoffWaiter = backoffWaiter == null ? this::awaitBackoffWithLock : backoffWaiter;
        this.testHooks = Objects.requireNonNull(testHooks, "testHooks");
        this.poolCompletionAdmissions = Objects.requireNonNull(poolCompletionAdmissions, "poolCompletionAdmissions");
        this.retirementAdmissions = Objects.requireNonNull(retirementAdmissions, "retirementAdmissions");
        constructionThread = Thread.currentThread();
        if (minIdle > 0 && !backgroundReplenishment) {
            throw new IllegalArgumentException("minIdle requires backgroundReplenishment");
        }
        poolAdmission = acquirePoolAdmission();
        try {
            completionOwner = new PoolCompletionOwner(poolAdmission, drained);
            completionOwner.start();
        } catch (RuntimeException | Error failure) {
            poolAdmission.close();
            throw failures.startupFailed("Could not start pool completion owner", failure);
        }
        List<FailureReport> commitReports = List.of();
        try {
            warmup();
            ensureReplenishmentOwner();
            testHooks.beforeConstructionCommit().run();
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
            try {
                testHooks.beforeConstructionFailure().accept(failure);
            } catch (RuntimeException | Error hookFailure) {
                SuppressionSupport.attach(failure, hookFailure);
            }
            cleanupFailedConstruction(failure);
            throw failure;
        }
        reportAll(commitReports);
        ensureRetirementOwner();
    }

    Worker<S> acquire(HealthCheck<S> healthCheck) {
        Objects.requireNonNull(healthCheck, "healthCheck");
        long startedAtNanos = metricsClock.nanoTime();
        long deadlineNanos = DurationSupport.deadlineFromNow(acquireTimeout);
        LeaseHandoff handoff = new LeaseHandoff();
        StartupReservationOwner startupReservation = new StartupReservationOwner();
        boolean acquireWaitAttempted = false;
        try {
            while (true) {
                Worker<S> worker = takeOrReserveWorker(deadlineNanos, handoff, startupReservation);
                if (worker.state() == SlotState.STARTING) {
                    testHooks.beforeStartupHandoff().run();
                    worker = openReservedWorker(startupReservation, deadlineNanos, StartupPurpose.DEMAND, handoff);
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
            startupReservation.fail(failure);
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

    void release(Worker<S> worker, boolean reusable, PooledWorkerRetireReason failureReason) {
        Objects.requireNonNull(worker, "worker");
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        try {
            synchronized (lock) {
                requireState(worker, SlotState.LEASED);
                PooledWorkerRetireReason reason = reusable ? retireReasonForPolicy(worker) : failureReason;
                if (closing && reason == null) {
                    reason = PooledWorkerRetireReason.CLOSED;
                }
                if (reason == null) {
                    transition(worker, SlotState.IDLE);
                    idle.addLast(worker);
                } else {
                    markRetiring(worker, reason, retirementDispatch);
                }
                verifyPartition();
                lock.notifyAll();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        ensureReplenishmentOwner();
        publishDrainIfReady();
    }

    PooledWorkerRetireReason retirementReasonFor(Worker<S> worker) {
        Objects.requireNonNull(worker, "worker");
        synchronized (lock) {
            requireState(worker, SlotState.LEASED);
            return closing ? PooledWorkerRetireReason.CLOSED : retireReasonForPolicy(worker);
        }
    }

    RequestObservation observeRequest() {
        return new RequestObservation();
    }

    MetricsSnapshot metrics() {
        synchronized (lock) {
            return metricsLocked();
        }
    }

    boolean awaitMetrics(Predicate<MetricsSnapshot> condition, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        long deadlineNanos = DurationSupport.deadlineFromNow(DurationSupport.requirePositive(timeout, "timeout"));
        synchronized (lock) {
            while (!condition.test(metricsLocked())) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
            }
            return true;
        }
    }

    private MetricsSnapshot metricsLocked() {
        StateCounts counts = stateCounts();
        return new MetricsSnapshot(
                counts.size(),
                counts.idle(),
                counts.leased(),
                counts.starting(),
                counts.retiring(),
                created,
                retired,
                completedRequests,
                failedRequests,
                failedStartups,
                failedWorkerCloses,
                totalAcquireWaitNanos,
                totalRequestDurationNanos,
                totalWorkerStartupNanos,
                Map.copyOf(retireReasons));
    }

    CompletableFuture<Void> closeAsync() {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        DrainPublication publication;
        try {
            synchronized (lock) {
                enterClosingLocked(null, retirementDispatch);
                retirementDispatch.observeQueueLocked();
                publication = drainPublication();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        publish(publication);
        return drained.copy();
    }

    CompletableFuture<Void> slotReleaseCompletion() {
        return slotsReleased.copy();
    }

    private void warmup() {
        for (int index = 0; index < warmupSize; index++) {
            LeaseHandoff handoff = new LeaseHandoff();
            StartupReservationOwner startupReservation = new StartupReservationOwner();
            try {
                reserveSlot(startupReservation);
                openReservedWorker(
                        startupReservation,
                        DurationSupport.deadlineFromNow(acquireTimeout),
                        StartupPurpose.WARMUP,
                        handoff);
                handoff.transferToPoolLifecycle();
            } catch (RuntimeException | Error failure) {
                startupReservation.fail(failure);
                handoff.fail(failure);
                throw failure;
            }
        }
    }

    private void cleanupFailedConstruction(Throwable primary) {
        List<FailureReport> completedFailures;
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        try {
            synchronized (lock) {
                completedFailures = construction.fail();
                enterClosingLocked(null, retirementDispatch);
                retirementDispatch.observeQueueLocked();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        publishDrainIfReady();
        awaitFailedConstructionCleanup(primary, completedFailures);
    }

    private void awaitFailedConstructionCleanup(Throwable primary, List<FailureReport> completedFailures) {
        WorkerCloseFailureAccumulator cleanupFailures = new WorkerCloseFailureAccumulator();
        completedFailures.forEach(report -> cleanupFailures.add(report.failure()));
        long deadlineNanos = DurationSupport.deadlineFromNow(closeTimeout);
        boolean restoreInterrupt = false;
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("pool construction cleanup deadline elapsed");
            }
            drained.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            cleanupFailures.add(failure);
        } catch (InterruptedException failure) {
            restoreInterrupt = true;
            cleanupFailures.add(failure);
        } catch (ExecutionException failure) {
            cleanupFailures.add(unwrapCompletionFailure(failure.getCause()));
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

    private Worker<S> takeOrReserveWorker(
            long deadlineNanos, LeaseHandoff handoff, StartupReservationOwner startupReservation) {
        while (true) {
            boolean retirementQueued = false;
            Worker<S> selected = null;
            AcquireWaitFailure waitFailure = null;
            InterruptedException waitInterruption = null;
            RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
            try {
                synchronized (lock) {
                    if (closing) {
                        waitFailure = AcquireWaitFailure.CLOSED;
                    } else {
                        Worker<S> worker = idle.pollFirst();
                        if (worker != null) {
                            requireState(worker, SlotState.IDLE);
                            PooledWorkerRetireReason reason = retireReasonForPolicy(worker);
                            if (reason == null) {
                                transitionToLeased(worker, handoff);
                                selected = worker;
                            } else {
                                markRetiring(worker, reason, retirementDispatch);
                                retirementQueued = true;
                            }
                        } else if (slots.size() < maxSize) {
                            selected = reserveSlotLocked(startupReservation);
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
                        if (waitFailure == null) {
                            verifyPartition();
                        }
                    }
                }
            } finally {
                retirementDispatch.ensureStarted();
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
                return selected;
            }
        }
    }

    private Worker<S> reserveSlot(StartupReservationOwner startupReservation) {
        Worker<S> reservation = null;
        boolean poolClosed;
        synchronized (lock) {
            poolClosed = closing;
            if (!poolClosed && slots.size() >= maxSize) {
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

    private Worker<S> reserveSlotLocked(StartupReservationOwner startupReservation) {
        Worker<S> worker = startupReservation.registerLocked();
        verifyPartition();
        lock.notifyAll();
        return worker;
    }

    private Worker<S> openReservedWorker(
            StartupReservationOwner startupReservation,
            long deadlineNanos,
            StartupPurpose purpose,
            LeaseHandoff handoff) {
        Worker<S> reservation = startupReservation.worker();
        StartAttemptOwner owner;
        BoundedTaskRunner.Permit permit;
        try {
            startupReservation.admit(retirementAdmissions.acquire(deadlineNanos));
            boolean poolClosed;
            synchronized (lock) {
                poolClosed = !slots.contains(reservation) || closing;
                if (!poolClosed) {
                    requireState(reservation, SlotState.STARTING);
                    reservation.startupPurpose(purpose);
                }
            }
            if (poolClosed) {
                throw failures.closed("Pool is closed");
            }
            permit = BoundedTaskRunner.WORKER_STARTUPS.acquire(deadlineNanos);
        } catch (TimeoutException exception) {
            StartupTerminalDecision decision = reservation.decideStartupTerminal(StartupTerminalDecision.TIMED_OUT);
            testHooks.afterStartupTimeoutSignal().run();
            failUnstartedReservation(reservation);
            startupReservation.disownRemovedSlot();
            if (decision == StartupTerminalDecision.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            if (decision != StartupTerminalDecision.TIMED_OUT) {
                throw new IllegalStateException(
                        "queued worker timeout has incompatible terminal decision: " + decision);
            }
            throw startupTimeout(purpose, exception);
        } catch (InterruptedException exception) {
            failUnstartedReservation(reservation);
            startupReservation.disownRemovedSlot();
            Thread.currentThread().interrupt();
            throw failures.acquireInterrupted("Interrupted while starting " + workerLabel, exception);
        } catch (RuntimeException | Error failure) {
            failUnstartedReservation(reservation);
            startupReservation.disownRemovedSlot();
            throw failure;
        }

        boolean permitTransferred = false;
        try {
            StartupClaim claim = claimStartupExecution(reservation, deadlineNanos);
            if (claim != StartupClaim.RUN) {
                startupReservation.disownRemovedSlot();
                publishDrainIfReady();
                if (claim == StartupClaim.CLOSED) {
                    throw failures.closed("Pool is closed");
                }
                throw startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed before launch"));
            }
            testHooks.faultInjector().check(FaultPoint.AFTER_STARTUP_CLAIM);
            owner = new StartAttemptOwner(reservation, permit);
            permitTransferred = true;
            startupReservation.transferToAttempt();
            try {
                owner.start();
            } catch (RuntimeException | Error failure) {
                failRunningReservation(reservation);
                throw failure;
            }
        } finally {
            if (!permitTransferred) {
                permit.close();
            }
        }

        CreatedWorker<S> createdWorker;
        try {
            createdWorker = owner.await(deadlineNanos);
        } catch (TimeoutException exception) {
            StartupTerminalDecision decision = reservation.startupTerminalDecision();
            PooledWorkerRetireReason reason =
                    switch (decision) {
                        case CLOSED -> PooledWorkerRetireReason.CLOSED;
                        case TIMED_OUT -> PooledWorkerRetireReason.STARTUP_TIMEOUT;
                        case FACTORY_COMPLETED, UNDECIDED ->
                            throw new IllegalStateException("worker startup timeout has no terminal decision");
                    };
            owner.abandon(reason);
            if (reason == PooledWorkerRetireReason.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            throw startupTimeout(purpose, exception);
        } catch (InterruptedException exception) {
            owner.abandon(PooledWorkerRetireReason.STARTUP_INTERRUPTED);
            Thread.currentThread().interrupt();
            throw failures.acquireInterrupted("Interrupted while starting " + workerLabel, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            StartupFailureOutcome outcome = finishFailedStart(reservation);
            if (cause instanceof Error error) {
                throw error;
            }
            if (outcome == StartupFailureOutcome.CLOSED) {
                RuntimeException closed = failures.closed("Pool is closed");
                SuppressionSupport.attach(closed, cause);
                throw closed;
            }
            throw failures.startupFailed("Could not start " + workerLabel, cause);
        }

        StartupTerminalDecision decision;
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        try {
            synchronized (lock) {
                requireState(reservation, SlotState.STARTING);
                decision = reservation.startupTerminalDecision();
                if (decision == StartupTerminalDecision.UNDECIDED) {
                    throw new IllegalStateException("successful worker startup has no terminal decision");
                }
                reservation.accept(createdWorker.session(), workerCloser);
                created++;
                totalWorkerStartupNanos += createdWorker.startupNanos();
                switch (decision) {
                    case FACTORY_COMPLETED -> transitionToLeased(reservation, handoff);
                    case CLOSED -> markRetiring(reservation, PooledWorkerRetireReason.CLOSED, retirementDispatch);
                    case TIMED_OUT ->
                        markRetiring(reservation, PooledWorkerRetireReason.STARTUP_TIMEOUT, retirementDispatch);
                    case UNDECIDED -> throw new AssertionError("unreachable terminal decision");
                }
                verifyPartition();
                lock.notifyAll();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        if (decision == StartupTerminalDecision.FACTORY_COMPLETED) {
            return reservation;
        }
        publishDrainIfReady();
        if (decision == StartupTerminalDecision.CLOSED) {
            throw failures.closed("Pool is closed");
        }
        throw startupTimeout(
                purpose, new TimeoutException("worker startup completed after timeout won terminal decision"));
    }

    private StartupClaim claimStartupExecution(Worker<S> reservation, long deadlineNanos) {
        synchronized (lock) {
            if (!slots.contains(reservation) || closing || reservation.startupStage() == StartupStage.CANCELLED) {
                reservation.decideStartupTerminal(StartupTerminalDecision.CLOSED);
                return StartupClaim.CLOSED;
            }
            requireState(reservation, SlotState.STARTING);
            if (deadlineNanos - System.nanoTime() <= 0) {
                reservation.decideStartupTerminal(StartupTerminalDecision.TIMED_OUT);
                reservation.startupStage(StartupStage.CANCELLED);
                removeSlot(reservation);
                reservation.releaseRetirementAdmission();
                verifyPartition();
                lock.notifyAll();
                return StartupClaim.TIMED_OUT;
            }
            reservation.startupStage(StartupStage.RUNNING);
            return StartupClaim.RUN;
        }
    }

    private RuntimeException startupTimeout(StartupPurpose purpose, TimeoutException cause) {
        if (purpose == StartupPurpose.WARMUP) {
            return failures.startupFailed("Timed out warming " + workerLabel, cause);
        }
        return failures.acquireTimeout("Timed out starting " + workerLabel);
    }

    private PoolRetirementDispatcher.Admission acquirePoolAdmission() {
        try {
            return poolCompletionAdmissions.acquire(DurationSupport.deadlineFromNow(acquireTimeout));
        } catch (TimeoutException failure) {
            throw failures.startupFailed("Could not admit pool lifecycle ownership", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failures.startupFailed("Interrupted while admitting pool lifecycle ownership", failure);
        }
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

    private boolean failUnstartedReservation(Worker<S> reservation) {
        boolean closedReservation;
        synchronized (lock) {
            closedReservation = reservation.startupTerminalDecision() == StartupTerminalDecision.CLOSED;
            if (removeSlot(reservation)) {
                verifyPartition();
                lock.notifyAll();
            }
            reservation.releaseRetirementAdmission();
        }
        publishDrainIfReady();
        ensureReplenishmentOwner();
        return closedReservation;
    }

    private StartupFailureOutcome finishFailedStart(Worker<S> reservation) {
        StartupFailureOutcome outcome;
        synchronized (lock) {
            outcome = switch (reservation.startupTerminalDecision()) {
                case CLOSED -> StartupFailureOutcome.CLOSED;
                case FACTORY_COMPLETED -> StartupFailureOutcome.FAILED;
                case TIMED_OUT, UNDECIDED ->
                    throw new IllegalStateException("worker factory failure has no completion decision");
            };
            if (removeSlot(reservation)) {
                failedStartups++;
                reservation.releaseRetirementAdmission();
                verifyPartition();
                lock.notifyAll();
            }
        }
        publishDrainIfReady();
        ensureReplenishmentOwner();
        return outcome;
    }

    private void failRunningReservation(Worker<S> reservation) {
        synchronized (lock) {
            if (removeSlot(reservation)) {
                reservation.releaseRetirementAdmission();
                verifyPartition();
                lock.notifyAll();
            }
        }
        publishDrainIfReady();
        ensureReplenishmentOwner();
    }

    private void finishAbandonedStart(
            Worker<S> reservation, S session, long startupNanos, PooledWorkerRetireReason reason, Throwable failure) {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        try {
            synchronized (lock) {
                if (!slots.contains(reservation)) {
                    return;
                }
                failedStartups++;
                if (session == null) {
                    removeSlot(reservation);
                    reservation.releaseRetirementAdmission();
                } else {
                    reservation.accept(session, workerCloser);
                    created++;
                    totalWorkerStartupNanos += startupNanos;
                    markRetiring(reservation, reason, retirementDispatch);
                }
                verifyPartition();
                lock.notifyAll();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        publishDrainIfReady();
        ensureReplenishmentOwner();
    }

    private void retireLeased(Worker<S> worker, PooledWorkerRetireReason reason) {
        retireLeased(worker, reason, false);
    }

    private void retireLeased(Worker<S> worker, PooledWorkerRetireReason reason, boolean closedTakesPrecedence) {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        try {
            synchronized (lock) {
                requireState(worker, SlotState.LEASED);
                PooledWorkerRetireReason effectiveReason =
                        closedTakesPrecedence && closing ? PooledWorkerRetireReason.CLOSED : reason;
                markRetiring(worker, effectiveReason, retirementDispatch);
                verifyPartition();
                lock.notifyAll();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        ensureReplenishmentOwner();
        publishDrainIfReady();
    }

    private void retireAfterFailedHandoff(
            Worker<S> worker,
            PooledWorkerRetireReason reason,
            boolean closedTakesPrecedence,
            Throwable primaryFailure) {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        try {
            synchronized (lock) {
                if (!slots.contains(worker)) {
                    return;
                }
                PooledWorkerRetireReason effectiveReason =
                        closedTakesPrecedence && closing ? PooledWorkerRetireReason.CLOSED : reason;
                switch (worker.state()) {
                    case LEASED -> markRetiring(worker, effectiveReason, retirementDispatch);
                    case IDLE -> {
                        if (!idle.remove(worker)) {
                            throw new IllegalStateException("owned idle worker is absent from the idle queue");
                        }
                        markRetiring(worker, effectiveReason, retirementDispatch);
                    }
                    case RETIRING -> retirementDispatch.observeQueueLocked();
                    case STARTING -> throw new IllegalStateException("handoff still owns a starting worker");
                }
                verifyPartition();
                lock.notifyAll();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        } finally {
            try {
                retirementDispatch.ensureStarted();
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
            Worker<S> worker, PooledWorkerRetireReason reason, RetirementDispatchGuard retirementDispatch) {
        transition(worker, SlotState.RETIRING);
        worker.retireReason(Objects.requireNonNull(reason, "reason"));
        retirementQueue.addLast(worker);
        retirementDispatch.markQueued();
    }

    private void ensureRetirementOwner() {
        boolean start;
        synchronized (lock) {
            start = retirementScheduling.tryActivate(!retirementQueue.isEmpty());
        }
        if (!start) {
            return;
        }
        startRetirementOwner();
    }

    private void startRetirementOwner() {
        Throwable schedulingFailure;
        try {
            dispatchRetirementOwner(retirementDispatcher);
            return;
        } catch (RuntimeException | Error failure) {
            schedulingFailure = failure;
        }
        recordRetirementDispatchFailure(schedulingFailure);
        try {
            dispatchRetirementOwner(PoolRetirementDispatcher::execute);
            return;
        } catch (RuntimeException | Error terminalFailure) {
            SuppressionSupport.attach(schedulingFailure, terminalFailure);
            recordRetirementDispatchFailure(terminalFailure);
        }

        RetirementOwnerAttempt inlineRecovery = new RetirementOwnerAttempt();
        inlineRecovery.run();
        enqueueRetirementOwnerCompletion(inlineRecovery.failure());
    }

    private void dispatchRetirementOwner(TerminalRetirementDispatcher dispatcher) {
        RetirementOwnerAttempt attempt = new RetirementOwnerAttempt();
        PoolRetirementDispatcher.Ownership ownership = Objects.requireNonNull(
                dispatcher.dispatch(poolAdmission, attempt), "retirement dispatcher returned null");
        ownership.completion().whenComplete((ignored, dispatchFailure) -> {
            Throwable completionFailure;
            try {
                WorkerCloseFailureAccumulator failures = new WorkerCloseFailureAccumulator();
                failures.add(attempt.failure());
                failures.add(dispatchFailure == null ? null : unwrapCompletionFailure(dispatchFailure));
                completionFailure = failures.failure();
            } catch (RuntimeException | Error callbackFailure) {
                completionFailure = callbackFailure;
            }
            try {
                enqueueRetirementOwnerCompletion(completionFailure);
            } catch (RuntimeException | Error callbackFailure) {
                failSafeRetirementCompletion(callbackFailure);
            }
        });
    }

    private void recordRetirementDispatchFailure(Throwable schedulingFailure) {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        DrainPublication publication = null;
        try {
            synchronized (lock) {
                if (!retirementScheduling.ownerActive()) {
                    return;
                }
                enterClosingLocked(schedulingFailure, retirementDispatch);
                publication = drainPublication();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(schedulingFailure, cleanupFailure);
            synchronized (lock) {
                closing = true;
                recordDrainFailure(schedulingFailure);
                lock.notifyAll();
            }
        } finally {
            try {
                retirementDispatch.ensureStarted();
            } catch (RuntimeException | Error cleanupFailure) {
                SuppressionSupport.attach(schedulingFailure, cleanupFailure);
            }
        }
        publish(publication);
    }

    private void runRetirementOwner() {
        List<FailureReport> deferredReports = new ArrayList<>();
        boolean finishing = false;
        try {
            while (true) {
                List<Worker<S>> batch = null;
                synchronized (lock) {
                    if (retirementQueue.isEmpty()) {
                        retirementScheduling.markFinishing();
                        finishing = true;
                    } else {
                        batch = new ArrayList<>(retirementQueue);
                        retirementQueue.clear();
                    }
                }
                if (finishing) {
                    return;
                }
                for (Worker<S> worker : batch) {
                    worker.initiateClose();
                }
                for (Worker<S> worker : batch) {
                    try {
                        observeRetirement(worker, deferredReports);
                    } catch (RuntimeException | Error completionFailure) {
                        completeUnexpectedRetirementFailure(worker, completionFailure);
                    }
                }
            }
        } finally {
            if (!finishing) {
                synchronized (lock) {
                    retirementScheduling.markFinishing();
                    lock.notifyAll();
                }
            }
            deferredReports.forEach(this::reportLate);
        }
    }

    private void enqueueRetirementOwnerCompletion(Throwable failure) {
        synchronized (retirementCompletionLock) {
            retirementOwnerCompletions.addLast(new RetirementOwnerCompletion(failure));
            if (retirementCompletionProcessorActive) {
                return;
            }
            retirementCompletionProcessorActive = true;
        }
        while (true) {
            RetirementOwnerCompletion completion;
            synchronized (retirementCompletionLock) {
                completion = retirementOwnerCompletions.pollFirst();
                if (completion == null) {
                    retirementCompletionProcessorActive = false;
                    return;
                }
            }
            try {
                completeRetirementOwner(completion.failure());
            } catch (RuntimeException | Error callbackFailure) {
                try {
                    recoverRetirementCompletion(callbackFailure);
                } catch (RuntimeException | Error recoveryFailure) {
                    SuppressionSupport.attach(callbackFailure, recoveryFailure);
                    failSafeRetirementCompletion(callbackFailure);
                }
            }
        }
    }

    private void completeRetirementOwner(Throwable ownerFailure) {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        boolean startNextOwner;
        DrainPublication publication;
        try {
            synchronized (lock) {
                if (ownerFailure != null) {
                    enterClosingLocked(ownerFailure, retirementDispatch);
                }
                startNextOwner = retirementScheduling.complete(!retirementQueue.isEmpty());
                publication = drainPublication();
                lock.notifyAll();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        if (startNextOwner) {
            startRetirementOwner();
        }
        publish(publication);
    }

    private void failSafeRetirementCompletion(Throwable callbackFailure) {
        boolean startNextOwner = false;
        synchronized (lock) {
            closing = true;
            recordDrainFailure(callbackFailure);
            if (retirementScheduling.finishing()) {
                startNextOwner = retirementScheduling.complete(!retirementQueue.isEmpty());
            }
            lock.notifyAll();
        }
        if (startNextOwner) {
            startRetirementOwner();
        }
        publishDrainIfReady();
        Threading.reportUncaught(Thread.currentThread(), callbackFailure);
    }

    private void recoverRetirementCompletion(Throwable callbackFailure) {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        boolean startNextOwner = false;
        DrainPublication publication = null;
        try {
            synchronized (lock) {
                enterClosingLocked(callbackFailure, retirementDispatch);
                if (retirementScheduling.finishing()) {
                    startNextOwner = retirementScheduling.complete(!retirementQueue.isEmpty());
                }
                publication = drainPublication();
                lock.notifyAll();
            }
        } catch (RuntimeException | Error recoveryFailure) {
            SuppressionSupport.attach(callbackFailure, recoveryFailure);
            synchronized (lock) {
                closing = true;
                recordDrainFailure(callbackFailure);
                if (retirementScheduling.finishing()) {
                    startNextOwner = retirementScheduling.complete(!retirementQueue.isEmpty());
                }
                lock.notifyAll();
            }
        } finally {
            try {
                retirementDispatch.ensureStarted();
            } catch (RuntimeException | Error recoveryFailure) {
                SuppressionSupport.attach(callbackFailure, recoveryFailure);
            }
        }
        if (startNextOwner) {
            startRetirementOwner();
        }
        publish(publication);
    }

    private void observeRetirement(Worker<S> worker, List<FailureReport> immediateReports) {
        CompletableFuture<CloseOutcome> closeOutcome = worker.closeOutcome();
        if (closeOutcome.isDone()) {
            CloseOutcome observed = closeOutcome.join();
            RetirementOutcome outcome = processRetirement(worker, observed, true);
            if (outcome.lateReport() != null) {
                immediateReports.add(outcome.lateReport());
            }
            return;
        }
        closeOutcome.whenComplete((outcome, impossibleFailure) -> {
            if (impossibleFailure != null) {
                completeUnexpectedRetirementFailure(worker, unwrapCompletionFailure(impossibleFailure));
                return;
            }
            try {
                RetirementOutcome retirement = processRetirement(worker, outcome, true);
                reportLate(retirement.lateReport());
            } catch (RuntimeException | Error completionFailure) {
                completeUnexpectedRetirementFailure(worker, completionFailure);
            }
        });
    }

    private void completeUnexpectedRetirementFailure(Worker<S> worker, Throwable failure) {
        FailureReport failureReport = failureReport(Thread.currentThread(), failure);
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        DrainPublication publication;
        try {
            synchronized (lock) {
                enterClosingLocked(failure, retirementDispatch);
                if (slots.contains(worker)
                        && worker.state() == SlotState.RETIRING
                        && !worker.closeAttemptFinished()
                        && !retirementQueue.contains(worker)) {
                    retirementQueue.addLast(worker);
                    retirementDispatch.markQueued();
                }
                publication = drainPublication();
                lock.notifyAll();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        publish(publication);
        reportLate(failureReport);
    }

    private RetirementOutcome processRetirement(Worker<S> worker, CloseOutcome outcome, boolean reportLateFailure) {
        Throwable closeFailure = outcome.failure();
        FailureReport lateReport = null;
        DrainPublication publication;
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        try {
            synchronized (lock) {
                requireState(worker, SlotState.RETIRING);
                worker.markCloseAttemptFinished();
                if (!removeSlot(worker)) {
                    throw new IllegalStateException("retiring worker is absent from the slot registry");
                }
                retired++;
                retireReasons.merge(worker.retireReason(), 1L, Long::sum);
                worker.releaseRetirementAdmission();
                if (closeFailure != null) {
                    failedWorkerCloses++;
                    enterClosingLocked(closeFailure, retirementDispatch);
                    if (construction.constructing() && worker.claimFailureReport()) {
                        construction.record(failureReport(Thread.currentThread(), closeFailure));
                    } else if (reportLateFailure && construction.failed() && worker.claimFailureReport()) {
                        lateReport = failureReport(Thread.currentThread(), closeFailure);
                    }
                }
                verifyPartition();
                publication = drainPublication();
                lock.notifyAll();
            }
        } finally {
            retirementDispatch.ensureStarted();
        }
        testHooks.afterRetirementOutcome().accept(closeFailure);
        publish(publication);
        ensureReplenishmentOwner();
        return new RetirementOutcome(closeFailure, lateReport);
    }

    private void ensureReplenishmentOwner() {
        if (!backgroundReplenishment || minIdle == 0) {
            return;
        }
        boolean start;
        synchronized (lock) {
            start = !closing && !replenishmentOwnerActive && needsReplenishment();
            if (start) {
                replenishmentOwnerActive = true;
            }
        }
        if (start) {
            startReplenishmentOwner(Duration.ZERO);
        }
    }

    private void startReplenishmentOwner(Duration backoff) {
        Runnable owner = () -> runReplenishmentOwner(backoff);
        try {
            replenishmentStarter.accept(owner);
        } catch (RuntimeException schedulingFailure) {
            scheduleReplenishmentRetry(nextBackoff(backoff), schedulingFailure);
        } catch (Error schedulingError) {
            RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
            DrainPublication publication;
            boolean hasRetirements;
            try {
                synchronized (lock) {
                    replenishmentOwnerActive = false;
                    enterClosingLocked(schedulingError, retirementDispatch);
                    hasRetirements = !retirementQueue.isEmpty();
                    publication = drainPublication();
                }
            } finally {
                retirementDispatch.ensureStarted();
            }
            if (hasRetirements && !construction.constructing()) {
                ensureRetirementOwner();
            }
            publish(publication);
            throw schedulingError;
        }
    }

    private void scheduleReplenishmentRetry(Duration backoff, RuntimeException schedulingFailure) {
        synchronized (lock) {
            if (closing) {
                replenishmentOwnerActive = false;
                lock.notifyAll();
                return;
            }
        }
        try {
            Threading.start(threadPrefix + "replenish-retry-", () -> runReplenishmentOwner(backoff));
        } catch (RuntimeException | Error retryFailure) {
            SuppressionSupport.attach(schedulingFailure, retryFailure);
            RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
            DrainPublication publication;
            boolean hasRetirements;
            try {
                synchronized (lock) {
                    replenishmentOwnerActive = false;
                    enterClosingLocked(schedulingFailure, retirementDispatch);
                    hasRetirements = !retirementQueue.isEmpty();
                    publication = drainPublication();
                }
            } finally {
                retirementDispatch.ensureStarted();
            }
            if (hasRetirements && !construction.constructing()) {
                ensureRetirementOwner();
            }
            publish(publication);
            if (retryFailure instanceof Error error) {
                throw error;
            }
            throw schedulingFailure;
        }
    }

    private void runReplenishmentOwner(Duration initialBackoff) {
        Duration backoff = initialBackoff;
        while (true) {
            if (!backoffWaiter.await(backoff)) {
                finishReplenishmentOwner();
                return;
            }
            StartupReservationOwner startupReservation = new StartupReservationOwner();
            LeaseHandoff handoff = new LeaseHandoff();
            try {
                boolean stop;
                synchronized (lock) {
                    stop = closing || !needsReplenishment();
                    if (stop) {
                        replenishmentOwnerActive = false;
                        lock.notifyAll();
                    } else {
                        reserveSlotLocked(startupReservation);
                    }
                }
                if (stop) {
                    break;
                }
                openReservedWorker(
                        startupReservation,
                        DurationSupport.deadlineFromNow(acquireTimeout),
                        StartupPurpose.REPLENISHMENT,
                        handoff);
                handoff.transferToPoolLifecycle();
                backoff = Duration.ZERO;
            } catch (RuntimeException failure) {
                startupReservation.fail(failure);
                handoff.fail(failure);
                backoff = nextBackoff(backoff);
            } catch (Error error) {
                startupReservation.fail(error);
                handoff.fail(error);
                failReplenishmentOwner(error);
                throw error;
            }
        }
        publishDrainIfReady();
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

    private void finishReplenishmentOwner() {
        synchronized (lock) {
            replenishmentOwnerActive = false;
            lock.notifyAll();
        }
        publishDrainIfReady();
    }

    private void failReplenishmentOwner(Error failure) {
        RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
        DrainPublication publication = null;
        try {
            synchronized (lock) {
                replenishmentOwnerActive = false;
                enterClosingLocked(failure, retirementDispatch);
                publication = drainPublication();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(failure, cleanupFailure);
        } finally {
            try {
                retirementDispatch.ensureStarted();
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
        if (slots.size() >= maxSize) {
            return false;
        }
        int readyOrReplenishing = idle.size();
        for (Worker<S> worker : slots) {
            if (worker.state() == SlotState.STARTING && worker.startupPurpose() == StartupPurpose.REPLENISHMENT) {
                readyOrReplenishing++;
            }
        }
        return readyOrReplenishing < minIdle;
    }

    private PooledWorkerRetireReason retireReasonForPolicy(Worker<S> worker) {
        if (worker.requests() >= maxRequestsPerWorker) {
            return PooledWorkerRetireReason.MAX_REQUESTS;
        }
        Duration maxAge = maxWorkerAge;
        if (!maxAge.isZero() && System.nanoTime() - worker.createdAtNanos() >= DurationSupport.saturatedNanos(maxAge)) {
            return PooledWorkerRetireReason.AGE;
        }
        return null;
    }

    private void recordAcquireWait(long startedAtNanos) {
        long elapsedNanos = Math.max(0, metricsClock.nanoTime() - startedAtNanos);
        synchronized (lock) {
            totalAcquireWaitNanos += elapsedNanos;
            lock.notifyAll();
        }
    }

    private void reportOrRecordLateError(BoundedFailureReporter.FailureTarget failureTarget, Throwable failure) {
        FailureReport report;
        synchronized (lock) {
            report = construction.route(new FailureReport(failureTarget, failure));
        }
        reportLate(report);
    }

    private void reportAll(List<FailureReport> reports) {
        reports.forEach(this::reportLate);
    }

    private void reportLate(FailureReport report) {
        if (report == null) {
            return;
        }
        try {
            PoolRetirementDispatcher.Ownership ownership = PoolRetirementDispatcher.report(() -> report(report));
            ownership.started().whenComplete((ignored, launchFailure) -> {
                if (launchFailure != null) {
                    SuppressionSupport.attach(report.failure(), unwrapCompletionFailure(launchFailure));
                    BoundedFailureReporter.shared().report(report.failureTarget(), report.failure());
                }
            });
        } catch (RuntimeException | Error dispatchFailure) {
            SuppressionSupport.attach(report.failure(), dispatchFailure);
            BoundedFailureReporter.shared().report(report.failureTarget(), report.failure());
        }
    }

    private void report(FailureReport report) {
        if (report == null) {
            return;
        }
        try {
            BoundedFailureReporter.withFailureTarget(
                    report.failureTarget(),
                    () -> lateFailureReporter.report(
                            BoundedFailureReporter.notificationSourceThread(), report.failure()));
        } catch (RuntimeException | Error reportingFailure) {
            BoundedFailureReporter.shared().report(report.failureTarget(), reportingFailure);
        }
    }

    private static void reportBounded(Thread ignored, Throwable failure) {
        BoundedFailureReporter.shared().report(BoundedFailureReporter.captureFailureTarget(), failure);
    }

    private static FailureReport failureReport(Thread sourceThread, Throwable failure) {
        return new FailureReport(BoundedFailureReporter.captureFailureTarget(sourceThread), failure);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException completionException
                        && completionException.getCause() != null
                ? completionException.getCause()
                : failure;
    }

    private void completeRequest(boolean successful, long durationNanos) {
        synchronized (lock) {
            if (successful) {
                completedRequests++;
            } else {
                failedRequests++;
            }
            totalRequestDurationNanos += Math.max(0, durationNanos);
            lock.notifyAll();
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
        if (!closing || drained.isDone() || drainPublicationClaimed) {
            return null;
        }
        if (retirementScheduling.ownerActive() || !retirementQueue.isEmpty()) {
            return null;
        }
        for (Worker<S> worker : slots) {
            if (worker.state() != SlotState.RETIRING || !worker.closeAttemptFinished()) {
                return null;
            }
        }
        if (!slots.isEmpty() && drainFailures.failure() == null) {
            throw new IllegalStateException("unresolved retiring workers require a drain failure");
        }
        drainPublicationClaimed = true;
        return new DrainPublication(drainFailures.failure());
    }

    private void publish(DrainPublication publication) {
        if (publication != null) {
            completionOwner.publish(publication);
        }
        publishSlotReleaseIfResolved();
    }

    private void publishSlotReleaseIfResolved() {
        boolean resolved;
        synchronized (lock) {
            resolved = closing && slots.isEmpty();
        }
        if (resolved) {
            slotsReleased.complete(null);
        }
    }

    private void recordDrainFailure(Throwable failure) {
        drainFailures.add(failure);
    }

    private void enterClosingLocked(Throwable failure, RetirementDispatchGuard retirementDispatch) {
        retirementDispatch.observeQueueLocked();
        for (Worker<S> worker : slots) {
            if (worker.state() == SlotState.STARTING) {
                worker.decideStartupTerminal(StartupTerminalDecision.CLOSED);
            }
        }
        closing = true;
        if (failure != null) {
            recordDrainFailure(failure);
        }
        while (!idle.isEmpty()) {
            markRetiring(idle.removeFirst(), PooledWorkerRetireReason.CLOSED, retirementDispatch);
        }
        Iterator<Worker<S>> slotsIterator = slots.iterator();
        while (slotsIterator.hasNext()) {
            Worker<S> worker = slotsIterator.next();
            if (worker.state() == SlotState.STARTING && worker.startupStage() == StartupStage.QUEUED) {
                worker.startupStage(StartupStage.CANCELLED);
                slotsIterator.remove();
                worker.releaseRetirementAdmission();
            }
        }
        verifyPartition();
        lock.notifyAll();
    }

    private void transition(Worker<S> worker, SlotState target) {
        if (!slots.contains(worker)) {
            throw new IllegalStateException("worker does not belong to this pool");
        }
        worker.state(target);
    }

    private void transitionToLeased(Worker<S> worker, LeaseHandoff handoff) {
        if (!handoff.tryOwn(worker)) {
            throw new IllegalStateException("lease handoff cannot accept another worker");
        }
        try {
            transition(worker, SlotState.LEASED);
            lock.notifyAll();
        } catch (RuntimeException | Error failure) {
            handoff.rollback(worker);
            throw failure;
        }
    }

    private void requireState(Worker<S> worker, SlotState expected) {
        if (!slots.contains(worker) || worker.state() != expected) {
            throw new IllegalStateException("worker state must be " + expected);
        }
    }

    private StateCounts stateCounts() {
        return scannedStateCounts();
    }

    private void verifyPartition() {
        if (testHooks == TestHooks.NONE) {
            return;
        }
        verifyPartitionLocked();
    }

    void verifyPartitionForTest() {
        synchronized (lock) {
            verifyPartitionLocked();
        }
    }

    private void verifyPartitionLocked() {
        if (slots.size() > maxSize) {
            throw new IllegalStateException("pool slot registry exceeds configured capacity");
        }
        StateCounts counts = scannedStateCounts();
        if ((long) counts.idle() + counts.leased() + counts.starting() + counts.retiring() != counts.size()) {
            throw new IllegalStateException("pool slot partition is inconsistent");
        }
        if (idle.size() != counts.idle()) {
            throw new IllegalStateException("idle queue does not match slot registry");
        }
        Set<Worker<S>> queued = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Worker<S> worker : idle) {
            if (!slots.contains(worker) || worker.state() != SlotState.IDLE || !queued.add(worker)) {
                throw new IllegalStateException("idle queue contains an invalid worker");
            }
        }
        queued.clear();
        for (Worker<S> worker : retirementQueue) {
            if (!slots.contains(worker) || worker.state() != SlotState.RETIRING || !queued.add(worker)) {
                throw new IllegalStateException("retirement queue contains an invalid worker");
            }
        }
    }

    private StateCounts scannedStateCounts() {
        int scannedIdle = 0;
        int scannedLeased = 0;
        int scannedStarting = 0;
        int scannedRetiring = 0;
        for (Worker<S> worker : slots) {
            switch (worker.state()) {
                case IDLE -> scannedIdle++;
                case LEASED -> scannedLeased++;
                case STARTING -> scannedStarting++;
                case RETIRING -> scannedRetiring++;
            }
        }
        return new StateCounts(slots.size(), scannedIdle, scannedLeased, scannedStarting, scannedRetiring);
    }

    private boolean removeSlot(Worker<S> worker) {
        return slots.remove(worker);
    }

    private static Duration nextBackoff(Duration current) {
        if (current.isZero()) {
            return MIN_REPLENISH_BACKOFF;
        }
        long doubled = Math.min(
                DurationSupport.saturatedNanos(current) * 2, DurationSupport.saturatedNanos(MAX_REPLENISH_BACKOFF));
        return Duration.ofNanos(doubled);
    }

    private static TerminalRetirementDispatcher sharedRetirementDispatcher() {
        return PoolRetirementDispatcher::execute;
    }

    private static TerminalRetirementDispatcher adaptRetirementStarter(Consumer<Runnable> starter) {
        Objects.requireNonNull(starter, "starter");
        return (admission, task) -> {
            Objects.requireNonNull(admission, "admission").claimDispatch();
            CompletableFuture<Thread> started = new CompletableFuture<>();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Runnable observed = () -> {
                started.complete(Thread.currentThread());
                Throwable failure = null;
                try {
                    task.run();
                } catch (Throwable taskFailure) {
                    failure = taskFailure;
                } finally {
                    admission.releaseDispatch();
                }
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                }
            };
            try {
                starter.accept(observed);
            } catch (RuntimeException | Error failure) {
                admission.releaseDispatch();
                started.completeExceptionally(failure);
                completion.completeExceptionally(failure);
                throw failure;
            }
            return new PoolRetirementDispatcher.Ownership(started, completion);
        };
    }

    interface PoolOptions {

        int maxSize();

        int warmupSize();

        int minIdle();

        Duration acquireTimeout();

        default Duration closeTimeout() {
            return acquireTimeout();
        }

        int maxRequestsPerWorker();

        Duration maxWorkerAge();

        boolean backgroundReplenishment();
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

    record TestHooks(
            Runnable beforeStartupHandoff,
            Consumer<Throwable> beforeConstructionFailure,
            Consumer<Throwable> afterRetirementOutcome,
            Runnable beforeConstructionCommit,
            Runnable afterFactoryTerminalSignal,
            Runnable afterStartupTimeoutSignal,
            FaultInjector faultInjector) {

        private static final TestHooks NONE = new TestHooks(
                () -> {}, failure -> {}, failure -> {}, () -> {}, () -> {}, () -> {}, new FaultInjector());

        TestHooks(
                Runnable beforeStartupHandoff,
                Consumer<Throwable> beforeConstructionFailure,
                Consumer<Throwable> afterRetirementOutcome,
                Runnable beforeConstructionCommit) {
            this(
                    beforeStartupHandoff,
                    beforeConstructionFailure,
                    afterRetirementOutcome,
                    beforeConstructionCommit,
                    () -> {},
                    () -> {},
                    new FaultInjector());
        }

        TestHooks {
            Objects.requireNonNull(beforeStartupHandoff, "beforeStartupHandoff");
            Objects.requireNonNull(beforeConstructionFailure, "beforeConstructionFailure");
            Objects.requireNonNull(afterRetirementOutcome, "afterRetirementOutcome");
            Objects.requireNonNull(beforeConstructionCommit, "beforeConstructionCommit");
            Objects.requireNonNull(afterFactoryTerminalSignal, "afterFactoryTerminalSignal");
            Objects.requireNonNull(afterStartupTimeoutSignal, "afterStartupTimeoutSignal");
            Objects.requireNonNull(faultInjector, "faultInjector");
        }
    }

    @FunctionalInterface
    interface TerminalRetirementDispatcher {

        PoolRetirementDispatcher.Ownership dispatch(PoolRetirementDispatcher.Admission admission, Runnable task);
    }

    @FunctionalInterface
    interface WorkerCloseAction<S> {

        CloseObservation initiate(S session, PoolRetirementDispatcher.Admission admission);
    }

    @FunctionalInterface
    interface RetirementAdmissionProvider {

        PoolRetirementDispatcher.Admission acquire(long deadlineNanos) throws TimeoutException, InterruptedException;
    }

    @FunctionalInterface
    interface CloseObservation {

        CompletableFuture<CloseOutcome> outcome();
    }

    record CloseOutcome(Throwable failure) {

        static CloseOutcome success() {
            return new CloseOutcome(null);
        }

        static CloseOutcome failure(Throwable failure) {
            return new CloseOutcome(Objects.requireNonNull(failure, "failure"));
        }
    }

    record MetricsSnapshot(
            int size,
            int idle,
            int leased,
            int starting,
            int retiring,
            long created,
            long retired,
            long completedRequests,
            long failedRequests,
            long failedStartups,
            long failedWorkerCloses,
            long totalAcquireWaitNanos,
            long totalRequestDurationNanos,
            long totalWorkerStartupNanos,
            Map<PooledWorkerRetireReason, Long> retireReasons) {}

    private final class LeaseHandoff {

        private Worker<S> worker;

        private boolean tryOwn(Worker<S> leasedWorker) {
            if (worker != null || leasedWorker == null) {
                return false;
            }
            worker = leasedWorker;
            return true;
        }

        private void rollback(Worker<S> leasedWorker) {
            if (worker == leasedWorker) {
                worker = null;
            }
        }

        private Worker<S> complete() {
            Worker<S> completedWorker = Objects.requireNonNull(worker, "lease handoff has no worker");
            worker = null;
            return completedWorker;
        }

        private void retire(PooledWorkerRetireReason reason) {
            Worker<S> retiredWorker = complete();
            retireLeased(retiredWorker, reason);
        }

        private void transferToPoolLifecycle() {
            Worker<S> transferredWorker = Objects.requireNonNull(worker, "lease handoff has no worker");
            RetirementDispatchGuard retirementDispatch = new RetirementDispatchGuard();
            try {
                synchronized (lock) {
                    requireState(transferredWorker, SlotState.LEASED);
                    PooledWorkerRetireReason reason =
                            closing ? PooledWorkerRetireReason.CLOSED : retireReasonForPolicy(transferredWorker);
                    if (reason != null) {
                        markRetiring(transferredWorker, reason, retirementDispatch);
                    } else {
                        transition(transferredWorker, SlotState.IDLE);
                        idle.addLast(transferredWorker);
                    }
                    verifyPartition();
                    worker = null;
                    lock.notifyAll();
                }
            } finally {
                retirementDispatch.ensureStarted();
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
            Worker<S> failedWorker = complete();
            retireAfterFailedHandoff(failedWorker, reason, closedTakesPrecedence, primaryFailure);
        }
    }

    private final class RetirementDispatchGuard {

        private boolean queued;
        private boolean ensured;

        private void markQueued() {
            queued = true;
        }

        private void observeQueueLocked() {
            if (!retirementQueue.isEmpty()) {
                queued = true;
            }
        }

        private void ensureStarted() {
            if (!queued || ensured) {
                return;
            }
            ensured = true;
            synchronized (lock) {
                // Only the constructor thread can defer retirement to construction cleanup.
                if (construction.constructing() && Thread.currentThread() == constructionThread) {
                    return;
                }
            }
            ensureRetirementOwner();
        }
    }

    private final class StartupReservationOwner {

        private Worker<S> worker;
        private boolean owned;

        private Worker<S> registerLocked() {
            if (owned) {
                throw new IllegalStateException("startup reservation already owns a slot");
            }
            Worker<S> reservedWorker = new Worker<>();
            worker = reservedWorker;
            owned = true;
            slots.add(reservedWorker);
            return reservedWorker;
        }

        private Worker<S> worker() {
            if (!owned || worker == null) {
                throw new IllegalStateException("startup reservation has no slot");
            }
            return worker;
        }

        private void admit(PoolRetirementDispatcher.Admission admission) {
            worker().retirementAdmission(admission);
        }

        private void transferToAttempt() {
            owned = false;
            worker = null;
        }

        private void disownRemovedSlot() {
            owned = false;
            worker = null;
        }

        private void fail(Throwable primaryFailure) {
            Objects.requireNonNull(primaryFailure, "primaryFailure");
            if (!owned) {
                return;
            }
            try {
                synchronized (lock) {
                    Worker<S> failedReservation = worker;
                    if (failedReservation != null) {
                        removeSlot(failedReservation);
                        failedReservation.releaseRetirementAdmission();
                    }
                    owned = false;
                    worker = null;
                    verifyPartition();
                    lock.notifyAll();
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
    }

    final class RequestObservation {

        private long accumulatedNanos;
        private long segmentStartedNanos = metricsClock.nanoTime();
        private boolean paused;
        private boolean completed;

        void pauseForAcquire() {
            if (completed || paused) {
                throw new IllegalStateException("request observation cannot be paused");
            }
            accumulatedNanos += Math.max(0, metricsClock.nanoTime() - segmentStartedNanos);
            paused = true;
        }

        void resumeAfterAcquire() {
            if (completed || !paused) {
                throw new IllegalStateException("request observation cannot be resumed");
            }
            paused = false;
            segmentStartedNanos = metricsClock.nanoTime();
        }

        void succeed() {
            finish(true);
        }

        void fail() {
            finish(false);
        }

        private void finish(boolean successful) {
            if (completed) {
                return;
            }
            completed = true;
            if (!paused) {
                accumulatedNanos += Math.max(0, metricsClock.nanoTime() - segmentStartedNanos);
            }
            completeRequest(successful, accumulatedNanos);
        }
    }

    static final class Worker<S> {

        private final AtomicReference<StartupTerminalDecision> startupTerminalDecision =
                new AtomicReference<>(StartupTerminalDecision.UNDECIDED);
        private SlotState state = SlotState.STARTING;
        private StartupStage startupStage = StartupStage.QUEUED;
        private StartupPurpose startupPurpose = StartupPurpose.DEMAND;
        private S session;
        private CloseOnce<S> closeOnce;
        private PoolRetirementDispatcher.Admission retirementAdmission;
        private long createdAtNanos;
        private int requests;
        private PooledWorkerRetireReason retireReason;
        private boolean closeAttemptFinished;
        private boolean failureReported;

        S session() {
            return Objects.requireNonNull(session, "worker has not completed startup");
        }

        long createdAtNanos() {
            return createdAtNanos;
        }

        int requests() {
            return requests;
        }

        void recordRequest() {
            requests++;
        }

        private void accept(S acceptedSession, WorkerCloseAction<S> closeAction) {
            if (session != null) {
                throw new IllegalStateException("worker session is already accepted");
            }
            session = Objects.requireNonNull(acceptedSession, "workerFactory returned null");
            closeOnce = new CloseOnce<>(
                    session,
                    Objects.requireNonNull(retirementAdmission, "worker has no retirement admission"),
                    closeAction);
            createdAtNanos = System.nanoTime();
        }

        private void retirementAdmission(PoolRetirementDispatcher.Admission admission) {
            if (retirementAdmission != null) {
                throw new IllegalStateException("worker retirement admission is already owned");
            }
            retirementAdmission = Objects.requireNonNull(admission, "admission");
        }

        private void releaseRetirementAdmission() {
            PoolRetirementDispatcher.Admission owned = retirementAdmission;
            retirementAdmission = null;
            if (owned != null) {
                owned.close();
            }
        }

        private void initiateClose() {
            Objects.requireNonNull(closeOnce, "worker has no close owner").initiate();
        }

        private CompletableFuture<CloseOutcome> closeOutcome() {
            return Objects.requireNonNull(closeOnce, "worker has no close owner")
                    .outcome();
        }

        private SlotState state() {
            return state;
        }

        private void state(SlotState state) {
            this.state = Objects.requireNonNull(state, "state");
        }

        private StartupPurpose startupPurpose() {
            return startupPurpose;
        }

        private void startupPurpose(StartupPurpose startupPurpose) {
            this.startupPurpose = Objects.requireNonNull(startupPurpose, "startupPurpose");
        }

        private StartupStage startupStage() {
            return startupStage;
        }

        private void startupStage(StartupStage startupStage) {
            this.startupStage = Objects.requireNonNull(startupStage, "startupStage");
        }

        private StartupTerminalDecision decideStartupTerminal(StartupTerminalDecision candidate) {
            startupTerminalDecision.compareAndSet(
                    StartupTerminalDecision.UNDECIDED, Objects.requireNonNull(candidate, "candidate"));
            return startupTerminalDecision.get();
        }

        private StartupTerminalDecision startupTerminalDecision() {
            return startupTerminalDecision.get();
        }

        private PooledWorkerRetireReason retireReason() {
            return retireReason;
        }

        private void retireReason(PooledWorkerRetireReason retireReason) {
            this.retireReason = retireReason;
        }

        private boolean closeAttemptFinished() {
            return closeAttemptFinished;
        }

        private void markCloseAttemptFinished() {
            closeAttemptFinished = true;
        }

        private boolean claimFailureReport() {
            if (failureReported) {
                return false;
            }
            failureReported = true;
            return true;
        }
    }

    private final class StartAttemptOwner {

        private final Worker<S> reservation;
        private final BoundedTaskRunner.Permit permit;
        private final long startedAtNanos = System.nanoTime();
        private final CompletableFuture<CreatedWorker<S>> completion = new CompletableFuture<>();
        private AttemptState state = AttemptState.WAITING;
        private boolean taskFinished;
        private CreatedWorker<S> completedWorker;
        private Throwable completedFailure;
        private BoundedFailureReporter.FailureTarget completedFailureTarget;
        private Thread thread;
        private boolean errorReported;

        private StartAttemptOwner(Worker<S> reservation, BoundedTaskRunner.Permit permit) {
            this.reservation = reservation;
            this.permit = permit;
        }

        private void start() {
            try {
                thread = Threading.unstarted(threadPrefix + "start-", this::run);
                thread.start();
            } catch (RuntimeException | Error failure) {
                permit.close();
                throw failure;
            }
        }

        private void run() {
            S session = null;
            Throwable failure = null;
            BoundedFailureReporter.FailureTarget failureTarget = null;
            try {
                session = Objects.requireNonNull(workerFactory.get(), "workerFactory returned null");
            } catch (Throwable startupFailure) {
                failure = startupFailure;
                failureTarget = BoundedFailureReporter.captureFailureTarget();
            } finally {
                permit.close();
            }

            CreatedWorker<S> createdWorker =
                    session == null ? null : new CreatedWorker<>(session, System.nanoTime() - startedAtNanos);
            reservation.decideStartupTerminal(StartupTerminalDecision.FACTORY_COMPLETED);
            testHooks.afterFactoryTerminalSignal().run();
            PooledWorkerRetireReason abandonedReason = null;
            boolean reportError = false;
            synchronized (this) {
                if (state == AttemptState.WAITING) {
                    taskFinished = true;
                    completedWorker = createdWorker;
                    completedFailure = failure;
                    completedFailureTarget = failureTarget;
                    if (failure == null) {
                        completion.complete(createdWorker);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                    return;
                }
                if (state == AttemptState.ABANDONED) {
                    abandonedReason = abandonReason;
                    state = AttemptState.FINISHED;
                    reportError = failure instanceof Error && claimErrorReport();
                }
            }
            finishAbandonedStart(
                    reservation,
                    session,
                    System.nanoTime() - startedAtNanos,
                    Objects.requireNonNull(abandonedReason, "abandonReason"),
                    failure);
            if (reportError) {
                reportOrRecordLateError(Objects.requireNonNull(failureTarget, "failureTarget"), failure);
            }
        }

        private PooledWorkerRetireReason abandonReason;

        private CreatedWorker<S> await(long deadlineNanos)
                throws TimeoutException, InterruptedException, ExecutionException {
            CreatedWorker<S> result;
            try {
                result = awaitCompletion(deadlineNanos);
            } catch (ExecutionException failure) {
                synchronized (this) {
                    if (state == AttemptState.WAITING) {
                        state = AttemptState.ACCEPTED;
                        completedWorker = null;
                        completedFailure = null;
                        completedFailureTarget = null;
                    }
                }
                throw failure;
            }
            synchronized (this) {
                if (state != AttemptState.WAITING) {
                    throw new TimeoutException("worker startup was abandoned");
                }
                state = AttemptState.ACCEPTED;
                completedWorker = null;
                completedFailure = null;
                completedFailureTarget = null;
            }
            return result;
        }

        private CreatedWorker<S> awaitCompletion(long deadlineNanos)
                throws TimeoutException, InterruptedException, ExecutionException {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return resolveStartupTimeout(new TimeoutException("worker startup deadline elapsed"));
            }
            try {
                return completion.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException failure) {
                return resolveStartupTimeout(failure);
            }
        }

        private CreatedWorker<S> resolveStartupTimeout(TimeoutException failure)
                throws TimeoutException, InterruptedException, ExecutionException {
            StartupTerminalDecision decision = reservation.decideStartupTerminal(StartupTerminalDecision.TIMED_OUT);
            testHooks.afterStartupTimeoutSignal().run();
            if (decision == StartupTerminalDecision.FACTORY_COMPLETED) {
                return completion.get();
            }
            throw failure;
        }

        private void abandon(PooledWorkerRetireReason reason) {
            CreatedWorker<S> lateWorker = null;
            Throwable lateFailureValue = null;
            BoundedFailureReporter.FailureTarget lateFailureTarget = null;
            boolean finished = false;
            synchronized (this) {
                if (state != AttemptState.WAITING) {
                    return;
                }
                state = AttemptState.ABANDONED;
                abandonReason = Objects.requireNonNull(reason, "reason");
                lateWorker = completedWorker;
                lateFailureValue = completedFailure;
                lateFailureTarget = completedFailureTarget;
                finished = taskFinished;
                completedWorker = null;
                completedFailure = null;
                completedFailureTarget = null;
            }
            thread.interrupt();
            if (finished) {
                boolean reportError;
                synchronized (this) {
                    if (state == AttemptState.ABANDONED) {
                        state = AttemptState.FINISHED;
                    } else {
                        return;
                    }
                    reportError = lateFailureValue instanceof Error && claimErrorReport();
                }
                finishAbandonedStart(
                        reservation,
                        lateWorker == null ? null : lateWorker.session(),
                        lateWorker == null ? System.nanoTime() - startedAtNanos : lateWorker.startupNanos(),
                        reason,
                        lateFailureValue);
                if (reportError) {
                    reportOrRecordLateError(
                            Objects.requireNonNull(lateFailureTarget, "lateFailureTarget"), lateFailureValue);
                }
            }
        }

        private boolean claimErrorReport() {
            if (errorReported) {
                return false;
            }
            errorReported = true;
            return true;
        }
    }

    private final class RetirementOwnerAttempt implements Runnable {

        private volatile Throwable failure;

        @Override
        public void run() {
            try {
                runRetirementOwner();
            } catch (Throwable ownerFailure) {
                failure = ownerFailure;
            }
        }

        private Throwable failure() {
            return failure;
        }
    }

    private static final class CloseOnce<S> {

        private final S session;
        private final PoolRetirementDispatcher.Admission admission;
        private final WorkerCloseAction<S> action;
        private CloseObservation observation;
        private CompletableFuture<CloseOutcome> observedOutcome;
        private CompletableFuture<CloseOutcome> outcome;

        private CloseOnce(S session, PoolRetirementDispatcher.Admission admission, WorkerCloseAction<S> action) {
            this.session = session;
            this.admission = admission;
            this.action = action;
        }

        private synchronized void initiate() {
            if (observation == null) {
                try {
                    observation = Objects.requireNonNull(
                            action.initiate(session, admission), "worker close action returned null observation");
                } catch (Throwable failure) {
                    observation = () -> CompletableFuture.completedFuture(CloseOutcome.failure(failure));
                }
            }
        }

        private synchronized CompletableFuture<CloseOutcome> outcome() {
            ensureOutcomes();
            return outcome;
        }

        private void ensureOutcomes() {
            initiate();
            if (outcome != null) {
                return;
            }
            try {
                observedOutcome =
                        Objects.requireNonNull(observation.outcome(), "worker close observation returned null future");
                outcome = normalize(observedOutcome);
            } catch (Throwable failure) {
                outcome = CompletableFuture.completedFuture(CloseOutcome.failure(failure));
            }
        }

        private static CompletableFuture<CloseOutcome> normalize(CompletableFuture<CloseOutcome> observed) {
            return observed.handle((closeOutcome, failure) -> failure == null
                    ? Objects.requireNonNull(closeOutcome, "worker close observation returned null")
                    : CloseOutcome.failure(unwrapCompletionFailure(failure)));
        }
    }

    private enum SlotState {
        STARTING,
        IDLE,
        LEASED,
        RETIRING
    }

    private enum StartupPurpose {
        DEMAND,
        WARMUP,
        REPLENISHMENT
    }

    private enum StartupStage {
        QUEUED,
        RUNNING,
        CANCELLED
    }

    private enum StartupClaim {
        RUN,
        CLOSED,
        TIMED_OUT
    }

    private enum AcquireWaitFailure {
        CLOSED,
        TIMED_OUT,
        INTERRUPTED
    }

    private enum StartupFailureOutcome {
        CLOSED,
        FAILED
    }

    private enum StartupTerminalDecision {
        UNDECIDED,
        FACTORY_COMPLETED,
        TIMED_OUT,
        CLOSED
    }

    enum FaultPoint {
        AFTER_STARTUP_CLAIM
    }

    static final class FaultInjector {

        private final AtomicReference<InjectedFault> pending = new AtomicReference<>();
        private final AtomicReference<Throwable> triggered = new AtomicReference<>();

        void failNext(FaultPoint point, Throwable failure) {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(failure, "failure");
            if (!(failure instanceof RuntimeException) && !(failure instanceof Error)) {
                throw new IllegalArgumentException("injected failure must be unchecked");
            }
            if (!pending.compareAndSet(null, new InjectedFault(point, failure))) {
                throw new IllegalStateException("a fault is already pending");
            }
        }

        Throwable triggeredFailure() {
            return triggered.get();
        }

        private void check(FaultPoint point) {
            InjectedFault fault = pending.get();
            if (fault == null || fault.point() != point || !pending.compareAndSet(fault, null)) {
                return;
            }
            triggered.set(fault.failure());
            if (fault.failure() instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) fault.failure();
        }
    }

    private record InjectedFault(FaultPoint point, Throwable failure) {}

    private enum AttemptState {
        WAITING,
        ACCEPTED,
        ABANDONED,
        FINISHED
    }

    private static final class ConstructionLedger {

        private final ArrayDeque<FailureReport> pending = new ArrayDeque<>();
        private ConstructionPhase phase = ConstructionPhase.CONSTRUCTING;

        private List<FailureReport> commit() {
            requirePhase(ConstructionPhase.CONSTRUCTING);
            phase = ConstructionPhase.COMMITTED;
            return drainPending();
        }

        private List<FailureReport> fail() {
            requirePhase(ConstructionPhase.CONSTRUCTING);
            phase = ConstructionPhase.FAILED;
            return drainPending();
        }

        private void record(FailureReport report) {
            if (!constructing()) {
                throw new IllegalStateException("construction failure can only be recorded before resolution");
            }
            pending.addLast(Objects.requireNonNull(report, "report"));
        }

        private FailureReport route(FailureReport report) {
            if (constructing()) {
                record(report);
                return null;
            }
            return report;
        }

        private boolean constructing() {
            return phase == ConstructionPhase.CONSTRUCTING;
        }

        private boolean failed() {
            return phase == ConstructionPhase.FAILED;
        }

        private List<FailureReport> drainPending() {
            List<FailureReport> reports = new ArrayList<>(pending);
            pending.clear();
            return List.copyOf(reports);
        }

        private void requirePhase(ConstructionPhase expected) {
            if (phase != expected) {
                throw new IllegalStateException("construction phase must be " + expected);
            }
        }
    }

    private static final class RetirementScheduling {

        private RetirementOwnerState state = RetirementOwnerState.IDLE;

        private boolean tryActivate(boolean workAvailable) {
            if (!workAvailable || state != RetirementOwnerState.IDLE) {
                return false;
            }
            state = RetirementOwnerState.RUNNING;
            return true;
        }

        private void markFinishing() {
            if (state != RetirementOwnerState.RUNNING) {
                throw new IllegalStateException("retirement owner cannot finish from " + state);
            }
            state = RetirementOwnerState.FINISHING;
        }

        private boolean complete(boolean workAvailable) {
            if (state != RetirementOwnerState.FINISHING) {
                throw new IllegalStateException("retirement owner cannot complete from " + state);
            }
            state = workAvailable ? RetirementOwnerState.RUNNING : RetirementOwnerState.IDLE;
            return workAvailable;
        }

        private boolean canConstructionClaim() {
            return state == RetirementOwnerState.IDLE;
        }

        private boolean ownerActive() {
            return state != RetirementOwnerState.IDLE;
        }

        private boolean finishing() {
            return state == RetirementOwnerState.FINISHING;
        }
    }

    private enum RetirementOwnerState {
        IDLE,
        RUNNING,
        FINISHING
    }

    private enum ConstructionPhase {
        CONSTRUCTING,
        COMMITTED,
        FAILED
    }

    private record CreatedWorker<S>(S session, long startupNanos) {}

    private record StateCounts(int size, int idle, int leased, int starting, int retiring) {}

    private record DrainPublication(Throwable failure) {}

    private record RetirementOwnerCompletion(Throwable failure) {}

    private static final class PoolCompletionOwner {

        private final PoolRetirementDispatcher.Admission admission;
        private final CompletableFuture<Void> drained;
        private final CompletableFuture<DrainPublication> publication = new CompletableFuture<>();

        private PoolCompletionOwner(PoolRetirementDispatcher.Admission admission, CompletableFuture<Void> drained) {
            this.admission = Objects.requireNonNull(admission, "admission");
            this.drained = Objects.requireNonNull(drained, "drained");
        }

        private void start() {
            Threading.start("procwright-pool-completion-", this::run);
        }

        private void publish(DrainPublication value) {
            if (!publication.complete(Objects.requireNonNull(value, "publication"))) {
                throw new IllegalStateException("pool drain publication is already owned");
            }
        }

        private void run() {
            try {
                new DrainCompletion(publication.join(), drained).run();
            } finally {
                admission.close();
            }
        }
    }

    private static final class DrainCompletion implements Runnable {

        private final DrainPublication publication;
        private final CompletableFuture<Void> drained;

        private DrainCompletion(DrainPublication publication, CompletableFuture<Void> drained) {
            this.publication = publication;
            this.drained = drained;
        }

        @Override
        public void run() {
            Throwable failure = publication.failure();
            if (failure == null) {
                drained.complete(null);
            } else {
                drained.completeExceptionally(failure);
            }
        }
    }

    private record FailureReport(BoundedFailureReporter.FailureTarget failureTarget, Throwable failure) {

        private FailureReport {
            Objects.requireNonNull(failureTarget, "failureTarget");
            Objects.requireNonNull(failure, "failure");
        }
    }

    private record RetirementOutcome(Throwable closeFailure, FailureReport lateReport) {}
}

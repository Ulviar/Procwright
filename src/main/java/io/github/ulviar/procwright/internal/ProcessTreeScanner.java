/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Owns bounded process-tree discovery and operations on trusted provider process objects.
 *
 * <p>Provider operations reuse non-inheriting daemon workers. The owner restores supported mutable thread state between
 * operations: name, context class loader, uncaught-exception handler, priority, and interrupt status. Arbitrary
 * {@link ThreadLocal} values cannot be enumerated or cleared by supported Java APIs and are outside this trusted
 * internal boundary.
 */
final class ProcessTreeScanner {

    static final int SHARED_OPERATION_CAPACITY = 32;
    static final int SHARED_DESCENDANT_LIMIT = 4_096;
    static final Duration SHARED_SCAN_TIMEOUT = Duration.ofMillis(250);
    static final Duration PROVIDER_OPERATION_TIMEOUT = Duration.ofSeconds(5);

    private static final Runnable NO_REPORTING_HOOK = () -> {};
    private static final SetupFailureInjector NO_SETUP_FAILURE = boundary -> {};
    private static final ProcessTreeScanner SHARED =
            new ProcessTreeScanner(SHARED_OPERATION_CAPACITY, SHARED_DESCENDANT_LIMIT, SHARED_SCAN_TIMEOUT);

    private final OperationOwner operations;
    private final int descendantLimit;
    private final Duration scanTimeout;
    private final Duration providerOperationTimeout;
    private final BoundedFailureReporter failureReporter;

    ProcessTreeScanner(int operationCapacity, int descendantLimit, Duration scanTimeout) {
        this(operationCapacity, descendantLimit, scanTimeout, PROVIDER_OPERATION_TIMEOUT);
    }

    ProcessTreeScanner(
            int operationCapacity, int descendantLimit, Duration scanTimeout, Duration providerOperationTimeout) {
        this(operationCapacity, descendantLimit, scanTimeout, providerOperationTimeout, null);
    }

    ProcessTreeScanner(
            int operationCapacity,
            int descendantLimit,
            Duration scanTimeout,
            Duration providerOperationTimeout,
            OperationThreadFactory threadFactory) {
        this(
                operationCapacity,
                descendantLimit,
                scanTimeout,
                providerOperationTimeout,
                threadFactory,
                BoundedFailureReporter.shared());
    }

    ProcessTreeScanner(
            int operationCapacity,
            int descendantLimit,
            Duration scanTimeout,
            Duration providerOperationTimeout,
            OperationThreadFactory threadFactory,
            BoundedFailureReporter failureReporter) {
        this(
                operationCapacity,
                descendantLimit,
                scanTimeout,
                providerOperationTimeout,
                threadFactory,
                failureReporter,
                NO_REPORTING_HOOK,
                NO_SETUP_FAILURE);
    }

    ProcessTreeScanner(
            int operationCapacity,
            int descendantLimit,
            Duration scanTimeout,
            Duration providerOperationTimeout,
            OperationThreadFactory threadFactory,
            BoundedFailureReporter failureReporter,
            Runnable beforeReportSubmission) {
        this(
                operationCapacity,
                descendantLimit,
                scanTimeout,
                providerOperationTimeout,
                threadFactory,
                failureReporter,
                beforeReportSubmission,
                NO_SETUP_FAILURE);
    }

    ProcessTreeScanner(
            int operationCapacity,
            int descendantLimit,
            Duration scanTimeout,
            Duration providerOperationTimeout,
            OperationThreadFactory threadFactory,
            BoundedFailureReporter failureReporter,
            Runnable beforeReportSubmission,
            SetupFailureInjector setupFailureInjector) {
        if (descendantLimit <= 0) {
            throw new IllegalArgumentException("descendantLimit must be positive");
        }
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.operations = new OperationOwner(
                operationCapacity,
                threadFactory,
                failureReporter,
                Objects.requireNonNull(beforeReportSubmission, "beforeReportSubmission"),
                Objects.requireNonNull(setupFailureInjector, "setupFailureInjector"));
        this.descendantLimit = descendantLimit;
        this.scanTimeout = Objects.requireNonNull(scanTimeout, "scanTimeout");
        this.providerOperationTimeout = Objects.requireNonNull(providerOperationTimeout, "providerOperationTimeout");
        if (scanTimeout.isNegative() || scanTimeout.isZero()) {
            throw new IllegalArgumentException("scanTimeout must be positive");
        }
        if (providerOperationTimeout.isNegative() || providerOperationTimeout.isZero()) {
            throw new IllegalArgumentException("providerOperationTimeout must be positive");
        }
    }

    static ProcessTreeScanner shared() {
        return SHARED;
    }

    Process guard(Process process) {
        Objects.requireNonNull(process, "process");
        return process instanceof GuardedProcess ? process : new GuardedProcess(process, this);
    }

    Set<ProcessHandle> descendants(Process process) {
        return descendants(process, scanTimeout);
    }

    Set<ProcessHandle> descendants(Process process, Duration budget) {
        Objects.requireNonNull(process, "process");
        Duration timeout = scanBudget(budget);
        if (timeout.isZero()) {
            return Set.of();
        }
        Process source = unwrap(process);
        Optional<Set<ProcessHandle>> scanned = operations.bestEffort("procwright-process-scan-", timeout, () -> {
            Set<ProcessHandle> handles = collectDescendants(source, timeout);
            return guardIfRequired(process, handles);
        });
        return scanned.orElseGet(Set::of);
    }

    Set<ProcessHandle> descendantsOfHandles(Iterable<ProcessHandle> handles) {
        return descendantsOfHandles(handles, scanTimeout);
    }

    Set<ProcessHandle> descendantsOfHandles(Iterable<ProcessHandle> handles, Duration budget) {
        Objects.requireNonNull(handles, "handles");
        Duration timeout = scanBudget(budget);
        if (timeout.isZero()) {
            return Set.of();
        }
        List<ProcessHandle> roots = new ArrayList<>();
        boolean guarded = false;
        for (ProcessHandle handle : handles) {
            if (roots.size() == descendantLimit) {
                break;
            }
            Objects.requireNonNull(handle, "handle");
            guarded |= handle instanceof GuardedProcessHandle;
            roots.add(unwrap(handle));
        }
        boolean guardResults = guarded;
        Optional<Set<ProcessHandle>> scanned = operations.bestEffort("procwright-handle-scan-", timeout, () -> {
            Set<ProcessHandle> result = collectChildren(roots, timeout);
            return guardResults ? guardHandles(result) : result;
        });
        return scanned.orElseGet(Set::of);
    }

    Set<ProcessHandle> childrenOfHandle(ProcessHandle handle) {
        Objects.requireNonNull(handle, "handle");
        ProcessHandle source = unwrap(handle);
        boolean guardResults = handle instanceof GuardedProcessHandle;
        Optional<Set<ProcessHandle>> scanned =
                operations.bestEffort("procwright-handle-children-scan-", scanTimeout, () -> {
                    Set<ProcessHandle> result = collectStream(source.children(), scanTimeout);
                    return guardResults ? guardHandles(result) : result;
                });
        return scanned.orElseGet(Set::of);
    }

    <T> T required(String operationName, Duration timeout, Callable<T> operation) throws InterruptedException {
        return operations.required(operationName, timeout, operation);
    }

    int availableOperationPermits() {
        return operations.availablePermits();
    }

    boolean awaitReportingSettlement(Duration timeout) throws InterruptedException {
        return failureReporter.awaitSettlement(timeout);
    }

    static boolean causedByOperationDeadline(CommandExecutionException failure) {
        return failure.getCause() instanceof OperationDeadlineExceeded;
    }

    static CommandExecutionException operationDeadlineExceeded(String operation) {
        return operationDeadlineExceeded(operation, null);
    }

    private static CommandExecutionException operationDeadlineExceeded(
            String operation, TimeoutException timeoutFailure) {
        OperationDeadlineExceeded deadline = new OperationDeadlineExceeded(operation);
        if (timeoutFailure != null) {
            deadline.initCause(timeoutFailure);
        }
        return new CommandExecutionException(
                CommandExecutionException.Reason.RUNTIME_FAILURE,
                "Provider process operation exceeded its bounded deadline: " + operation,
                deadline);
    }

    int descendantLimit() {
        return descendantLimit;
    }

    Duration providerOperationTimeout() {
        return providerOperationTimeout;
    }

    Duration scanTimeout() {
        return scanTimeout;
    }

    private Set<ProcessHandle> collectDescendants(Process process, Duration timeout) {
        return collectStream(process.descendants(), timeout);
    }

    private Set<ProcessHandle> collectChildren(List<ProcessHandle> roots, Duration timeout) {
        long deadline = DurationSupport.deadlineFromNow(timeout);
        ArrayDeque<ProcessHandle> pending = new ArrayDeque<>(roots);
        LinkedHashSet<HandleIdentity> expanded = new LinkedHashSet<>();
        LinkedHashMap<HandleIdentity, ProcessHandle> observed = new LinkedHashMap<>();
        while (!pending.isEmpty() && observed.size() < descendantLimit && deadline - System.nanoTime() > 0) {
            ProcessHandle parent = pending.removeFirst();
            if (!expanded.add(identity(parent))) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            Set<ProcessHandle> children = collectStream(parent.children(), Duration.ofNanos(remaining));
            for (ProcessHandle child : children) {
                HandleIdentity childIdentity = identity(child);
                if (observed.putIfAbsent(childIdentity, child) == null) {
                    pending.addLast(child);
                }
                if (observed.size() == descendantLimit) {
                    break;
                }
            }
        }
        return new LinkedHashSet<>(observed.values());
    }

    private Set<ProcessHandle> collectStream(Stream<ProcessHandle> stream, Duration timeout) {
        Objects.requireNonNull(stream, "process handle stream");
        long deadline = DurationSupport.deadlineFromNow(timeout);
        LinkedHashMap<HandleIdentity, ProcessHandle> observed = new LinkedHashMap<>();
        Throwable traversalFailure = null;
        try {
            Iterator<ProcessHandle> iterator = stream.iterator();
            while (observed.size() < descendantLimit && deadline - System.nanoTime() > 0) {
                if (!iterator.hasNext()) {
                    break;
                }
                ProcessHandle handle = Objects.requireNonNull(iterator.next(), "process descendant");
                observed.putIfAbsent(identity(handle), handle);
            }
        } catch (RuntimeException | Error failure) {
            traversalFailure = failure;
        }
        Error closeFailure = null;
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // Traversal is best effort; ordinary close failures degrade to the collected prefix.
        } catch (Error failure) {
            closeFailure = failure;
        }
        if (traversalFailure instanceof Error fatal) {
            SuppressionSupport.attach(fatal, closeFailure);
            throw fatal;
        }
        if (closeFailure != null) {
            SuppressionSupport.attach(closeFailure, traversalFailure);
            throw closeFailure;
        }
        if (traversalFailure instanceof RuntimeException unavailable) {
            throw unavailable;
        }
        return new LinkedHashSet<>(observed.values());
    }

    private Duration scanBudget(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative()) {
            throw new IllegalArgumentException("budget must not be negative");
        }
        return budget.compareTo(scanTimeout) < 0 ? budget : scanTimeout;
    }

    private static HandleIdentity identity(ProcessHandle handle) {
        long pid = handle.pid();
        Instant started = null;
        try {
            started = handle.info().startInstant().orElse(null);
        } catch (RuntimeException ignored) {
            // PID identity is the bounded fallback when process metadata is unavailable.
        }
        return new HandleIdentity(pid, started);
    }

    private Set<ProcessHandle> guardIfRequired(Process root, Set<ProcessHandle> handles) {
        return root instanceof GuardedProcess ? guardHandles(handles) : handles;
    }

    private Set<ProcessHandle> guardHandles(Set<ProcessHandle> handles) {
        LinkedHashSet<ProcessHandle> guarded = new LinkedHashSet<>(handles.size());
        for (ProcessHandle handle : handles) {
            guarded.add(guardObserved(handle));
        }
        return guarded;
    }

    ProcessHandle guardObserved(ProcessHandle handle) {
        return handle instanceof GuardedProcessHandle
                ? handle
                : new GuardedProcessHandle(handle, this, identity(handle));
    }

    private static Process unwrap(Process process) {
        return process instanceof GuardedProcess guarded ? guarded.delegate() : process;
    }

    private static ProcessHandle unwrap(ProcessHandle handle) {
        return handle instanceof GuardedProcessHandle guarded ? guarded.delegate() : handle;
    }

    record HandleIdentity(long pid, Instant startInstant) {}

    @SuppressWarnings("serial")
    private static final class OperationDeadlineExceeded extends TimeoutException {

        private OperationDeadlineExceeded(String operation) {
            super("Provider process operation exceeded its bounded deadline: " + operation);
        }
    }

    @FunctionalInterface
    interface OperationThreadFactory {

        Thread unstarted(String threadPrefix, Runnable task);
    }

    @FunctionalInterface
    interface SetupFailureInjector {

        void after(SetupBoundary boundary);
    }

    enum SetupBoundary {
        PERMIT_ACQUIRED,
        INPUTS_VALIDATED,
        COMPLETION_ALLOCATED,
        LATE_FAILURE_REPORTER_ALLOCATED,
        PRODUCER_REGISTERED,
        FAILURE_SETTLEMENT_ALLOCATED,
        OPERATION_EXECUTION_ALLOCATED,
        START_GATE_ALLOCATED,
        HANDOFF_ALLOCATED,
        WORKER_TASK_ALLOCATED,
        WORKER_SUBMITTED_BEFORE_HANDOFF
    }

    private static final class OperationOwner {

        private final Semaphore permits;
        private final ThreadPoolExecutor workers;
        private final BoundedFailureReporter failureReporter;
        private final Runnable beforeReportSubmission;
        private final SetupFailureInjector setupFailureInjector;
        private final ThreadLocal<WorkerState> workerState = new ThreadLocal<>();

        private OperationOwner(
                int capacity,
                OperationThreadFactory threadFactory,
                BoundedFailureReporter failureReporter,
                Runnable beforeReportSubmission,
                SetupFailureInjector setupFailureInjector) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("operationCapacity must be positive");
            }
            this.failureReporter = failureReporter;
            this.beforeReportSubmission = beforeReportSubmission;
            this.setupFailureInjector = setupFailureInjector;
            permits = new Semaphore(capacity, true);
            workers = threadFactory == null
                    ? Threading.newReusableBoundedExecutor("procwright-process-operation-owner-", capacity, capacity)
                    : Threading.newReusableBoundedExecutor(
                            "procwright-process-operation-owner-", capacity, capacity, threadFactory::unstarted);
        }

        private <T> Optional<T> bestEffort(String threadPrefix, Duration timeout, Callable<T> operation) {
            OperationSetupLedger setup = new OperationSetupLedger(permits, workers);
            if (!setup.tryAcquirePermit()) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(execute(threadPrefix, timeout, operation, setup));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (SecurityException | UnsupportedOperationException unavailable) {
                return Optional.empty();
            } catch (RuntimeException unavailable) {
                return Optional.empty();
            } catch (Error fatal) {
                throw fatal;
            } catch (Exception impossible) {
                return Optional.empty();
            }
        }

        private <T> T required(String threadPrefix, Duration timeout, Callable<T> operation)
                throws InterruptedException {
            OperationSetupLedger setup = new OperationSetupLedger(permits, workers);
            if (!setup.tryAcquirePermit()) {
                throw new CommandExecutionException(
                        CommandExecutionException.Reason.RUNTIME_FAILURE,
                        "Could not execute provider process operation because bounded capacity is exhausted");
            }
            try {
                return execute(threadPrefix, timeout, operation, setup);
            } catch (InterruptedException interruption) {
                throw interruption;
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new CommandExecutionException(
                        CommandExecutionException.Reason.RUNTIME_FAILURE,
                        "Provider process operation failed: " + threadPrefix,
                        failure);
            }
        }

        private <T> T execute(String threadPrefix, Duration timeout, Callable<T> operation, OperationSetupLedger setup)
                throws Exception {
            OperationHandoff<T> handoff;
            try {
                setupFailureInjector.after(SetupBoundary.PERMIT_ACQUIRED);
                Objects.requireNonNull(threadPrefix, "threadPrefix");
                Objects.requireNonNull(timeout, "timeout");
                Objects.requireNonNull(operation, "operation");
                setupFailureInjector.after(SetupBoundary.INPUTS_VALIDATED);

                CompletableFuture<Outcome<T>> completion = new CompletableFuture<>();
                setupFailureInjector.after(SetupBoundary.COMPLETION_ALLOCATED);
                LateTaskFailureReporter lateFailureReporter = new LateTaskFailureReporter(failureReporter);
                setupFailureInjector.after(SetupBoundary.LATE_FAILURE_REPORTER_ALLOCATED);
                BoundedFailureReporter.ProducerRegistration producer = failureReporter.registerProducer();
                setup.ownProducer(producer);
                setupFailureInjector.after(SetupBoundary.PRODUCER_REGISTERED);
                LateFailureSettlement lateFailure =
                        new LateFailureSettlement(lateFailureReporter, producer, beforeReportSubmission);
                setupFailureInjector.after(SetupBoundary.FAILURE_SETTLEMENT_ALLOCATED);
                OperationExecution execution = new OperationExecution(lateFailure);
                setupFailureInjector.after(SetupBoundary.OPERATION_EXECUTION_ALLOCATED);
                StartGate<T> startGate = new StartGate<>();
                setup.ownStartGate(startGate);
                setupFailureInjector.after(SetupBoundary.START_GATE_ALLOCATED);
                handoff = new OperationHandoff<>(completion, lateFailure, execution, setup.permit());
                setupFailureInjector.after(SetupBoundary.HANDOFF_ALLOCATED);
                Runnable workerTask = () -> runOperation(startGate, threadPrefix, operation);
                setup.ownWorkerTask(workerTask);
                setupFailureInjector.after(SetupBoundary.WORKER_TASK_ALLOCATED);

                workers.prestartCoreThread();
                workers.execute(workerTask);
                setupFailureInjector.after(SetupBoundary.WORKER_SUBMITTED_BEFORE_HANDOFF);
                startGate.commit(setup, handoff);
            } catch (RuntimeException | Error setupFailure) {
                setup.rollback();
                throw setupFailure;
            }
            return awaitResult(threadPrefix, timeout, handoff);
        }

        private <T> void runOperation(StartGate<T> startGate, String threadPrefix, Callable<T> operation) {
            OperationHandoff<T> handoff = startGate.awaitHandoff();
            if (handoff == null) {
                return;
            }
            CompletableFuture<Outcome<T>> completion = handoff.completion();
            LateFailureSettlement lateFailure = handoff.lateFailure();
            OperationExecution execution = handoff.execution();
            Permit permit = handoff.permit();
            Thread worker = Thread.currentThread();
            boolean bound = false;
            try {
                execution.bind(worker);
                bound = true;
                WorkerState baseline = workerState.get();
                if (baseline == null) {
                    baseline = WorkerState.capture(worker);
                    workerState.set(baseline);
                }
                tryRename(worker, threadPrefix + Integer.toUnsignedString(System.identityHashCode(execution)));
                Outcome<T> outcome;
                try {
                    outcome = Outcome.completed(operation.call());
                } catch (Throwable failure) {
                    outcome = Outcome.failed(failure);
                }
                Throwable sanitationFailure = baseline.restore(worker);
                if (sanitationFailure != null) {
                    if (outcome.failure() == null) {
                        outcome = Outcome.failed(sanitationFailure);
                    } else {
                        SuppressionSupport.attach(outcome.failure(), sanitationFailure);
                    }
                }
                permit.close();
                completion.complete(outcome);
                lateFailure.workerCompleted(outcome.failure());
                if (sanitationFailure != null) {
                    throw new WorkerStateResetException(sanitationFailure);
                }
            } catch (RuntimeException | Error infrastructureFailure) {
                if (!completion.isDone()) {
                    permit.close();
                    try {
                        completion.complete(Outcome.failed(infrastructureFailure));
                    } finally {
                        lateFailure.workerCompleted(infrastructureFailure);
                    }
                }
                throw infrastructureFailure;
            } finally {
                permit.close();
                if (bound) {
                    execution.unbind(worker);
                }
            }
        }

        private static <T> T awaitResult(String threadPrefix, Duration timeout, OperationHandoff<T> handoff)
                throws Exception {
            try {
                Outcome<T> outcome =
                        handoff.completion().get(DurationSupport.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
                handoff.lateFailure().resultObserved();
                if (outcome.failure() instanceof Exception exception) {
                    throw exception;
                }
                if (outcome.failure() instanceof Error error) {
                    throw error;
                }
                return outcome.value();
            } catch (TimeoutException timeoutFailure) {
                handoff.lateFailure().abandon();
                handoff.execution().interrupt();
                throw operationDeadlineExceeded(threadPrefix, timeoutFailure);
            } catch (ExecutionException impossible) {
                throw new AssertionError("process operation completion stores failures as values", impossible);
            } catch (InterruptedException interruption) {
                handoff.lateFailure().abandon();
                handoff.execution().interrupt();
                throw interruption;
            }
        }

        private int availablePermits() {
            return permits.availablePermits();
        }

        private static boolean tryRename(Thread thread, String name) {
            try {
                thread.setName(name);
                return true;
            } catch (SecurityException denied) {
                return false;
            }
        }
    }

    private record WorkerState(
            String name,
            ClassLoader contextClassLoader,
            Thread.UncaughtExceptionHandler uncaughtExceptionHandler,
            int priority) {

        private static WorkerState capture(Thread thread) {
            return new WorkerState(
                    thread.getName(),
                    thread.getContextClassLoader(),
                    thread.getUncaughtExceptionHandler(),
                    thread.getPriority());
        }

        private Throwable restore(Thread thread) {
            Throwable failure = null;
            Thread.interrupted();
            failure = restore(failure, () -> thread.setName(name));
            failure = restore(failure, () -> thread.setContextClassLoader(contextClassLoader));
            failure = restore(failure, () -> thread.setUncaughtExceptionHandler(uncaughtExceptionHandler));
            failure = restore(failure, () -> thread.setPriority(priority));
            Thread.interrupted();
            return failure;
        }

        private static Throwable restore(Throwable primary, Runnable operation) {
            try {
                operation.run();
                return primary;
            } catch (RuntimeException | Error failure) {
                return SuppressionSupport.combine(primary, failure);
            }
        }
    }

    private static final class WorkerStateResetException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private WorkerStateResetException(Throwable cause) {
            super("Reusable process-provider worker state could not be restored", cause);
        }
    }

    private static final class LateFailureSettlement {

        private final LateTaskFailureReporter lateFailure;
        private final BoundedFailureReporter.ProducerRegistration producer;
        private final Runnable beforeReportSubmission;
        private boolean callerSettled;
        private boolean workerSettled;
        private boolean producerSettled;

        private LateFailureSettlement(
                LateTaskFailureReporter lateFailure,
                BoundedFailureReporter.ProducerRegistration producer,
                Runnable beforeReportSubmission) {
            this.lateFailure = lateFailure;
            this.beforeReportSubmission = beforeReportSubmission;
            this.producer = producer;
        }

        private void bind(Thread thread) {
            lateFailure.bind(thread);
        }

        private void resultObserved() {
            settleCaller();
        }

        private void abandon() {
            try {
                lateFailure.abandon();
            } finally {
                settleCaller();
            }
        }

        private void workerCompleted(Throwable failure) {
            try {
                if (failure != null && !(failure instanceof InterruptedException)) {
                    beforeReportSubmission.run();
                    lateFailure.record(failure);
                }
            } finally {
                settleWorker();
            }
        }

        private void settleCaller() {
            BoundedFailureReporter.ProducerRegistration settledProducer;
            synchronized (this) {
                callerSettled = true;
                settledProducer = claimSettledProducer();
            }
            complete(settledProducer);
        }

        private void settleWorker() {
            BoundedFailureReporter.ProducerRegistration settledProducer;
            synchronized (this) {
                workerSettled = true;
                settledProducer = claimSettledProducer();
            }
            complete(settledProducer);
        }

        private BoundedFailureReporter.ProducerRegistration claimSettledProducer() {
            if (!callerSettled || !workerSettled || producerSettled) {
                return null;
            }
            producerSettled = true;
            return producer;
        }

        private static void complete(BoundedFailureReporter.ProducerRegistration producer) {
            if (producer != null) {
                producer.complete();
            }
        }
    }

    private static final class OperationExecution {

        private final LateFailureSettlement lateFailure;
        private Thread activeThread;
        private boolean interruptRequested;

        private OperationExecution(LateFailureSettlement lateFailure) {
            this.lateFailure = lateFailure;
        }

        private synchronized void bind(Thread thread) {
            activeThread = thread;
            lateFailure.bind(thread);
            if (interruptRequested) {
                thread.interrupt();
            }
        }

        private synchronized void unbind(Thread thread) {
            if (activeThread == thread) {
                activeThread = null;
            }
        }

        private synchronized void interrupt() {
            interruptRequested = true;
            if (activeThread != null) {
                activeThread.interrupt();
            }
        }
    }

    private static final class OperationSetupLedger {

        private final Permit permit;
        private final ThreadPoolExecutor workers;
        private BoundedFailureReporter.ProducerRegistration producer;
        private StartGate<?> startGate;
        private Runnable workerTask;
        private SetupOwnership ownership = SetupOwnership.SETUP;

        private OperationSetupLedger(Semaphore permits, ThreadPoolExecutor workers) {
            permit = new Permit(permits);
            this.workers = workers;
        }

        private boolean tryAcquirePermit() {
            return permit.tryAcquire();
        }

        private Permit permit() {
            return permit;
        }

        private void ownProducer(BoundedFailureReporter.ProducerRegistration producer) {
            this.producer = producer;
        }

        private void ownStartGate(StartGate<?> startGate) {
            this.startGate = startGate;
        }

        private void ownWorkerTask(Runnable workerTask) {
            this.workerTask = workerTask;
        }

        private void transferToWorker() {
            if (ownership != SetupOwnership.SETUP) {
                throw new IllegalStateException("process operation setup is no longer caller-owned");
            }
            ownership = SetupOwnership.WORKER;
        }

        private void rollback() {
            if (ownership != SetupOwnership.SETUP) {
                return;
            }
            ownership = SetupOwnership.ROLLED_BACK;
            try {
                if (startGate != null) {
                    startGate.reject();
                }
            } catch (RuntimeException | Error ignored) {
                // Cleanup below must still settle both bounded resources.
            }
            try {
                if (workerTask != null) {
                    workers.remove(workerTask);
                }
            } catch (RuntimeException | Error ignored) {
                // Producer and permit cleanup must not depend on executor queue cleanup.
            }
            try {
                if (producer != null) {
                    producer.complete();
                }
            } catch (RuntimeException | Error ignored) {
                // Permit recovery is independent from reporting settlement cleanup.
            } finally {
                permit.close();
            }
        }
    }

    private enum SetupOwnership {
        SETUP,
        WORKER,
        ROLLED_BACK
    }

    private record OperationHandoff<T>(
            CompletableFuture<Outcome<T>> completion,
            LateFailureSettlement lateFailure,
            OperationExecution execution,
            Permit permit) {}

    private static final class StartGate<T> {

        private GateState state = GateState.PENDING;
        private OperationHandoff<T> handoff;

        private synchronized void commit(OperationSetupLedger setup, OperationHandoff<T> handoff) {
            if (state != GateState.PENDING) {
                throw new IllegalStateException("process operation start gate is already settled");
            }
            setup.transferToWorker();
            this.handoff = handoff;
            state = GateState.ADMITTED;
            notifyAll();
        }

        private synchronized void reject() {
            if (state == GateState.PENDING) {
                state = GateState.REJECTED;
                notifyAll();
            }
        }

        private synchronized OperationHandoff<T> awaitHandoff() {
            boolean interrupted = false;
            while (state == GateState.PENDING) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return state == GateState.ADMITTED ? handoff : null;
        }
    }

    private enum GateState {
        PENDING,
        ADMITTED,
        REJECTED
    }

    private record Outcome<T>(T value, Throwable failure) {

        private static <T> Outcome<T> completed(T value) {
            return new Outcome<>(value, null);
        }

        private static <T> Outcome<T> failed(Throwable failure) {
            return new Outcome<>(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    private static final class Permit implements AutoCloseable {

        private final Semaphore permits;
        private boolean acquired;
        private boolean closed;

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        private synchronized boolean tryAcquire() {
            if (!permits.tryAcquire()) {
                return false;
            }
            acquired = true;
            return true;
        }

        @Override
        public synchronized void close() {
            if (acquired && !closed) {
                closed = true;
                permits.release();
            }
        }
    }
}

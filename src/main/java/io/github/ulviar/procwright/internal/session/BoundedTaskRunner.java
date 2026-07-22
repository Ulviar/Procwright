/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.Threading;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Bounds timed operations whose implementation may ignore interruption.
 *
 * <p>Java cannot forcibly terminate arbitrary callback code. A timed-out task therefore retains its permit until the
 * task actually returns. This lets the caller observe its deadline while putting a hard upper bound on abandoned
 * library-managed threads. By default each admitted invocation receives a fresh daemon platform thread that does not
 * inherit thread-local state. A caller may supply a narrower lifecycle owner only when callback ownership itself
 * provides the isolation boundary.
 */
public final class BoundedTaskRunner {

    private static final long CANCELLATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final TaskAbandonmentHandler NO_OP_ABANDONMENT = failure -> {};

    static final Limiter STREAM_LISTENERS = new Limiter(16);
    static final Limiter READINESS_PROBES = new Limiter(16);
    static final Limiter WORKER_HOOKS = new Limiter(16);
    static final Limiter PROTOCOL_CALLBACKS = new Limiter(64);
    static final Limiter TEXT_ENCODINGS = new Limiter(32);
    static final Limiter BLOCKING_WRITES = new Limiter(32);
    static final Limiter WORKER_STARTUPS = new Limiter(16);
    static final Limiter REGEX_MATCHES = new Limiter(8);

    private BoundedTaskRunner() {}

    static <T> T run(Limiter limiter, String threadPrefix, long deadlineNanos, Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        try {
            return run(limiter, threadPrefix, deadlineNanos, null, task);
        } catch (TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    static <T> T run(
            Limiter limiter, String threadPrefix, long deadlineNanos, CancellationSignal cancellation, Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return runReportingLateFailure(
                limiter, threadPrefix, deadlineNanos, cancellation, BoundedTaskRunner::reportLateFailure, task);
    }

    static <T> T run(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFatalHandler lateFatalHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return run(limiter, threadPrefix, deadlineNanos, cancellation, lateFatalHandler, null, task);
    }

    private static <T> T run(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFatalHandler lateFatalHandler,
            TaskHandoff handoff,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return runReportingLateFailure(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation,
                (thread, failure) -> {
                    if (failure instanceof Error error) {
                        lateFatalHandler.handle(thread, error);
                    }
                },
                NO_OP_ABANDONMENT,
                handoff,
                null,
                null,
                task);
    }

    static <T> T runTracked(Limiter limiter, String threadPrefix, long deadlineNanos, TaskHandoff handoff, Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        return runTracked(limiter, threadPrefix, deadlineNanos, handoff, null, task);
    }

    static <T> T runTracked(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            TaskHandoff handoff,
            TaskThreadFactory threadFactory,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        Objects.requireNonNull(handoff, "handoff");
        try {
            return runReportingLateFailure(
                    limiter,
                    threadPrefix,
                    deadlineNanos,
                    null,
                    BoundedTaskRunner::reportLateFailure,
                    NO_OP_ABANDONMENT,
                    handoff,
                    threadFactory,
                    null,
                    task);
        } catch (TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    static <T> T runReportingLateFailure(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFailureHandler lateFailureHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return runReportingLateFailure(
                limiter, threadPrefix, deadlineNanos, cancellation, lateFailureHandler, NO_OP_ABANDONMENT, task);
    }

    static <T> T runReportingLateFailure(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFailureHandler lateFailureHandler,
            TaskAbandonmentHandler abandonmentHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return runReportingLateFailure(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation,
                lateFailureHandler,
                abandonmentHandler,
                null,
                null,
                null,
                task);
    }

    static <T> T runReportingLateFailure(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFailureHandler lateFailureHandler,
            TaskStarter taskStarter,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return runReportingLateFailure(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation,
                lateFailureHandler,
                NO_OP_ABANDONMENT,
                null,
                null,
                Objects.requireNonNull(taskStarter, "taskStarter"),
                task);
    }

    private static <T> T runReportingLateFailure(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFailureHandler lateFailureHandler,
            TaskAbandonmentHandler abandonmentHandler,
            TaskHandoff handoff,
            TaskThreadFactory threadFactory,
            TaskStarter taskStarter,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return runReportingLateFailure(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation,
                lateFailureHandler,
                abandonmentHandler,
                handoff,
                threadFactory,
                taskStarter,
                System::nanoTime,
                task);
    }

    static <T> T runTracked(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            TaskHandoff handoff,
            TaskThreadFactory threadFactory,
            LongSupplier nanoTime,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        Objects.requireNonNull(handoff, "handoff");
        try {
            return runReportingLateFailure(
                    limiter,
                    threadPrefix,
                    deadlineNanos,
                    null,
                    BoundedTaskRunner::reportLateFailure,
                    NO_OP_ABANDONMENT,
                    handoff,
                    threadFactory,
                    null,
                    nanoTime,
                    task);
        } catch (TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    private static <T> T runReportingLateFailure(
            Limiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFailureHandler lateFailureHandler,
            TaskAbandonmentHandler abandonmentHandler,
            TaskHandoff handoff,
            TaskThreadFactory threadFactory,
            TaskStarter taskStarter,
            LongSupplier nanoTime,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        Objects.requireNonNull(limiter, "limiter");
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(lateFailureHandler, "lateFailureHandler");
        Objects.requireNonNull(abandonmentHandler, "abandonmentHandler");
        Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(task, "task");

        Permit permit;
        try {
            permit = cancellation == null
                    ? limiter.acquire(deadlineNanos, nanoTime)
                    : limiter.acquire(deadlineNanos, cancellation, nanoTime);
        } catch (TimeoutException | InterruptedException | TaskCancelledException failure) {
            if (handoff != null) {
                handoff.rejectBeforeAdmission();
            }
            throw failure;
        }
        boolean permitTransferred = false;
        StartGate startGate = null;
        CancellationRegistration cancellationRegistration = null;
        try {
            startGate = new StartGate();
            CompletableFuture<TaskOutcome<T>> completion = new CompletableFuture<>();
            CompletableFuture<TaskOutcome<T>> race = new CompletableFuture<>();
            completion.thenAccept(race::complete);
            cancellationRegistration = cancellation == null
                    ? null
                    : cancellation.register(() -> race.complete(TaskOutcome.cancelledOutcome()));
            LateFailurePublication lateFailure = new LateFailurePublication(lateFailureHandler);
            TaskExecution execution = new TaskExecution(lateFailure);
            AtomicBoolean taskClaimed = new AtomicBoolean();
            try {
                if (cancellation != null) {
                    cancellation.throwIfCancelled();
                }
                StartGate taskStartGate = startGate;
                Runnable boundedTask = () -> {
                    if (!taskClaimed.compareAndSet(false, true)) {
                        return;
                    }
                    if (!taskStartGate.awaitAdmission()) {
                        return;
                    }
                    Thread current = Thread.currentThread();
                    String previousName = current.getName();
                    boolean renamed = tryRename(current, execution.taskName(threadPrefix));
                    execution.bind(current);
                    TaskOutcome<T> outcome;
                    Throwable taskFailure = null;
                    try (permit) {
                        try {
                            if (handoff != null) {
                                handoff.start();
                            }
                            outcome = TaskOutcome.completed(task.run());
                        } catch (Throwable failure) {
                            taskFailure = failure;
                            outcome = TaskOutcome.failed(failure);
                        }
                    } finally {
                        execution.unbind(current);
                        if (renamed) {
                            tryRename(current, previousName);
                        }
                    }
                    if (taskFailure != null) {
                        lateFailure.record(taskFailure);
                    }
                    completion.complete(outcome);
                };
                TaskRejection taskRejection = failure -> {
                    Objects.requireNonNull(failure, "failure");
                    if (!taskClaimed.compareAndSet(false, true)) {
                        return;
                    }
                    if (!taskStartGate.awaitAdmission()) {
                        return;
                    }
                    Thread current = Thread.currentThread();
                    execution.bind(current);
                    try (permit) {
                        // The owner accepted the task but could not provide a replacement execution thread.
                    } finally {
                        execution.unbind(current);
                    }
                    lateFailure.record(failure);
                    completion.complete(TaskOutcome.failed(failure));
                };
                if (cancellation != null) {
                    cancellation.throwIfCancelled();
                }
                if (deadlineNanos - nanoTime.getAsLong() <= 0) {
                    throw new TimeoutException("operation deadline elapsed before task start");
                }
                if (taskStarter != null) {
                    taskStarter.start(threadPrefix, boundedTask, taskRejection);
                } else if (threadFactory == null) {
                    Thread thread =
                            Threading.unstartedPlatformNonInheriting(execution.taskName(threadPrefix), boundedTask);
                    thread.start();
                } else {
                    Thread thread = Objects.requireNonNull(
                            threadFactory.unstarted(threadPrefix, boundedTask), "threadFactory returned null");
                    thread.setDaemon(true);
                    thread.start();
                }
                if (handoff != null) {
                    handoff.admit();
                }
                permitTransferred = true;
                startGate.admit();
            } catch (TaskCancelledException | TimeoutException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new ExecutionException("Could not start bounded task", failure);
            } catch (Error failure) {
                throw failure;
            }

            try {
                long remainingNanos = deadlineNanos - nanoTime.getAsLong();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("operation deadline elapsed");
                }
                TaskOutcome<T> outcome = race.get(remainingNanos, TimeUnit.NANOSECONDS);
                if (outcome.cancelled()) {
                    TaskCancelledException cancellationFailure = new TaskCancelledException();
                    abandonTask(abandonmentHandler, lateFailure, execution, cancellationFailure);
                    throw cancellationFailure;
                }
                if (outcome.failure() != null) {
                    throw new ExecutionException(outcome.failure());
                }
                return outcome.value();
            } catch (TimeoutException | InterruptedException failure) {
                abandonTask(abandonmentHandler, lateFailure, execution, failure);
                throw failure;
            }
        } finally {
            if (cancellationRegistration != null) {
                cancellationRegistration.close();
            }
            if (!permitTransferred) {
                if (startGate != null) {
                    startGate.reject();
                }
                if (handoff != null) {
                    handoff.rejectIfWaiting();
                }
                permit.close();
            }
        }
    }

    private static void abandonTask(
            TaskAbandonmentHandler abandonmentHandler,
            LateFailurePublication lateFailure,
            TaskExecution execution,
            Throwable failure) {
        try {
            abandonmentHandler.beforeInterrupt(failure);
        } finally {
            lateFailure.abandon();
            execution.interrupt();
        }
    }

    static void reportLateFailure(Thread sourceThread, Throwable failure) {
        if (failure instanceof RuntimeException || failure instanceof Error) {
            BoundedFailureReporter.shared().report(sourceThread, failure);
        }
    }

    private static boolean tryRename(Thread thread, String name) {
        try {
            thread.setName(name);
            return true;
        } catch (SecurityException denied) {
            return false;
        }
    }

    static boolean start(Limiter limiter, String threadPrefix, Runnable task) {
        Objects.requireNonNull(limiter, "limiter");
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(task, "task");

        Permit permit = limiter.tryAcquire();
        if (permit == null) {
            return false;
        }
        StartGate startGate = new StartGate();
        try {
            Runnable boundedTask = () -> {
                if (!startGate.awaitAdmission()) {
                    return;
                }
                try (permit) {
                    task.run();
                }
            };
            Thread thread = Threading.unstartedPlatformNonInheriting(
                    threadPrefix + Integer.toUnsignedString(System.identityHashCode(startGate)), boundedTask);
            thread.start();
            startGate.admit();
            return true;
        } catch (RuntimeException | Error failure) {
            startGate.reject();
            permit.close();
            throw failure;
        }
    }

    @FunctionalInterface
    interface Task<T> {

        T run() throws Throwable;
    }

    @FunctionalInterface
    interface TaskThreadFactory {

        Thread unstarted(String threadPrefix, Runnable task);
    }

    @FunctionalInterface
    interface TaskStarter {

        /** Accepts either task execution or later rejection as the sole owner of permit settlement. */
        void start(String threadPrefix, Runnable task, TaskRejection rejection);
    }

    @FunctionalInterface
    interface TaskRejection {

        void reject(Throwable failure);
    }

    @FunctionalInterface
    interface LateFatalHandler {

        void handle(Thread thread, Error failure);
    }

    @FunctionalInterface
    interface LateFailureHandler {

        void handle(Thread thread, Throwable failure);
    }

    @FunctionalInterface
    interface TaskAbandonmentHandler {

        void beforeInterrupt(Throwable failure);
    }

    enum TaskPhase {
        WAITING_FOR_ADMISSION,
        REJECTED_BEFORE_ADMISSION,
        ADMITTED,
        STARTED,
        SIDE_EFFECT_STARTED
    }

    /**
     * Records the one-way ownership transfer from the capacity waiter to a task thread. Only rejection before admission
     * proves that no task exists and no later side effect is possible; every phase from {@link TaskPhase#ADMITTED}
     * onward must be treated as potentially concurrent with the caller's timeout.
     */
    static final class TaskHandoff {

        private final AtomicReference<TaskPhase> phase = new AtomicReference<>(TaskPhase.WAITING_FOR_ADMISSION);

        private void rejectBeforeAdmission() {
            transition(TaskPhase.WAITING_FOR_ADMISSION, TaskPhase.REJECTED_BEFORE_ADMISSION);
        }

        private void rejectIfWaiting() {
            phase.compareAndSet(TaskPhase.WAITING_FOR_ADMISSION, TaskPhase.REJECTED_BEFORE_ADMISSION);
        }

        private void admit() {
            transition(TaskPhase.WAITING_FOR_ADMISSION, TaskPhase.ADMITTED);
        }

        private void start() {
            transition(TaskPhase.ADMITTED, TaskPhase.STARTED);
        }

        void markSideEffectStarted() {
            transition(TaskPhase.STARTED, TaskPhase.SIDE_EFFECT_STARTED);
        }

        boolean retrySafe() {
            return phase.get() == TaskPhase.REJECTED_BEFORE_ADMISSION;
        }

        boolean mayRun() {
            TaskPhase current = phase.get();
            return current == TaskPhase.ADMITTED
                    || current == TaskPhase.STARTED
                    || current == TaskPhase.SIDE_EFFECT_STARTED;
        }

        TaskPhase phase() {
            return phase.get();
        }

        private void transition(TaskPhase expected, TaskPhase next) {
            if (!phase.compareAndSet(expected, next)) {
                throw new IllegalStateException("task handoff cannot transition from " + phase.get() + " to " + next);
            }
        }
    }

    /** Keeps a newly started thread side-effect free until admission and permit ownership transfer are committed. */
    private static final class StartGate {

        private GateState state = GateState.PENDING;

        private synchronized void admit() {
            state = GateState.ADMITTED;
            notifyAll();
        }

        private synchronized void reject() {
            if (state == GateState.PENDING) {
                state = GateState.REJECTED;
                notifyAll();
            }
        }

        private synchronized boolean awaitAdmission() {
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
            return state == GateState.ADMITTED;
        }
    }

    private enum GateState {
        PENDING,
        ADMITTED,
        REJECTED
    }

    private static final class LateFailurePublication {

        private final LateFailureHandler handler;
        private final AtomicReference<Thread> taskThread = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();

        private LateFailurePublication(LateFailureHandler handler) {
            this.handler = handler;
        }

        private void bind(Thread thread) {
            if (!taskThread.compareAndSet(null, Objects.requireNonNull(thread, "thread"))) {
                throw new IllegalStateException("late fatal publication is already bound");
            }
        }

        private void record(Throwable taskFailure) {
            failure.compareAndSet(null, Objects.requireNonNull(taskFailure, "taskFailure"));
            publishIfReady();
        }

        private void abandon() {
            abandoned.set(true);
            publishIfReady();
        }

        private void publishIfReady() {
            Thread thread = taskThread.get();
            Throwable taskFailure = failure.get();
            if (thread != null && taskFailure != null && abandoned.get() && published.compareAndSet(false, true)) {
                try {
                    handler.handle(thread, taskFailure);
                } catch (Throwable reportingFailure) {
                    BoundedFailureReporter.shared().report(thread, reportingFailure);
                }
            }
        }
    }

    private static final class TaskExecution {

        private final LateFailurePublication lateFailure;
        private Thread activeThread;
        private boolean interruptRequested;

        private TaskExecution(LateFailurePublication lateFailure) {
            this.lateFailure = lateFailure;
        }

        private synchronized void bind(Thread thread) {
            activeThread = Objects.requireNonNull(thread, "thread");
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

        private String taskName(String threadPrefix) {
            return threadPrefix + Integer.toUnsignedString(System.identityHashCode(this));
        }
    }

    static final class Limiter {

        private final int capacity;
        private final Semaphore permits;

        Limiter(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.capacity = capacity;
            permits = new Semaphore(capacity, true);
        }

        Permit acquire(long deadlineNanos) throws TimeoutException, InterruptedException {
            return acquire(deadlineNanos, System::nanoTime);
        }

        private Permit acquire(long deadlineNanos, LongSupplier nanoTime)
                throws TimeoutException, InterruptedException {
            long remainingNanos = deadlineNanos - nanoTime.getAsLong();
            if (remainingNanos <= 0 || !permits.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("timed operation capacity was not available before its deadline");
            }
            return new Permit(permits);
        }

        Permit acquire(long deadlineNanos, CancellationSignal cancellation)
                throws TimeoutException, InterruptedException, TaskCancelledException {
            return acquire(deadlineNanos, cancellation, System::nanoTime);
        }

        private Permit acquire(long deadlineNanos, CancellationSignal cancellation, LongSupplier nanoTime)
                throws TimeoutException, InterruptedException, TaskCancelledException {
            Objects.requireNonNull(cancellation, "cancellation");
            while (true) {
                cancellation.throwIfCancelled();
                long remainingNanos = deadlineNanos - nanoTime.getAsLong();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("timed operation capacity was not available before its deadline");
                }
                long waitNanos = Math.min(remainingNanos, CANCELLATION_POLL_NANOS);
                if (permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
                    Permit permit = new Permit(permits);
                    try {
                        cancellation.throwIfCancelled();
                        return permit;
                    } catch (TaskCancelledException cancelled) {
                        permit.close();
                        throw cancelled;
                    }
                }
            }
        }

        Permit tryAcquire() {
            return permits.tryAcquire() ? new Permit(permits) : null;
        }

        int availablePermits() {
            return permits.availablePermits();
        }

        int capacity() {
            return capacity;
        }
    }

    static final class Permit implements AutoCloseable {

        private final Semaphore permits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }

    static final class CancellationSignal {

        private final Object monitor = new Object();
        private final ArrayList<Runnable> listeners = new ArrayList<>();
        private volatile boolean cancelled;

        boolean cancel() {
            List<Runnable> pending;
            synchronized (monitor) {
                if (cancelled) {
                    return false;
                }
                cancelled = true;
                pending = List.copyOf(listeners);
                listeners.clear();
            }
            pending.forEach(Runnable::run);
            return true;
        }

        CancellationRegistration register(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            boolean runImmediately;
            synchronized (monitor) {
                runImmediately = cancelled;
                if (!runImmediately) {
                    listeners.add(listener);
                }
            }
            if (runImmediately) {
                listener.run();
            }
            return new CancellationRegistration(this, listener, !runImmediately);
        }

        private void throwIfCancelled() throws TaskCancelledException {
            if (cancelled) {
                throw new TaskCancelledException();
            }
        }

        private void unregister(Runnable listener) {
            synchronized (monitor) {
                listeners.remove(listener);
            }
        }

        int listenerCountForTest() {
            synchronized (monitor) {
                return listeners.size();
            }
        }
    }

    static final class CancellationRegistration implements AutoCloseable {

        private final CancellationSignal owner;
        private final Runnable listener;
        private final AtomicBoolean registered;

        private CancellationRegistration(CancellationSignal owner, Runnable listener, boolean registered) {
            this.owner = owner;
            this.listener = listener;
            this.registered = new AtomicBoolean(registered);
        }

        @Override
        public void close() {
            if (registered.compareAndSet(true, false)) {
                owner.unregister(listener);
            }
        }
    }

    static final class TaskCancelledException extends Exception {

        private static final long serialVersionUID = 1L;

        private TaskCancelledException() {
            super("bounded task was cancelled");
        }
    }

    private record TaskOutcome<T>(T value, Throwable failure, boolean cancelled) {

        private static <T> TaskOutcome<T> completed(T value) {
            return new TaskOutcome<>(value, null, false);
        }

        private static <T> TaskOutcome<T> failed(Throwable failure) {
            return new TaskOutcome<>(null, Objects.requireNonNull(failure, "failure"), false);
        }

        private static <T> TaskOutcome<T> cancelledOutcome() {
            return new TaskOutcome<>(null, null, true);
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.Threading;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final TaskAbandonmentHandler NO_OP_ABANDONMENT = failure -> {};
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong();
    private static final TaskStarter FRESH_TASK_STARTER = (threadPrefix, task, rejection) ->
            Threading.unstartedPlatformNonInheriting(threadPrefix + TASK_SEQUENCE.getAndIncrement(), task)
                    .start();

    private BoundedTaskRunner() {}

    static <T> T run(BoundedTaskLimiter limiter, String threadPrefix, long deadlineNanos, Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        try {
            return execute(
                    limiter,
                    threadPrefix,
                    deadlineNanos,
                    BoundedTaskCancellation.none(),
                    BoundedTaskRunner::reportLateFailure,
                    NO_OP_ABANDONMENT,
                    BoundedTaskHandoff.untracked(),
                    FRESH_TASK_STARTER,
                    System::nanoTime,
                    task);
        } catch (TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    static <T> T run(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return execute(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation(cancellation),
                BoundedTaskRunner::reportLateFailure,
                NO_OP_ABANDONMENT,
                BoundedTaskHandoff.untracked(),
                FRESH_TASK_STARTER,
                System::nanoTime,
                task);
    }

    static <T> T run(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFatalHandler lateFatalHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        Objects.requireNonNull(lateFatalHandler, "lateFatalHandler");
        return execute(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation(cancellation),
                (thread, failure) -> {
                    if (failure instanceof Error error) {
                        lateFatalHandler.handle(thread, error);
                    }
                },
                NO_OP_ABANDONMENT,
                BoundedTaskHandoff.untracked(),
                FRESH_TASK_STARTER,
                System::nanoTime,
                task);
    }

    static <T> T run(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationToken cancellation,
            LateFatalHandler lateFatalHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return run(
                limiter,
                threadPrefix,
                deadlineNanos,
                Objects.requireNonNull(cancellation, "cancellation").owner,
                lateFatalHandler,
                task);
    }

    static <T> T runTracked(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskHandoff handoff,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        return runTracked(limiter, threadPrefix, deadlineNanos, handoff, FRESH_TASK_STARTER, System::nanoTime, task);
    }

    static <T> T runReportingLateFailure(
            BoundedTaskLimiter limiter,
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
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFailureHandler lateFailureHandler,
            TaskAbandonmentHandler abandonmentHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return execute(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation(cancellation),
                lateFailureHandler,
                abandonmentHandler,
                BoundedTaskHandoff.untracked(),
                FRESH_TASK_STARTER,
                System::nanoTime,
                task);
    }

    static <T> T runReportingLateFailure(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            LateFailureHandler lateFailureHandler,
            TaskStarter taskStarter,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return execute(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation(cancellation),
                lateFailureHandler,
                NO_OP_ABANDONMENT,
                BoundedTaskHandoff.untracked(),
                taskStarter,
                System::nanoTime,
                task);
    }

    private static <T> T runTracked(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskHandoff handoff,
            TaskStarter taskStarter,
            LongSupplier nanoTime,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        Objects.requireNonNull(handoff, "handoff");
        try {
            return execute(
                    limiter,
                    threadPrefix,
                    deadlineNanos,
                    BoundedTaskCancellation.none(),
                    BoundedTaskRunner::reportLateFailure,
                    NO_OP_ABANDONMENT,
                    handoff,
                    taskStarter,
                    nanoTime,
                    task);
        } catch (TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    private static <T> T execute(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskCancellation cancellation,
            LateFailureHandler lateFailureHandler,
            TaskAbandonmentHandler abandonmentHandler,
            BoundedTaskHandoff handoff,
            TaskStarter taskStarter,
            LongSupplier nanoTime,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return BoundedTaskExecution.run(new BoundedTaskExecution.Request<>(
                limiter,
                threadPrefix,
                deadlineNanos,
                cancellation,
                lateFailureHandler,
                abandonmentHandler,
                handoff,
                taskStarter,
                nanoTime,
                task));
    }

    private static BoundedTaskCancellation cancellation(CancellationSignal signal) {
        return signal == null ? BoundedTaskCancellation.none() : signal;
    }

    static void reportLateFailure(Thread sourceThread, Throwable failure) {
        if (failure instanceof RuntimeException || failure instanceof Error) {
            BoundedFailureReporter.shared().report(sourceThread, failure);
        }
    }

    @FunctionalInterface
    interface Task<T> {

        T run() throws Throwable;
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

    static final class CancellationSignal implements BoundedTaskCancellation {

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

        CancellationToken token() {
            return new CancellationToken(this);
        }

        @Override
        public BoundedTaskPermit acquire(BoundedTaskLimiter limiter, long deadlineNanos, LongSupplier nanoTime)
                throws TimeoutException, InterruptedException, TaskCancelledException {
            return limiter.acquire(deadlineNanos, this, nanoTime);
        }

        @Override
        public BoundedTaskCancellation.Registration register(Runnable listener) {
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
            return runImmediately ? () -> {} : () -> unregister(listener);
        }

        @Override
        public void throwIfCancelled() throws TaskCancelledException {
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

    static final class CancellationToken {

        private final CancellationSignal owner;

        private CancellationToken(CancellationSignal owner) {
            this.owner = owner;
        }
    }

    static final class TaskCancelledException extends Exception {

        private static final long serialVersionUID = 1L;

        TaskCancelledException() {
            super("bounded task was cancelled");
        }
    }
}

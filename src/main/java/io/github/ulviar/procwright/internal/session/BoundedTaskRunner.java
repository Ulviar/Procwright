/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Bounds timed operations whose implementation may ignore interruption.
 *
 * <p>Java cannot forcibly terminate arbitrary callback code. A timed-out task therefore retains its permit until the
 * task actually returns. This lets the caller observe its deadline while putting a hard upper bound on abandoned
 * library-managed tasks. Each default invocation uses a non-inheriting virtual thread on Java 24 or newer, where
 * monitor pinning no longer constrains bounded concurrency, and a fresh non-inheriting daemon platform thread on older
 * runtimes. A caller may supply a narrower lifecycle owner only when callback ownership itself provides the isolation
 * boundary.
 */
public final class BoundedTaskRunner {

    private static final TaskAbandonmentHandler NO_OP_ABANDONMENT = failure -> {};
    private static final TaskStarter DEFAULT_TASK_STARTER =
            (threadPrefix, task, rejection) -> Threading.start(threadPrefix, task);

    private BoundedTaskRunner() {}

    static <T> T run(BoundedTaskLimiter limiter, String threadPrefix, long deadlineNanos, Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        try {
            return execute(
                    limiter,
                    threadPrefix,
                    deadlineNanos,
                    BoundedTaskCancellation.none(),
                    NO_OP_ABANDONMENT,
                    BoundedTaskHandoff.untracked(),
                    DEFAULT_TASK_STARTER,
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
                Objects.requireNonNull(cancellation, "cancellation"),
                NO_OP_ABANDONMENT,
                BoundedTaskHandoff.untracked(),
                DEFAULT_TASK_STARTER,
                System::nanoTime,
                task);
    }

    static <T> T run(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationToken cancellation,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return execute(
                limiter,
                threadPrefix,
                deadlineNanos,
                Objects.requireNonNull(cancellation, "cancellation").owner,
                NO_OP_ABANDONMENT,
                BoundedTaskHandoff.untracked(),
                DEFAULT_TASK_STARTER,
                System::nanoTime,
                task);
    }

    static <T> T runTracked(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskHandoff handoff,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        return runTracked(limiter, threadPrefix, deadlineNanos, handoff, DEFAULT_TASK_STARTER, System::nanoTime, task);
    }

    static <T> T runWithAbandonment(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            TaskAbandonmentHandler abandonmentHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return execute(
                limiter,
                threadPrefix,
                deadlineNanos,
                Objects.requireNonNull(cancellation, "cancellation"),
                abandonmentHandler,
                BoundedTaskHandoff.untracked(),
                DEFAULT_TASK_STARTER,
                System::nanoTime,
                task);
    }

    static <T> T runWithStarter(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            TaskStarter taskStarter,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return execute(
                limiter,
                threadPrefix,
                deadlineNanos,
                Objects.requireNonNull(cancellation, "cancellation"),
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
                abandonmentHandler,
                handoff,
                taskStarter,
                nanoTime,
                task));
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one potentially blocking callback without allowing it to hold its caller past the deadline.
 *
 * <p>Java cannot stop arbitrary callback code. Cancellable scenario owners can select their terminal state through the
 * abandonment handler before this runner interrupts the callback thread. Other owners receive the timeout or
 * interruption immediately after the interrupt request. A non-cooperative callback may therefore outlive its caller.
 */
final class TimedTaskRunner {

    private static final AbandonmentHandler NO_OP_ABANDONMENT = failure -> {};

    private TimedTaskRunner() {}

    static <T> T run(String threadPrefix, long deadlineNanos, Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        try {
            return execute(threadPrefix, deadlineNanos, null, NO_OP_ABANDONMENT, new TaskStart(), task);
        } catch (TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    static <T> T runTracked(String threadPrefix, long deadlineNanos, TaskStart start, Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        try {
            return execute(threadPrefix, deadlineNanos, null, NO_OP_ABANDONMENT, start, task);
        } catch (TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    static <T> T runCancellable(
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            AbandonmentHandler abandonmentHandler,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        return execute(
                threadPrefix,
                deadlineNanos,
                Objects.requireNonNull(cancellation, "cancellation"),
                abandonmentHandler,
                new TaskStart(),
                task);
    }

    private static <T> T execute(
            String threadPrefix,
            long deadlineNanos,
            CancellationSignal cancellation,
            AbandonmentHandler abandonmentHandler,
            TaskStart start,
            Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(abandonmentHandler, "abandonmentHandler");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(task, "task");

        CompletableFuture<Outcome<T>> outcome = new CompletableFuture<>();
        TaskControl control = new TaskControl();
        CancellationSignal.Registration registration = cancellation == null
                ? null
                : cancellation.register(() -> {
                    control.abandon();
                    outcome.complete(Outcome.cancelledOutcome());
                });
        try (registration) {
            if (cancellation != null) {
                cancellation.throwIfCancelled();
            }
            ensureBeforeDeadline(deadlineNanos, "operation deadline elapsed before task start");

            Thread thread;
            try {
                thread = Threading.unstarted(threadPrefix, () -> runTask(task, deadlineNanos, outcome, control));
            } catch (RuntimeException failure) {
                throw new ExecutionException("Could not create timed task thread", failure);
            }
            control.bind(thread);
            if (cancellation != null) {
                cancellation.throwIfCancelled();
            }
            ensureBeforeDeadline(deadlineNanos, "operation deadline elapsed before task start");
            try {
                thread.start();
            } catch (RuntimeException failure) {
                throw new ExecutionException("Could not start timed task", failure);
            }
            start.markStarted();
            return await(deadlineNanos, outcome, abandonmentHandler, control);
        }
    }

    private static <T> void runTask(
            Task<T> task, long deadlineNanos, CompletableFuture<Outcome<T>> outcome, TaskControl control) {
        if (!control.begin(deadlineNanos)) {
            control.clear(Thread.currentThread());
            return;
        }
        try {
            outcome.complete(Outcome.completed(task.run()));
        } catch (Throwable failure) {
            outcome.complete(Outcome.failed(failure));
        } finally {
            control.clear(Thread.currentThread());
        }
    }

    private static <T> T await(
            long deadlineNanos,
            CompletableFuture<Outcome<T>> future,
            AbandonmentHandler abandonmentHandler,
            TaskControl control)
            throws TimeoutException, InterruptedException, ExecutionException, TaskCancelledException {
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("operation deadline elapsed");
            }
            Outcome<T> outcome = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (outcome.cancelled()) {
                TaskCancelledException cancellation = new TaskCancelledException();
                abandon(abandonmentHandler, control, cancellation);
                throw cancellation;
            }
            if (outcome.failure() != null) {
                throw new ExecutionException(outcome.failure());
            }
            return outcome.value();
        } catch (TimeoutException | InterruptedException failure) {
            abandon(abandonmentHandler, control, failure);
            throw failure;
        }
    }

    private static void abandon(AbandonmentHandler abandonmentHandler, TaskControl control, Throwable failure) {
        try {
            abandonmentHandler.beforeInterrupt(failure);
        } finally {
            control.interrupt();
        }
    }

    private static void ensureBeforeDeadline(long deadlineNanos, String message) throws TimeoutException {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw new TimeoutException(message);
        }
    }

    @FunctionalInterface
    interface Task<T> {

        T run() throws Throwable;
    }

    @FunctionalInterface
    interface AbandonmentHandler {

        void beforeInterrupt(Throwable failure);
    }

    /** One terminal cancellation signal for sequential callbacks owned by a scenario. */
    static final class CancellationSignal {

        private Registration active;
        private volatile boolean cancelled;

        boolean cancel() {
            Registration pending;
            synchronized (this) {
                if (cancelled) {
                    return false;
                }
                cancelled = true;
                pending = active;
                active = null;
            }
            if (pending != null) {
                pending.listener.run();
            }
            return true;
        }

        Registration register(Runnable listener) {
            Registration registration = new Registration(Objects.requireNonNull(listener, "listener"));
            boolean runImmediately;
            synchronized (this) {
                runImmediately = cancelled;
                if (!runImmediately) {
                    if (active != null) {
                        throw new IllegalStateException("A callback is already registered for cancellation");
                    }
                    active = registration;
                }
            }
            if (runImmediately) {
                listener.run();
            }
            return registration;
        }

        void throwIfCancelled() throws TaskCancelledException {
            if (cancelled) {
                throw new TaskCancelledException();
            }
        }

        final class Registration implements AutoCloseable {

            private final Runnable listener;

            private Registration(Runnable listener) {
                this.listener = listener;
            }

            @Override
            public void close() {
                synchronized (CancellationSignal.this) {
                    if (active == this) {
                        active = null;
                    }
                }
            }
        }
    }

    static final class TaskCancelledException extends Exception {

        private static final long serialVersionUID = 1L;

        private TaskCancelledException() {
            super("timed task was cancelled");
        }
    }

    static final class TaskControl {

        private Thread thread;
        private State state = State.PENDING;

        synchronized void bind(Thread candidate) {
            thread = Objects.requireNonNull(candidate, "candidate");
        }

        synchronized boolean begin(long deadlineNanos) {
            if (state != State.PENDING) {
                return false;
            }
            if (deadlineNanos - System.nanoTime() <= 0) {
                state = State.ABANDONED;
                return false;
            }
            state = State.RUNNING;
            return true;
        }

        synchronized void abandon() {
            if (state == State.PENDING) {
                state = State.ABANDONED;
            }
        }

        synchronized void clear(Thread candidate) {
            if (thread == candidate) {
                thread = null;
            }
        }

        void interrupt() {
            Thread target;
            synchronized (this) {
                abandon();
                target = thread;
            }
            if (target != null) {
                target.interrupt();
            }
        }

        private enum State {
            PENDING,
            RUNNING,
            ABANDONED
        }
    }

    private record Outcome<T>(T value, Throwable failure, boolean cancelled) {

        private static <T> Outcome<T> completed(T value) {
            return new Outcome<>(value, null, false);
        }

        private static <T> Outcome<T> failed(Throwable failure) {
            return new Outcome<>(null, Objects.requireNonNull(failure, "failure"), false);
        }

        private static <T> Outcome<T> cancelledOutcome() {
            return new Outcome<>(null, null, true);
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Owns the lifecycle and settlement of one admitted bounded task. */
final class BoundedTaskExecution {

    private BoundedTaskExecution() {}

    static <T> T run(Request<T> request)
            throws TimeoutException, InterruptedException, ExecutionException,
                    BoundedTaskRunner.TaskCancelledException {
        BoundedTaskPermit permit;
        try {
            permit = request.cancellation().acquire(request.limiter(), request.deadlineNanos(), request.nanoTime());
        } catch (TimeoutException | InterruptedException | BoundedTaskRunner.TaskCancelledException failure) {
            request.handoff().rejectBeforeAdmission();
            throw failure;
        }

        boolean permitTransferred = false;
        BoundedTaskStartGate startGate = null;
        BoundedTaskCancellation.Registration cancellationRegistration = () -> {};
        try {
            startGate = new BoundedTaskStartGate();
            CompletableFuture<TaskOutcome<T>> completion = new CompletableFuture<>();
            CompletableFuture<TaskOutcome<T>> race = new CompletableFuture<>();
            completion.thenAccept(race::complete);
            cancellationRegistration =
                    request.cancellation().register(() -> race.complete(TaskOutcome.cancelledOutcome()));
            LateFailurePublication lateFailure = new LateFailurePublication(request.lateFailureHandler());
            ActiveTask activeTask = new ActiveTask(lateFailure);
            AtomicBoolean taskClaimed = new AtomicBoolean();
            try {
                request.cancellation().throwIfCancelled();
                BoundedTaskStartGate taskStartGate = startGate;
                Runnable boundedTask = () -> {
                    if (!taskClaimed.compareAndSet(false, true) || !taskStartGate.awaitAdmission()) {
                        return;
                    }
                    Thread current = Thread.currentThread();
                    String previousName = current.getName();
                    boolean renamed = tryRename(current, activeTask.taskName(request.threadPrefix()));
                    activeTask.bind(current);
                    TaskOutcome<T> outcome;
                    Throwable taskFailure = null;
                    try (permit) {
                        try {
                            outcome = TaskOutcome.completed(request.task().run());
                        } catch (Throwable failure) {
                            taskFailure = failure;
                            outcome = TaskOutcome.failed(failure);
                        }
                    } finally {
                        activeTask.unbind(current);
                        if (renamed) {
                            tryRename(current, previousName);
                        }
                    }
                    if (taskFailure != null) {
                        lateFailure.record(taskFailure);
                    }
                    completion.complete(outcome);
                };
                BoundedTaskRunner.TaskRejection taskRejection = failure -> {
                    Objects.requireNonNull(failure, "failure");
                    if (!taskClaimed.compareAndSet(false, true) || !taskStartGate.awaitAdmission()) {
                        return;
                    }
                    Thread current = Thread.currentThread();
                    activeTask.bind(current);
                    try (permit) {
                        // The owner accepted the task but could not provide a replacement execution thread.
                    } finally {
                        activeTask.unbind(current);
                    }
                    lateFailure.record(failure);
                    completion.complete(TaskOutcome.failed(failure));
                };
                request.cancellation().throwIfCancelled();
                if (request.deadlineNanos() - request.nanoTime().getAsLong() <= 0) {
                    throw new TimeoutException("operation deadline elapsed before task start");
                }
                start(request, boundedTask, taskRejection, activeTask);
                request.handoff().admit();
                permitTransferred = true;
                startGate.admit();
            } catch (BoundedTaskRunner.TaskCancelledException | TimeoutException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new ExecutionException("Could not start bounded task", failure);
            } catch (Error failure) {
                throw failure;
            }

            return awaitOutcome(request, race, lateFailure, activeTask);
        } finally {
            cancellationRegistration.close();
            if (!permitTransferred) {
                if (startGate != null) {
                    startGate.reject();
                }
                request.handoff().rejectIfWaiting();
                permit.close();
            }
        }
    }

    private static <T> void start(
            Request<T> request,
            Runnable boundedTask,
            BoundedTaskRunner.TaskRejection taskRejection,
            ActiveTask activeTask) {
        request.owner()
                .start(request.threadPrefix(), activeTask.taskName(request.threadPrefix()), boundedTask, taskRejection);
    }

    private static <T> T awaitOutcome(
            Request<T> request,
            CompletableFuture<TaskOutcome<T>> race,
            LateFailurePublication lateFailure,
            ActiveTask activeTask)
            throws TimeoutException, InterruptedException, ExecutionException,
                    BoundedTaskRunner.TaskCancelledException {
        try {
            long remainingNanos = request.deadlineNanos() - request.nanoTime().getAsLong();
            if (remainingNanos <= 0) {
                throw new TimeoutException("operation deadline elapsed");
            }
            TaskOutcome<T> outcome = race.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (outcome.cancelled()) {
                BoundedTaskRunner.TaskCancelledException cancellation = new BoundedTaskRunner.TaskCancelledException();
                abandon(request.abandonmentHandler(), lateFailure, activeTask, cancellation);
                throw cancellation;
            }
            if (outcome.failure() != null) {
                throw new ExecutionException(outcome.failure());
            }
            return outcome.value();
        } catch (TimeoutException | InterruptedException failure) {
            abandon(request.abandonmentHandler(), lateFailure, activeTask, failure);
            throw failure;
        }
    }

    private static void abandon(
            BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
            LateFailurePublication lateFailure,
            ActiveTask activeTask,
            Throwable failure) {
        try {
            abandonmentHandler.beforeInterrupt(failure);
        } finally {
            lateFailure.abandon();
            activeTask.interrupt();
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

    record Request<T>(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskCancellation cancellation,
            BoundedTaskRunner.LateFailureHandler lateFailureHandler,
            BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
            BoundedTaskHandoff handoff,
            BoundedTaskOwner owner,
            LongSupplier nanoTime,
            BoundedTaskRunner.Task<T> task) {

        Request {
            Objects.requireNonNull(limiter, "limiter");
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(cancellation, "cancellation");
            Objects.requireNonNull(lateFailureHandler, "lateFailureHandler");
            Objects.requireNonNull(abandonmentHandler, "abandonmentHandler");
            Objects.requireNonNull(handoff, "handoff");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(nanoTime, "nanoTime");
            Objects.requireNonNull(task, "task");
        }
    }

    private static final class LateFailurePublication {

        private final BoundedTaskRunner.LateFailureHandler handler;
        private final AtomicReference<Thread> taskThread = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();

        private LateFailurePublication(BoundedTaskRunner.LateFailureHandler handler) {
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

    private static final class ActiveTask {

        private final LateFailurePublication lateFailure;
        private Thread activeThread;
        private boolean interruptRequested;

        private ActiveTask(LateFailurePublication lateFailure) {
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

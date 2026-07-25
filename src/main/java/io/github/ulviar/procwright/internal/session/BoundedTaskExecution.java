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
        BoundedTaskCancellation.Registration cancellationRegistration = () -> {};
        try {
            CompletableFuture<TaskOutcome<T>> settlement = new CompletableFuture<>();
            cancellationRegistration =
                    request.cancellation().register(() -> settlement.complete(TaskOutcome.cancelledOutcome()));
            LateFailurePublication lateFailure = new LateFailurePublication(request.lateFailureHandler());
            ActiveTask activeTask = new ActiveTask();
            try {
                request.cancellation().throwIfCancelled();
                Runnable boundedTask = () -> {
                    if (!request.handoff().claimAndAwaitAdmission()) {
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
                        lateFailure.record(current, taskFailure);
                    }
                    settlement.complete(outcome);
                };
                BoundedTaskRunner.TaskRejection taskRejection = failure -> {
                    Objects.requireNonNull(failure, "failure");
                    if (!request.handoff().claimAndAwaitAdmission()) {
                        return;
                    }
                    Thread current = Thread.currentThread();
                    activeTask.bind(current);
                    try (permit) {
                        // The owner accepted the task but could not provide a replacement execution thread.
                    } finally {
                        activeTask.unbind(current);
                    }
                    lateFailure.record(current, failure);
                    settlement.complete(TaskOutcome.failed(failure));
                };
                request.cancellation().throwIfCancelled();
                if (request.deadlineNanos() - request.nanoTime().getAsLong() <= 0) {
                    throw new TimeoutException("operation deadline elapsed before task start");
                }
                start(request, boundedTask, taskRejection, activeTask);
                request.handoff().admit();
                permitTransferred = true;
            } catch (BoundedTaskRunner.TaskCancelledException | TimeoutException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new ExecutionException("Could not start bounded task", failure);
            } catch (Error failure) {
                throw failure;
            }

            return awaitOutcome(request, settlement, lateFailure, activeTask);
        } finally {
            cancellationRegistration.close();
            if (!permitTransferred) {
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
        private final AtomicReference<TaskFailure> failure = new AtomicReference<>();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();

        private LateFailurePublication(BoundedTaskRunner.LateFailureHandler handler) {
            this.handler = handler;
        }

        private void record(Thread taskThread, Throwable taskFailure) {
            failure.compareAndSet(
                    null,
                    new TaskFailure(
                            Objects.requireNonNull(taskThread, "taskThread"),
                            Objects.requireNonNull(taskFailure, "taskFailure")));
            publishIfReady();
        }

        private void abandon() {
            abandoned.set(true);
            publishIfReady();
        }

        private void publishIfReady() {
            TaskFailure taskFailure = failure.get();
            if (taskFailure != null && abandoned.get() && published.compareAndSet(false, true)) {
                try {
                    handler.handle(taskFailure.thread(), taskFailure.failure());
                } catch (Throwable reportingFailure) {
                    BoundedFailureReporter.shared().report(taskFailure.thread(), reportingFailure);
                }
            }
        }

        private record TaskFailure(Thread thread, Throwable failure) {}
    }

    private static final class ActiveTask {

        private Thread activeThread;
        private boolean interruptRequested;

        private synchronized void bind(Thread thread) {
            activeThread = Objects.requireNonNull(thread, "thread");
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

final class BoundedTaskTestSupport {

    private BoundedTaskTestSupport() {}

    static <T> T runTracked(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskHandoff handoff,
            BoundedTaskRunner.Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        return BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task);
    }

    static <T> T runTracked(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskHandoff handoff,
            TaskThreadFactory threadFactory,
            BoundedTaskRunner.Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        return runTracked(limiter, threadPrefix, deadlineNanos, handoff, threadFactory, System::nanoTime, task);
    }

    static <T> T runTracked(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskHandoff handoff,
            TaskThreadFactory threadFactory,
            LongSupplier nanoTime,
            BoundedTaskRunner.Task<T> task)
            throws TimeoutException, InterruptedException, ExecutionException {
        BoundedTaskRunner.TaskStarter taskStarter = (prefix, boundedTask, rejection) -> {
            Thread thread =
                    Objects.requireNonNull(threadFactory.unstarted(prefix, boundedTask), "threadFactory returned null");
            thread.setDaemon(true);
            thread.start();
        };
        try {
            return BoundedTaskExecution.run(new BoundedTaskExecution.Request<>(
                    limiter,
                    threadPrefix,
                    deadlineNanos,
                    BoundedTaskCancellation.none(),
                    BoundedTaskRunner::reportLateFailure,
                    failure -> {},
                    handoff,
                    taskStarter,
                    nanoTime,
                    task));
        } catch (BoundedTaskRunner.TaskCancelledException impossible) {
            throw new AssertionError("uncancellable task was cancelled", impossible);
        }
    }

    @FunctionalInterface
    interface TaskThreadFactory {

        Thread unstarted(String threadPrefix, Runnable task);
    }
}

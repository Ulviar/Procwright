/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Dispatches external callbacks on fresh threads behind a hard active-and-pending bound. */
final class BoundedIsolatedTaskDispatcher {

    private final int workerCapacity;
    private final int queueCapacity;
    private final ArrayDeque<PendingTask> pending = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ClassLoader contextClassLoader = BoundedIsolatedTaskDispatcher.class.getClassLoader();
    private int active;

    BoundedIsolatedTaskDispatcher(int workerCapacity, int queueCapacity) {
        if (workerCapacity <= 0) {
            throw new IllegalArgumentException("workerCapacity must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.workerCapacity = workerCapacity;
        this.queueCapacity = queueCapacity;
    }

    boolean execute(String threadPrefix, Runnable task) {
        Objects.requireNonNull(task, "task");
        return executeRequeueing(
                threadPrefix,
                () -> {
                    task.run();
                    return false;
                },
                ignored -> {});
    }

    boolean executeRequeueing(String threadPrefix, RequeueingTask task, Consumer<Throwable> rejection) {
        PendingTask accepted = new PendingTask(
                Objects.requireNonNull(threadPrefix, "threadPrefix"),
                Objects.requireNonNull(task, "task"),
                Objects.requireNonNull(rejection, "rejection"));
        synchronized (this) {
            if (active == workerCapacity) {
                if (pending.size() == queueCapacity) {
                    return false;
                }
                pending.addLast(accepted);
                return true;
            }
            active++;
        }
        try {
            start(accepted);
            return true;
        } catch (RuntimeException | Error startFailure) {
            reject(accepted, startFailure);
            startReplacement(releaseFailedStart());
            throw startFailure;
        }
    }

    private void start(PendingTask task) {
        Thread thread =
                Threading.unstartedPlatformNonInheriting(task.threadPrefix() + sequence.getAndIncrement(), () -> {
                    boolean requeue = false;
                    try {
                        requeue = task.task().run();
                    } finally {
                        taskCompleted(requeue ? task : null);
                    }
                });
        thread.setContextClassLoader(contextClassLoader);
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
    }

    private void startReplacement(PendingTask replacement) {
        PendingTask candidate = replacement;
        while (candidate != null) {
            try {
                start(candidate);
                return;
            } catch (RuntimeException | Error startFailure) {
                reject(candidate, startFailure);
                candidate = releaseFailedStart();
            }
        }
    }

    private synchronized PendingTask releaseFailedStart() {
        PendingTask replacement = pending.pollFirst();
        if (replacement == null) {
            active--;
        }
        return replacement;
    }

    private void taskCompleted(PendingTask continuation) {
        PendingTask replacement;
        synchronized (this) {
            if (continuation == null) {
                replacement = pending.pollFirst();
            } else {
                replacement = pending.pollFirst();
                if (replacement == null) {
                    replacement = continuation;
                } else {
                    pending.addLast(continuation);
                }
            }
            if (replacement == null) {
                active--;
                return;
            }
        }
        startReplacement(replacement);
    }

    private static void reject(PendingTask task, Throwable failure) {
        try {
            task.rejection().accept(failure);
        } catch (Throwable ignored) {
            // Rejection cleanup is best effort and cannot compromise dispatcher accounting.
        }
    }

    synchronized int activeCount() {
        return active;
    }

    synchronized int queuedCount() {
        return pending.size();
    }

    int workerCapacity() {
        return workerCapacity;
    }

    int queueCapacity() {
        return queueCapacity;
    }

    @FunctionalInterface
    interface RequeueingTask {

        boolean run();
    }

    private record PendingTask(String threadPrefix, RequeueingTask task, Consumer<Throwable> rejection) {}
}

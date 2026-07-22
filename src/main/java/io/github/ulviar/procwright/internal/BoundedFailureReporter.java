/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owns the hard global bound for best-effort external failure and completion notifications.
 *
 * <p>Lifecycle state, resource futures, and capacity permits must be settled before work is submitted here. A hostile
 * uncaught-exception handler or completion callback can then consume only this owner's fixed active slots and bounded
 * pending queue; it cannot retain the resource owner that produced the notification. Every active notification uses a
 * fresh non-inheriting daemon thread, so arbitrary callback thread-local state cannot cross notification ownership.
 * Uncaught-exception handlers receive a detached thread identity containing a snapshot of useful source metadata,
 * never the physical source thread that may already have returned to a reusable owner.
 *
 * @hidden
 */
public final class BoundedFailureReporter {

    public static final int SHARED_WORKER_CAPACITY = 4;
    public static final int SHARED_QUEUE_CAPACITY = 64;

    private static final BoundedFailureReporter SHARED =
            new BoundedFailureReporter(SHARED_WORKER_CAPACITY, SHARED_QUEUE_CAPACITY);
    private static final ThreadLocal<NotificationTarget> NOTIFICATION_TARGET = new ThreadLocal<>();

    private final int workerCapacity;
    private final int queueCapacity;
    private final BoundedIsolatedTaskDispatcher dispatcher;
    private final Object settlementMonitor = new Object();
    private long unsettledProducers;
    private long unsettledSubmissions;

    public BoundedFailureReporter(int workerCapacity, int queueCapacity) {
        if (workerCapacity <= 0) {
            throw new IllegalArgumentException("workerCapacity must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.workerCapacity = workerCapacity;
        this.queueCapacity = queueCapacity;
        dispatcher = new BoundedIsolatedTaskDispatcher(workerCapacity, queueCapacity);
    }

    public static BoundedFailureReporter shared() {
        return SHARED;
    }

    /** Submits one best-effort uncaught-failure notification without blocking the caller. */
    public boolean report(Thread sourceThread, Throwable failure) {
        Objects.requireNonNull(sourceThread, "sourceThread");
        Objects.requireNonNull(failure, "failure");
        NotificationTarget target = targetFor(sourceThread);
        return execute(target, () -> target.report(failure));
    }

    /** Captures the current notification destination before its source thread can terminate. */
    public static FailureTarget captureFailureTarget() {
        NotificationTarget inherited = NOTIFICATION_TARGET.get();
        NotificationTarget target = inherited == null ? targetFor(Thread.currentThread()) : inherited;
        return new FailureTarget(target);
    }

    /** Captures a specific source thread's current notification destination. */
    public static FailureTarget captureFailureTarget(Thread sourceThread) {
        return new FailureTarget(targetFor(Objects.requireNonNull(sourceThread, "sourceThread")));
    }

    /** Reports to a destination captured while the originating thread and its handler were still stable. */
    public boolean report(FailureTarget failureTarget, Throwable failure) {
        Objects.requireNonNull(failureTarget, "failureTarget");
        Objects.requireNonNull(failure, "failure");
        NotificationTarget target = failureTarget.target;
        return execute(target, () -> target.report(failure));
    }

    /** Submits one best-effort external callback without blocking the caller. */
    public boolean execute(Runnable callback) {
        return execute(Thread.currentThread(), callback);
    }

    public boolean execute(Thread sourceThread, Runnable callback) {
        Objects.requireNonNull(sourceThread, "sourceThread");
        Objects.requireNonNull(callback, "callback");
        return execute(targetFor(sourceThread), callback);
    }

    private boolean execute(NotificationTarget target, Runnable callback) {
        SubmissionSettlement settlement = beginSubmission();
        try {
            boolean accepted = dispatcher.executeRequeueing(
                    "procwright-failure-report-",
                    () -> {
                        NOTIFICATION_TARGET.set(target);
                        try {
                            callback.run();
                        } catch (Throwable failure) {
                            target.report(failure);
                        } finally {
                            NOTIFICATION_TARGET.remove();
                            settlement.complete();
                        }
                        return false;
                    },
                    ignored -> settlement.complete());
            if (!accepted) {
                settlement.complete();
            }
            return accepted;
        } catch (RuntimeException | Error startFailure) {
            settlement.complete();
            throw startFailure;
        }
    }

    /** Waits up to the supplied bound for all producers, dispatching, queued, and active submissions to settle. */
    boolean awaitSettlement(Duration timeout) throws InterruptedException {
        DurationSupport.requireNonNegative(timeout, "timeout");
        long deadline = DurationSupport.deadlineFromNow(timeout);
        synchronized (settlementMonitor) {
            while (unsettledProducers != 0 || unsettledSubmissions != 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                int remainderNanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(remainingMillis));
                settlementMonitor.wait(remainingMillis, remainderNanos);
            }
            return true;
        }
    }

    ProducerRegistration registerProducer() {
        ProducerRegistration registration = new ProducerRegistration();
        synchronized (settlementMonitor) {
            unsettledProducers++;
        }
        return registration;
    }

    private SubmissionSettlement beginSubmission() {
        SubmissionSettlement settlement = new SubmissionSettlement();
        synchronized (settlementMonitor) {
            unsettledSubmissions++;
        }
        return settlement;
    }

    public static Thread notificationSourceThread() {
        NotificationTarget target = NOTIFICATION_TARGET.get();
        return target == null ? Thread.currentThread() : target.source().detachedThread();
    }

    /** Runs internal lifecycle accounting while preserving the physical owner's failure-reporting destination. */
    public static void withFailureTarget(FailureTarget failureTarget, Runnable task) {
        Objects.requireNonNull(failureTarget, "failureTarget");
        Objects.requireNonNull(task, "task");
        NotificationTarget previous = NOTIFICATION_TARGET.get();
        NOTIFICATION_TARGET.set(failureTarget.target);
        try {
            task.run();
        } finally {
            if (previous == null) {
                NOTIFICATION_TARGET.remove();
            } else {
                NOTIFICATION_TARGET.set(previous);
            }
        }
    }

    private static NotificationTarget targetFor(Thread sourceThread) {
        NotificationTarget inherited = NOTIFICATION_TARGET.get();
        if (inherited != null) {
            return inherited;
        }
        Thread.UncaughtExceptionHandler handler = sourceThread.getUncaughtExceptionHandler();
        if (handler == null) {
            handler = Thread.getDefaultUncaughtExceptionHandler();
        }
        return new NotificationTarget(FailureSource.capture(sourceThread), handler);
    }

    public int activeCount() {
        return dispatcher.activeCount();
    }

    public int queuedCount() {
        return dispatcher.queuedCount();
    }

    public int workerCapacity() {
        return workerCapacity;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    final class ProducerRegistration {

        private boolean complete;

        private ProducerRegistration() {}

        void complete() {
            synchronized (this) {
                if (complete) {
                    return;
                }
                complete = true;
            }
            synchronized (settlementMonitor) {
                unsettledProducers--;
                notifyIfSettled();
            }
        }
    }

    private final class SubmissionSettlement {

        private boolean complete;

        private void complete() {
            synchronized (this) {
                if (complete) {
                    return;
                }
                complete = true;
            }
            synchronized (settlementMonitor) {
                unsettledSubmissions--;
                notifyIfSettled();
            }
        }
    }

    private void notifyIfSettled() {
        if (unsettledProducers == 0 && unsettledSubmissions == 0) {
            settlementMonitor.notifyAll();
        }
    }

    /** Opaque snapshot of an uncaught-failure destination. */
    public static final class FailureTarget {

        private final NotificationTarget target;

        private FailureTarget(NotificationTarget target) {
            this.target = target;
        }
    }

    private record NotificationTarget(FailureSource source, Thread.UncaughtExceptionHandler handler) {

        private NotificationTarget {
            Objects.requireNonNull(source, "source");
        }

        private void report(Throwable failure) {
            if (handler == null) {
                return;
            }
            try {
                handler.uncaughtException(source.detachedThread(), failure);
            } catch (Throwable ignored) {
                // The JVM also ignores failures thrown by an uncaught-exception handler.
            }
        }
    }

    private record FailureSource(String name, ClassLoader contextClassLoader, int priority, boolean daemon) {

        private static FailureSource capture(Thread sourceThread) {
            return new FailureSource(
                    sourceThread.getName(),
                    sourceThread.getContextClassLoader(),
                    sourceThread.getPriority(),
                    sourceThread.isDaemon());
        }

        private Thread detachedThread() {
            Thread detached = new Thread(null, () -> {}, name, 0, false);
            detached.setDaemon(daemon);
            detached.setContextClassLoader(contextClassLoader);
            detached.setPriority(priority);
            return detached;
        }
    }
}

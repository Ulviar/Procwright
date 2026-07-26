/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Runs potentially blocking process-stream closes with a bounded active set and backlog.
 *
 * <p>Admission owns only work that is already ready to run. Live process resources do not reserve dispatcher capacity.
 * A rejected close remains a best-effort cleanup failure and cannot delay a public process outcome. Physical close and
 * callbacks always run outside the dispatcher monitor.
 *
 * @hidden
 */
public final class BoundedCloseDispatcher {

    public static final int SHARED_ACTIVE_CAPACITY = 32;
    public static final int SHARED_PENDING_CAPACITY = 128;

    private static final BoundedCloseDispatcher SHARED =
            new BoundedCloseDispatcher(SHARED_ACTIVE_CAPACITY, SHARED_PENDING_CAPACITY);

    private final int activeCapacity;
    private final int maxOutstandingCapacity;
    private final ThreadStarter threadStarter;
    private final CloseNotificationPublisher notifications;
    private final Object lock = new Object();
    private final ArrayDeque<CloseExecution> pending;
    private int active;
    private int outstanding;

    public BoundedCloseDispatcher(int activeCapacity, int pendingCapacity) {
        this(activeCapacity, pendingCapacity, Threading::start);
    }

    public BoundedCloseDispatcher(int activeCapacity, int pendingCapacity, ThreadStarter threadStarter) {
        this(activeCapacity, pendingCapacity, threadStarter, BoundedFailureReporter.shared());
    }

    public BoundedCloseDispatcher(
            int activeCapacity,
            int pendingCapacity,
            ThreadStarter threadStarter,
            BoundedFailureReporter failureReporter) {
        this(activeCapacity, pendingCapacity, threadStarter, CloseNotificationPublisher.using(failureReporter));
    }

    BoundedCloseDispatcher(
            int activeCapacity,
            int pendingCapacity,
            ThreadStarter threadStarter,
            CloseNotificationPublisher notifications) {
        if (activeCapacity <= 0) {
            throw new IllegalArgumentException("activeCapacity must be positive");
        }
        if (pendingCapacity <= 0) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        try {
            maxOutstandingCapacity = Math.addExact(activeCapacity, pendingCapacity);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("dispatcher capacities are too large", overflow);
        }
        this.activeCapacity = activeCapacity;
        this.threadStarter = Objects.requireNonNull(threadStarter, "threadStarter");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        pending = new ArrayDeque<>(pendingCapacity);
    }

    public static BoundedCloseDispatcher shared() {
        return SHARED;
    }

    static CloseRequest ownedCloseRequest(
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> settlement,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        return new CloseRequest(closeable, threadPrefix, settlement, failureHandler, completionHandler);
    }

    void dispatch(CloseRequest request) {
        Objects.requireNonNull(request, "request");
        startExecution(admit(request));
    }

    void dispatchRequired(CloseRequest request) {
        Objects.requireNonNull(request, "request");
        Throwable startFailure = startExecution(admit(request));
        rethrow(startFailure);
    }

    void dispatchPair(CloseRequest first, CloseRequest second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        CloseExecution firstLaunch;
        CloseExecution secondLaunch;
        synchronized (lock) {
            requireCapacityLocked(2);
            outstanding += 2;
            firstLaunch = admitLocked(new CloseExecution(first));
            secondLaunch = admitLocked(new CloseExecution(second));
        }
        startExecution(firstLaunch);
        startExecution(secondLaunch);
    }

    private CloseExecution admit(CloseRequest request) {
        synchronized (lock) {
            requireCapacityLocked(1);
            outstanding++;
            return admitLocked(new CloseExecution(request));
        }
    }

    private void requireCapacityLocked(int requests) {
        if (outstanding > maxOutstandingCapacity - requests) {
            throw new RejectedExecutionException("Stream close capacity is exhausted: "
                    + outstanding
                    + " of "
                    + maxOutstandingCapacity
                    + " cleanup tasks are active or pending");
        }
    }

    private CloseExecution admitLocked(CloseExecution execution) {
        if (active < activeCapacity) {
            active++;
            return execution;
        }
        pending.addLast(execution);
        return null;
    }

    private Throwable startExecution(CloseExecution execution) {
        if (execution == null) {
            return null;
        }
        try {
            threadStarter.start(execution.request().threadPrefix(), execution::run);
            return null;
        } catch (RuntimeException | Error startFailure) {
            execution.complete(startFailure);
            return startFailure;
        }
    }

    private static Throwable attemptPhysicalClose(Closeable closeable) {
        try {
            closeable.close();
            return null;
        } catch (IOException | RuntimeException | Error failure) {
            return failure;
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void completeExecution(CloseExecution execution, Throwable failure) {
        CloseRequest request = execution.request();
        Throwable settledFailure = settle(request, failure);
        startExecution(releaseAndClaimNext());
        Thread sourceThread = Thread.currentThread();
        publishFailure(request, settledFailure, sourceThread);
        publishCompletion(request, sourceThread);
    }

    private CloseExecution releaseAndClaimNext() {
        synchronized (lock) {
            if (active <= 0 || outstanding <= 0) {
                throw new IllegalStateException("Stream close dispatcher accounting underflow");
            }
            active--;
            outstanding--;
            CloseExecution next = pending.pollFirst();
            if (next != null) {
                active++;
            }
            lock.notifyAll();
            return next;
        }
    }

    private static Throwable settle(CloseRequest request, Throwable failure) {
        try {
            request.settlement().accept(failure);
            return failure;
        } catch (Throwable settlementFailure) {
            return FailureAggregation.combine(
                    failure, settlementFailure, "Process stream close and settlement both failed");
        }
    }

    private void publishFailure(CloseRequest request, Throwable failure, Thread sourceThread) {
        if (failure == null) {
            return;
        }
        try {
            notifications.execute(sourceThread, () -> {
                try {
                    request.failureHandler().accept(failure);
                } catch (Throwable callbackFailure) {
                    notifications.report(
                            sourceThread,
                            FailureAggregation.combine(
                                    failure, callbackFailure, "Process stream close and failure callback both failed"));
                }
            });
        } catch (Throwable ignored) {
            // Physical close and mandatory settlement are already complete.
        }
    }

    private void publishCompletion(CloseRequest request, Thread sourceThread) {
        try {
            notifications.execute(sourceThread, request.completionHandler());
        } catch (Throwable ignored) {
            // Physical close and mandatory settlement are already complete.
        }
    }

    public int activeCount() {
        synchronized (lock) {
            return active;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public int outstandingCount() {
        synchronized (lock) {
            return outstanding;
        }
    }

    @FunctionalInterface
    public interface ThreadStarter {

        /**
         * Starts {@code task} asynchronously before returning.
         *
         * <p>An implementation that cannot start the task must throw before invoking it. Once the task can run, the
         * method must return normally.
         */
        void start(String threadPrefix, Runnable task);
    }

    record CloseRequest(
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> settlement,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {

        CloseRequest {
            Objects.requireNonNull(closeable, "closeable");
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(settlement, "settlement");
            Objects.requireNonNull(failureHandler, "failureHandler");
            Objects.requireNonNull(completionHandler, "completionHandler");
        }
    }

    private final class CloseExecution {

        private final CloseRequest request;

        private CloseExecution(CloseRequest request) {
            this.request = request;
        }

        private CloseRequest request() {
            return request;
        }

        private void run() {
            complete(attemptPhysicalClose(request.closeable()));
        }

        private void complete(Throwable failure) {
            completeExecution(this, failure);
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Runs mandatory pool lifecycle work on a fixed set of owners.
 *
 * <p>Each owner set has bounded queue admission. Mandatory retirement work runs on the caller when its queue is full;
 * reports and replenishment wait for their independent bounded capacity. A task submitted recursively into its current
 * owner set also runs inline, so queue capacity cannot make every owner wait for capacity that only those owners can
 * release.
 *
 * <p>All owners are started before this dispatcher becomes usable. A starter failure therefore fails construction
 * before any mandatory task can be accepted or abandoned.
 */
final class PoolLifecycleDispatcher {

    private static final int SHARED_PARALLELISM = 8;
    private static final int SHARED_QUEUE_CAPACITY = 256;

    private final Object lock = new Object();
    private final ArrayDeque<TaskRequest> pending = new ArrayDeque<>();
    private final BoundedTaskLimiter taskPermits;
    private final ThreadLocal<Boolean> ownerThread = new ThreadLocal<>();
    private boolean ready;
    private boolean running = true;

    PoolLifecycleDispatcher(int parallelism, TaskStarter starter, String threadPrefix) {
        this(parallelism, starter, threadPrefix, defaultTaskCapacity(parallelism));
    }

    PoolLifecycleDispatcher(int parallelism, TaskStarter starter, String threadPrefix, int taskCapacity) {
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        taskPermits = new BoundedTaskLimiter(taskCapacity);
        if (parallelism <= 0) {
            throw new IllegalArgumentException("dispatcher parallelism must be positive");
        }
        startOwners(parallelism, starter, threadPrefix);
    }

    static void executeRetirementBatch(Runnable task) {
        Retirements.INSTANCE.dispatchRetirementBatch(task);
    }

    static void replenish(Runnable task) {
        Replenishments.INSTANCE.dispatch(task);
    }

    static void report(Runnable task) {
        Reports.INSTANCE.dispatch(task);
    }

    void dispatch(Runnable task) {
        if (Boolean.TRUE.equals(ownerThread.get())) {
            runInline(task);
            return;
        }
        BoundedTaskPermit permit = taskPermits.acquireUninterruptibly();
        try {
            enqueue(new TaskRequest(task, permit));
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    void dispatchRetirementBatch(Runnable task) {
        if (Boolean.TRUE.equals(ownerThread.get())) {
            runInline(task);
            return;
        }
        BoundedTaskPermit permit = taskPermits.tryAcquire();
        if (permit == null) {
            runInline(task);
            return;
        }
        try {
            enqueue(new TaskRequest(task, permit));
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    private void enqueue(TaskRequest request) {
        synchronized (lock) {
            if (!running || !ready) {
                throw new IllegalStateException("pool lifecycle dispatcher is unavailable");
            }
            pending.addLast(request);
            lock.notifyAll();
        }
    }

    private void startOwners(int parallelism, TaskStarter starter, String threadPrefix) {
        try {
            for (int index = 0; index < parallelism; index++) {
                starter.start(threadPrefix, this::runOwner);
            }
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                running = false;
                ready = true;
                lock.notifyAll();
            }
            throw failure;
        }
        synchronized (lock) {
            ready = true;
            lock.notifyAll();
        }
    }

    private void runOwner() {
        boolean interrupted = false;
        ownerThread.set(Boolean.TRUE);
        try {
            while (true) {
                TaskRequest request;
                synchronized (lock) {
                    while (running && (!ready || pending.isEmpty())) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (!running) {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return;
                    }
                    request = pending.removeFirst();
                }
                request.run();
            }
        } finally {
            ownerThread.remove();
        }
    }

    private static void runInline(Runnable task) {
        new TaskRequest(task, null).run();
    }

    private static PoolLifecycleDispatcher shared(String threadPrefix, int taskCapacity) {
        return new PoolLifecycleDispatcher(SHARED_PARALLELISM, Threading::start, threadPrefix, taskCapacity);
    }

    private static final class Retirements {

        private static final PoolLifecycleDispatcher INSTANCE = shared("procwright-retirement-", SHARED_QUEUE_CAPACITY);
    }

    private static final class Reports {

        private static final PoolLifecycleDispatcher INSTANCE =
                shared("procwright-pool-late-report-", SHARED_QUEUE_CAPACITY);
    }

    private static final class Replenishments {

        private static final PoolLifecycleDispatcher INSTANCE =
                shared("procwright-pool-replenishment-", SHARED_QUEUE_CAPACITY);
    }

    private static int defaultTaskCapacity(int parallelism) {
        return Math.max(16, Math.multiplyExact(parallelism, 8));
    }

    @FunctionalInterface
    interface TaskStarter {

        Thread start(String threadPrefix, Runnable task);
    }

    private static final class TaskRequest {

        private final Runnable task;
        private final BoundedTaskPermit permit;

        private TaskRequest(Runnable task, BoundedTaskPermit permit) {
            this.task = Objects.requireNonNull(task, "task");
            this.permit = permit;
        }

        private void run() {
            try {
                task.run();
            } catch (Throwable ignored) {
                // Mandatory tasks expose failures through their own outcome channel.
            } finally {
                if (permit != null) {
                    permit.close();
                }
            }
        }
    }
}

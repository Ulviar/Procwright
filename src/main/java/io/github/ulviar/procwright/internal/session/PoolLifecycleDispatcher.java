/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs mandatory pool retirement work with fixed parallelism and bounded admission.
 *
 * <p>Retirement runs on the submitting thread when saturated or recursively submitted by its own owner.
 */
final class PoolLifecycleDispatcher {

    private static final int SHARED_PARALLELISM = 8;
    private static final int SHARED_TASK_CAPACITY = 256;

    private final ThreadLocal<Boolean> ownerThread = new ThreadLocal<>();
    private final ThreadPoolExecutor executor;

    PoolLifecycleDispatcher(int parallelism, int taskCapacity, ThreadFactory threadFactory) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("dispatcher parallelism must be positive");
        }
        if (taskCapacity <= parallelism) {
            throw new IllegalArgumentException("task capacity must be larger than parallelism");
        }
        Objects.requireNonNull(threadFactory, "threadFactory");
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(taskCapacity - parallelism);
        executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0,
                TimeUnit.MILLISECONDS,
                queue,
                task -> Objects.requireNonNull(threadFactory.newThread(owned(task)), "thread factory returned null"),
                this::reject);
        prestartInitialOwners();
    }

    static void executeRetirementBatch(Runnable task) {
        Retirements.INSTANCE.execute(task);
    }

    void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        Runnable safeTask = safe(task);
        if (Boolean.TRUE.equals(ownerThread.get())) {
            safeTask.run();
            return;
        }
        executor.execute(safeTask);
    }

    private void reject(Runnable task, ThreadPoolExecutor selectedExecutor) {
        if (selectedExecutor.isShutdown()) {
            throw new RejectedExecutionException("pool lifecycle dispatcher is unavailable");
        }
        task.run();
    }

    private void prestartInitialOwners() {
        try {
            executor.prestartAllCoreThreads();
        } catch (RuntimeException | Error failure) {
            executor.shutdownNow();
            throw failure;
        }
    }

    private Runnable owned(Runnable owner) {
        return () -> {
            ownerThread.set(Boolean.TRUE);
            try {
                owner.run();
            } finally {
                ownerThread.remove();
            }
        };
    }

    private static Runnable safe(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable ignored) {
                // Mandatory tasks expose failures through their own outcome channel.
            }
        };
    }

    private static PoolLifecycleDispatcher shared(String threadPrefix) {
        AtomicInteger sequence = new AtomicInteger();
        return new PoolLifecycleDispatcher(SHARED_PARALLELISM, SHARED_TASK_CAPACITY, task -> {
            Thread thread = Threading.unstartedPlatformNonInheriting(threadPrefix + sequence.getAndIncrement(), task);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            return thread;
        });
    }

    private static final class Retirements {

        private static final PoolLifecycleDispatcher INSTANCE = shared("procwright-retirement-");
    }
}

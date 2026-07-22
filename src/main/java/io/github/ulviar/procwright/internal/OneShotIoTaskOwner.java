/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the global admission bound for one-shot stdin and output tasks. */
final class OneShotIoTaskOwner {

    static final int SHARED_CAPACITY = 96;

    private static final OneShotIoTaskOwner SHARED = new OneShotIoTaskOwner(SHARED_CAPACITY);

    private final int capacity;
    private final Semaphore permits;

    OneShotIoTaskOwner(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        permits = new Semaphore(capacity, true);
    }

    static OneShotIoTaskOwner shared() {
        return SHARED;
    }

    Reservation reserve(int taskCount) {
        if (taskCount < 0 || taskCount > capacity) {
            throw new IllegalArgumentException("taskCount must be between 0 and " + capacity);
        }
        if (!permits.tryAcquire(taskCount)) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE,
                    "Could not start one-shot command because bounded I/O task capacity is exhausted");
        }
        return new Reservation(this, taskCount);
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    int capacity() {
        return capacity;
    }

    private void release() {
        permits.release();
    }

    static final class Reservation implements AutoCloseable {

        private final OneShotIoTaskOwner owner;
        private final ArrayDeque<Lease> unused;

        private Reservation(OneShotIoTaskOwner owner, int taskCount) {
            this.owner = owner;
            unused = new ArrayDeque<>(taskCount);
            for (int index = 0; index < taskCount; index++) {
                unused.addLast(new Lease(owner));
            }
        }

        <T> OwnedFuture<T> submit(Executor executor, Callable<T> task) {
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(task, "task");
            Lease lease;
            synchronized (this) {
                lease = unused.pollFirst();
            }
            if (lease == null) {
                throw new IllegalStateException("One-shot I/O reservation has no unused task permits");
            }
            OwnedFuture<T> future = new OwnedFuture<>(task, lease);
            try {
                executor.execute(future);
                return future;
            } catch (RuntimeException | Error failure) {
                future.cancel(false);
                throw failure;
            }
        }

        @Override
        public void close() {
            ArrayDeque<Lease> released;
            synchronized (this) {
                released = new ArrayDeque<>(unused);
                unused.clear();
            }
            released.forEach(Lease::release);
        }
    }

    static final class OwnedFuture<T> extends FutureTask<T> {

        private final Lease lease;
        private final AtomicBoolean started = new AtomicBoolean();
        private final CompletableFuture<TaskOutcome<T>> actualCompletion;

        private OwnedFuture(Callable<T> task, Lease lease) {
            this(task, lease, new CompletableFuture<>());
        }

        private OwnedFuture(Callable<T> task, Lease lease, CompletableFuture<TaskOutcome<T>> actualCompletion) {
            super(() -> {
                try {
                    T value = task.call();
                    actualCompletion.complete(TaskOutcome.completed(value));
                    return value;
                } catch (Throwable failure) {
                    actualCompletion.complete(TaskOutcome.failed(failure));
                    if (failure instanceof Exception exception) {
                        throw exception;
                    }
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    throw new AssertionError("one-shot task failed with an unknown Throwable", failure);
                }
            });
            this.lease = lease;
            this.actualCompletion = actualCompletion;
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                super.run();
            } finally {
                lease.release();
            }
        }

        @Override
        protected void done() {
            if (!started.get()) {
                actualCompletion.complete(TaskOutcome.cancelledOutcome());
                lease.release();
            }
        }

        CompletableFuture<TaskOutcome<T>> actualCompletion() {
            return actualCompletion.copy();
        }
    }

    record TaskOutcome<T>(T value, Throwable failure, boolean cancelled) {

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

    private static final class Lease {

        private final OneShotIoTaskOwner owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(OneShotIoTaskOwner owner) {
            this.owner = owner;
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }
}

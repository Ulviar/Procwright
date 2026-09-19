/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounds concurrent process scans and caller waiting without releasing abandoned operations' capacity. */
final class ProcessScanOperationOwner {

    private final Semaphore permits;
    private final OperationThreadFactory threadFactory;
    private final AtomicLong threadSequence = new AtomicLong();

    ProcessScanOperationOwner(int capacity, OperationThreadFactory threadFactory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("operationCapacity must be positive");
        }
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
        permits = new Semaphore(capacity);
    }

    static ProcessScanOperationOwner production(int capacity) {
        return new ProcessScanOperationOwner(capacity, Threading::unstartedPlatformNonInheriting);
    }

    <T> Result<T> scan(String threadPrefix, Duration timeout, Callable<T> operation) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        Permit permit = new Permit(permits);
        if (!permits.tryAcquire()) {
            return Result.failed(Result.Failure.UNAVAILABLE);
        }
        CompletableFuture<T> completion;
        Thread worker;
        try {
            completion = new CompletableFuture<>();
            String threadName = threadPrefix + Long.toUnsignedString(threadSequence.getAndIncrement());
            worker = Objects.requireNonNull(
                    threadFactory.unstarted(threadName, () -> runOperation(operation, permit, completion)));
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException unavailable) {
            permit.close();
            return Result.failed(Result.Failure.UNAVAILABLE);
        } catch (Error fatal) {
            permit.close();
            throw fatal;
        }
        try {
            return Result.completed(completion.get(DurationSupport.saturatedNanos(timeout), TimeUnit.NANOSECONDS));
        } catch (TimeoutException timeoutFailure) {
            worker.interrupt();
            return Result.failed(Result.Failure.DEADLINE);
        } catch (InterruptedException interruption) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return Result.failed(Result.Failure.INTERRUPTED);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error fatal) {
                throw fatal;
            }
            return Result.failed(Result.Failure.UNAVAILABLE);
        }
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    private static <T> void runOperation(Callable<T> operation, Permit permit, CompletableFuture<T> completion) {
        T value = null;
        Throwable failure = null;
        try {
            value = operation.call();
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            permit.close();
        }
        if (failure == null) {
            completion.complete(value);
        } else {
            completion.completeExceptionally(failure);
        }
    }

    @FunctionalInterface
    interface OperationThreadFactory {

        Thread unstarted(String threadName, Runnable task);
    }

    record Result<T>(T value, Failure failure) {

        Result {
            Objects.requireNonNull(failure, "failure");
        }

        private static <T> Result<T> completed(T value) {
            return new Result<>(value, Failure.NONE);
        }

        private static <T> Result<T> failed(Failure failure) {
            return new Result<>(null, failure);
        }

        boolean completed() {
            return failure == Failure.NONE;
        }

        enum Failure {
            NONE,
            DEADLINE,
            INTERRUPTED,
            UNAVAILABLE
        }
    }

    private static final class Permit implements AutoCloseable {

        private final Semaphore permits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}

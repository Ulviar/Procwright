/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Owns bounded admission and deadline-bound waiting for process-provider calls. */
final class ProcessProviderOperationOwner {

    private final Semaphore permits;
    private final OperationThreadFactory threadFactory;
    private final AtomicLong threadSequence = new AtomicLong();

    ProcessProviderOperationOwner(int capacity, OperationThreadFactory threadFactory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("operationCapacity must be positive");
        }
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
        permits = new Semaphore(capacity, true);
    }

    static ProcessProviderOperationOwner production(int capacity) {
        return new ProcessProviderOperationOwner(capacity, Threading::unstartedPlatformNonInheriting);
    }

    <T> BestEffortResult<T> bestEffortResult(String threadPrefix, Duration timeout, Callable<T> operation) {
        Permit permit = new Permit(permits);
        if (!permit.tryAcquire()) {
            return BestEffortResult.unavailable();
        }
        try {
            return BestEffortResult.completed(execute(threadPrefix, timeout, operation, permit));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return BestEffortResult.interrupted();
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return BestEffortResult.unavailable();
        } catch (CommandExecutionException failure) {
            return causedByOperationDeadline(failure)
                    ? BestEffortResult.deadlineExceeded()
                    : BestEffortResult.unavailable();
        } catch (RuntimeException unavailable) {
            return BestEffortResult.unavailable();
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception impossible) {
            return BestEffortResult.unavailable();
        }
    }

    <T> T required(String threadPrefix, Duration timeout, Callable<T> operation) throws InterruptedException {
        Permit permit = new Permit(permits);
        if (!permit.tryAcquire()) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE,
                    "Could not execute provider process operation because bounded capacity is exhausted");
        }
        try {
            return execute(threadPrefix, timeout, operation, permit);
        } catch (InterruptedException interruption) {
            throw interruption;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE,
                    "Provider process operation failed: " + threadPrefix,
                    failure);
        }
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    static boolean causedByOperationDeadline(CommandExecutionException failure) {
        return failure.getCause() instanceof OperationDeadlineExceeded;
    }

    static CommandExecutionException operationDeadlineExceeded(String operation) {
        return operationDeadlineExceeded(operation, null);
    }

    private static CommandExecutionException operationDeadlineExceeded(
            String operation, TimeoutException timeoutFailure) {
        OperationDeadlineExceeded deadline = new OperationDeadlineExceeded(operation);
        if (timeoutFailure != null) {
            deadline.initCause(timeoutFailure);
        }
        return new CommandExecutionException(
                CommandExecutionException.Reason.RUNTIME_FAILURE,
                "Provider process operation exceeded its bounded deadline: " + operation,
                deadline);
    }

    private <T> T execute(String threadPrefix, Duration timeout, Callable<T> operation, Permit permit)
            throws Exception {
        CompletableFuture<Outcome<T>> completion;
        Thread worker;
        try {
            completion = new CompletableFuture<>();
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(operation, "operation");
            String threadName = threadPrefix + Long.toUnsignedString(threadSequence.getAndIncrement());
            worker = Objects.requireNonNull(
                    threadFactory.unstarted(threadName, () -> runOperation(operation, permit, completion)));
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | Error startFailure) {
            permit.close();
            throw startFailure;
        }
        return awaitResult(threadPrefix, timeout, completion, worker);
    }

    private static <T> void runOperation(
            Callable<T> operation, Permit permit, CompletableFuture<Outcome<T>> completion) {
        Outcome<T> outcome;
        try {
            outcome = Outcome.completed(operation.call());
        } catch (Throwable failure) {
            outcome = Outcome.failed(failure);
        } finally {
            permit.close();
        }
        completion.complete(outcome);
    }

    private static <T> T awaitResult(
            String threadPrefix, Duration timeout, CompletableFuture<Outcome<T>> completion, Thread worker)
            throws Exception {
        try {
            Outcome<T> outcome = completion.get(DurationSupport.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
            if (outcome.failure() instanceof Exception exception) {
                throw exception;
            }
            if (outcome.failure() instanceof Error error) {
                throw error;
            }
            return outcome.value();
        } catch (TimeoutException timeoutFailure) {
            worker.interrupt();
            throw operationDeadlineExceeded(threadPrefix, timeoutFailure);
        } catch (ExecutionException impossible) {
            throw new AssertionError("process operation completion stores failures as values", impossible);
        } catch (InterruptedException interruption) {
            worker.interrupt();
            throw interruption;
        }
    }

    @FunctionalInterface
    interface OperationThreadFactory {

        Thread unstarted(String threadName, Runnable task);
    }

    private record Outcome<T>(T value, Throwable failure) {

        private static <T> Outcome<T> completed(T value) {
            return new Outcome<>(value, null);
        }

        private static <T> Outcome<T> failed(Throwable failure) {
            return new Outcome<>(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    record BestEffortResult<T>(T value, Failure failure) {

        BestEffortResult {
            failure = Objects.requireNonNull(failure, "failure");
        }

        private static <T> BestEffortResult<T> completed(T value) {
            return new BestEffortResult<>(value, Failure.NONE);
        }

        private static <T> BestEffortResult<T> deadlineExceeded() {
            return new BestEffortResult<>(null, Failure.DEADLINE);
        }

        private static <T> BestEffortResult<T> unavailable() {
            return new BestEffortResult<>(null, Failure.UNAVAILABLE);
        }

        private static <T> BestEffortResult<T> interrupted() {
            return new BestEffortResult<>(null, Failure.INTERRUPTED);
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
        private boolean acquired;
        private boolean closed;

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        private synchronized boolean tryAcquire() {
            if (!permits.tryAcquire()) {
                return false;
            }
            acquired = true;
            return true;
        }

        @Override
        public synchronized void close() {
            if (acquired && !closed) {
                closed = true;
                permits.release();
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class OperationDeadlineExceeded extends TimeoutException {

        private OperationDeadlineExceeded(String operation) {
            super("Provider process operation exceeded its bounded deadline: " + operation);
        }
    }
}

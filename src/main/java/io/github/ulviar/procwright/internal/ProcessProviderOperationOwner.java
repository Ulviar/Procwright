/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Owns bounded execution, abandonment, and fresh-thread isolation for process-provider calls. */
final class ProcessProviderOperationOwner {

    private final Semaphore permits;
    private final OperationThreadFactory threadFactory;
    private final BoundedFailureReporter failureReporter;
    private final AtomicLong threadSequence = new AtomicLong();

    ProcessProviderOperationOwner(
            int capacity, OperationThreadFactory threadFactory, BoundedFailureReporter failureReporter) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("operationCapacity must be positive");
        }
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        permits = new Semaphore(capacity, true);
    }

    static ProcessProviderOperationOwner production(int capacity) {
        return new ProcessProviderOperationOwner(
                capacity, Threading::unstartedPlatformNonInheriting, BoundedFailureReporter.shared());
    }

    <T> Optional<T> bestEffort(String threadPrefix, Duration timeout, Callable<T> operation) {
        Permit permit = new Permit(permits);
        if (!permit.tryAcquire()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(execute(threadPrefix, timeout, operation, permit));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Optional.empty();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception impossible) {
            return Optional.empty();
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

    boolean awaitReportingSettlement(Duration timeout) throws InterruptedException {
        return failureReporter.awaitSettlement(timeout);
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
        BoundedFailureReporter.ProducerRegistration producer = null;
        OperationHandoff<T> handoff;
        try {
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(operation, "operation");

            CompletableFuture<Outcome<T>> completion = new CompletableFuture<>();
            LateTaskFailureReporter lateFailureReporter = new LateTaskFailureReporter(failureReporter);
            producer = failureReporter.registerProducer();
            ProcessProviderOperationSettlement settlement =
                    new ProcessProviderOperationSettlement(lateFailureReporter, producer);
            ProcessProviderOperationCancellation cancellation = new ProcessProviderOperationCancellation();
            handoff = new OperationHandoff<>(completion, settlement, cancellation, permit);
            String threadName = threadPrefix + Long.toUnsignedString(threadSequence.getAndIncrement());
            Thread worker =
                    Objects.requireNonNull(threadFactory.unstarted(threadName, () -> runOperation(handoff, operation)));
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | Error startFailure) {
            rollbackAdmission(producer, permit);
            throw startFailure;
        }
        return awaitResult(threadPrefix, timeout, handoff);
    }

    private static <T> void runOperation(OperationHandoff<T> handoff, Callable<T> operation) {
        CompletableFuture<Outcome<T>> completion = handoff.completion();
        ProcessProviderOperationSettlement settlement = handoff.settlement();
        ProcessProviderOperationCancellation cancellation = handoff.cancellation();
        Permit permit = handoff.permit();
        Thread worker = Thread.currentThread();
        boolean bound = false;
        try {
            settlement.bind(worker);
            cancellation.bind(worker);
            bound = true;
            Outcome<T> outcome;
            try {
                outcome = Outcome.completed(operation.call());
            } catch (Throwable failure) {
                outcome = Outcome.failed(failure);
            }
            permit.close();
            completion.complete(outcome);
            settlement.workerCompleted(outcome.failure());
        } catch (RuntimeException | Error infrastructureFailure) {
            if (!completion.isDone()) {
                permit.close();
                try {
                    completion.complete(Outcome.failed(infrastructureFailure));
                } finally {
                    settlement.workerCompleted(infrastructureFailure);
                }
            }
            throw infrastructureFailure;
        } finally {
            permit.close();
            if (bound) {
                cancellation.unbind(worker);
            }
        }
    }

    private static void rollbackAdmission(BoundedFailureReporter.ProducerRegistration producer, Permit permit) {
        try {
            if (producer != null) {
                producer.complete();
            }
        } catch (RuntimeException | Error ignored) {
            // Permit recovery is independent from reporting settlement cleanup.
        } finally {
            permit.close();
        }
    }

    private static <T> T awaitResult(String threadPrefix, Duration timeout, OperationHandoff<T> handoff)
            throws Exception {
        try {
            Outcome<T> outcome =
                    handoff.completion().get(DurationSupport.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
            handoff.settlement().resultObserved();
            if (outcome.failure() instanceof Exception exception) {
                throw exception;
            }
            if (outcome.failure() instanceof Error error) {
                throw error;
            }
            return outcome.value();
        } catch (TimeoutException timeoutFailure) {
            handoff.settlement().abandon();
            handoff.cancellation().interrupt();
            throw operationDeadlineExceeded(threadPrefix, timeoutFailure);
        } catch (ExecutionException impossible) {
            throw new AssertionError("process operation completion stores failures as values", impossible);
        } catch (InterruptedException interruption) {
            handoff.settlement().abandon();
            handoff.cancellation().interrupt();
            throw interruption;
        }
    }

    @FunctionalInterface
    interface OperationThreadFactory {

        Thread unstarted(String threadName, Runnable task);
    }

    private record OperationHandoff<T>(
            CompletableFuture<Outcome<T>> completion,
            ProcessProviderOperationSettlement settlement,
            ProcessProviderOperationCancellation cancellation,
            Permit permit) {}

    private record Outcome<T>(T value, Throwable failure) {

        private static <T> Outcome<T> completed(T value) {
            return new Outcome<>(value, null);
        }

        private static <T> Outcome<T> failed(Throwable failure) {
            return new Outcome<>(null, Objects.requireNonNull(failure, "failure"));
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

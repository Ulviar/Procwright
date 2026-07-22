/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds fallback calls to custom {@link Process} destruction methods that may block. */
final class BoundedDestroyDispatcher {

    private static final int CAPACITY = 32;
    private static final Limiter LIMITER = new Limiter(CAPACITY);
    private static final long OBSERVATION_MILLIS = 25;
    private static final CompletionObserver DEFAULT_OBSERVER =
            completion -> completion.get(OBSERVATION_MILLIS, TimeUnit.MILLISECONDS);

    private BoundedDestroyDispatcher() {}

    static void dispatch(String threadPrefix, Runnable action) {
        dispatch(threadPrefix, action, LIMITER, DEFAULT_OBSERVER);
    }

    static void dispatch(String threadPrefix, Runnable action, Limiter limiter, CompletionObserver observer) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(limiter, "limiter");
        Objects.requireNonNull(observer, "observer");
        if (!limiter.tryAcquire()) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE,
                    "Could not schedule process destroy fallback because bounded destroy capacity is exhausted");
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        LateTaskFailureReporter lateFailure = new LateTaskFailureReporter();
        Thread fallback;
        try {
            fallback = Threading.unstarted(threadPrefix, () -> {
                Throwable failure = null;
                try {
                    action.run();
                } catch (Throwable taskFailure) {
                    failure = taskFailure;
                } finally {
                    limiter.release();
                }
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                    lateFailure.record(failure);
                }
            });
            lateFailure.bind(fallback);
            fallback.start();
        } catch (RuntimeException | Error failure) {
            limiter.release();
            throw failure;
        }

        try {
            observer.await(completion);
        } catch (TimeoutException exception) {
            lateFailure.abandon();
            fallback.interrupt();
        } catch (InterruptedException exception) {
            lateFailure.abandon();
            fallback.interrupt();
            Thread.interrupted();
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE,
                    "Interrupted while observing process destroy fallback",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE, "Process destroy fallback failed", cause);
        }
    }

    static int capacity() {
        return CAPACITY;
    }

    static int availablePermits() {
        return LIMITER.availablePermits();
    }

    @FunctionalInterface
    interface CompletionObserver {

        void await(CompletableFuture<Void> completion)
                throws InterruptedException, ExecutionException, TimeoutException;
    }

    static final class Limiter {

        private final int capacity;
        private final Semaphore permits;

        Limiter(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.capacity = capacity;
            permits = new Semaphore(capacity, true);
        }

        private boolean tryAcquire() {
            return permits.tryAcquire();
        }

        private void release() {
            permits.release();
        }

        int capacity() {
            return capacity;
        }

        int availablePermits() {
            return permits.availablePermits();
        }
    }
}

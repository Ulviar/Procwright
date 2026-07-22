/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class PtyLaunchAdmission {

    static final int MAX_CONCURRENT_TASKS = 8;
    static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(10);
    private static final ThreadPoolExecutor EXECUTOR = executor();

    private PtyLaunchAdmission() {}

    static Process launch(Duration timeout, Operation<Process> operation) throws IOException, InterruptedException {
        return execute(timeout, operation, true);
    }

    static <T> T run(Duration timeout, Operation<T> operation) throws IOException, InterruptedException {
        return execute(timeout, operation, false);
    }

    private static <T> T execute(Duration timeout, Operation<T> operation, boolean handoffProcess)
            throws IOException, InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("PTY launch timeout must be between 1 ns and 10 seconds");
        }

        Context context = new Context(timeout);
        AdmissionTask<T> task = new AdmissionTask<>(context, operation, handoffProcess);
        Future<T> future;
        try {
            future = EXECUTOR.submit(task::call);
        } catch (RejectedExecutionException exception) {
            throw new IOException("PTY launch capacity is exhausted", exception);
        }

        try {
            long remaining = context.remainingNanos();
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            task.abort();
            future.cancel(true);
            throw new IOException("PTY launch did not complete before its deadline", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("PTY launch task failed", cause);
        } catch (InterruptedException exception) {
            task.abort();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    @FunctionalInterface
    interface Operation<T> {

        T run(Context context) throws IOException, InterruptedException;
    }

    static final class Context {

        private final long deadline;
        private final AtomicReference<Process> process = new AtomicReference<>();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicBoolean cleanupStarted = new AtomicBoolean();

        private Context(Duration timeout) {
            deadline = addSaturated(System.nanoTime(), timeout.toNanos());
        }

        void registerProcess(Process launched) throws IOException, InterruptedException {
            Objects.requireNonNull(launched, "launched");
            if (!process.compareAndSet(null, launched)) {
                throw new IllegalStateException("PTY admission already owns a process");
            }
            checkpoint();
        }

        Duration remaining() throws IOException, InterruptedException {
            checkpoint();
            return Duration.ofNanos(Math.max(1L, remainingNanos()));
        }

        void checkpoint() throws IOException, InterruptedException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("PTY launch task was interrupted");
            }
            if (abandoned.get()) {
                throw new IOException("PTY launch was cancelled");
            }
            if (remainingNanos() <= 0) {
                throw new IOException("PTY launch did not complete before its deadline");
            }
        }

        private long remainingNanos() {
            return deadline - System.nanoTime();
        }

        private void abandon() {
            abandoned.set(true);
            cleanup();
        }

        private void cleanup() {
            Process owned = process.get();
            if (owned == null || !cleanupStarted.compareAndSet(false, true)) {
                return;
            }
            PtyProcessCleanup.terminate(owned);
        }
    }

    private static final class AdmissionTask<T> {

        private final Context context;
        private final Operation<T> operation;
        private final boolean handoffProcess;

        private AdmissionTask(Context context, Operation<T> operation, boolean handoffProcess) {
            this.context = context;
            this.operation = operation;
            this.handoffProcess = handoffProcess;
        }

        private T call() throws IOException, InterruptedException {
            boolean succeeded = false;
            try {
                context.checkpoint();
                T result = operation.run(context);
                context.checkpoint();
                if (handoffProcess && result != context.process.get()) {
                    throw new IOException("PTY launch did not register its process");
                }
                succeeded = true;
                return result;
            } finally {
                if (!succeeded || !handoffProcess) {
                    context.cleanup();
                }
            }
        }

        private void abort() {
            context.abandon();
        }
    }

    private static long addSaturated(long value, long addition) {
        try {
            return Math.addExact(value, addition);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static ThreadPoolExecutor executor() {
        AtomicLong sequence = new AtomicLong();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "procwright-pty-launch-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                MAX_CONCURRENT_TASKS,
                30,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}

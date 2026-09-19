/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns one worker-factory invocation and its terminal race against the caller's deadline and pool close. */
final class WorkerStartup<S> {

    private final Supplier<S> factory;
    private final String threadPrefix;
    private final Consumer<LateCompletion<S>> lateCompletion;
    private final ThreadFactory threadFactory;
    private final AtomicBoolean startClaimed = new AtomicBoolean();
    private final CompletableFuture<Outcome<S>> outcome = new CompletableFuture<>();

    private volatile Thread thread;
    private long startedAtNanos;

    WorkerStartup(Supplier<S> factory, String threadPrefix, Consumer<LateCompletion<S>> lateCompletion) {
        this(factory, threadPrefix, lateCompletion, Threading::unstarted);
    }

    WorkerStartup(
            Supplier<S> factory,
            String threadPrefix,
            Consumer<LateCompletion<S>> lateCompletion,
            ThreadFactory threadFactory) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        this.lateCompletion = Objects.requireNonNull(lateCompletion, "lateCompletion");
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    void start() {
        if (!startClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("worker startup is already started");
        }
        if (outcome.isDone()) {
            return;
        }
        startedAtNanos = System.nanoTime();
        Thread candidate =
                Objects.requireNonNull(threadFactory.unstarted(threadPrefix, this::run), "threadFactory returned null");
        thread = candidate;
        if (!outcome.isDone()) {
            candidate.start();
        }
    }

    Outcome<S> await(long deadlineNanos) {
        try {
            return awaitOutcome(deadlineNanos);
        } catch (TimeoutException failure) {
            return decide(StopReason.TIMED_OUT);
        } catch (InterruptedException failure) {
            try {
                return decide(StopReason.INTERRUPTED);
            } finally {
                Thread.currentThread().interrupt();
            }
        }
    }

    Outcome<S> signalTimeout() {
        return decide(StopReason.TIMED_OUT);
    }

    Outcome<S> signalClosed() {
        return decide(StopReason.CLOSED);
    }

    private void run() {
        S session = null;
        Throwable failure = null;
        BoundedFailureReporter.FailureTarget failureTarget = null;
        Outcome<S> beforeFactory = outcome.getNow(null);
        if (beforeFactory == null) {
            try {
                session = Objects.requireNonNull(factory.get(), "workerFactory returned null");
            } catch (Throwable startupFailure) {
                failure = startupFailure;
                failureTarget = captureFailureTarget();
            }
        }
        if (beforeFactory != null) {
            lateCompletion.accept(new LateCompletion<>(
                    null, System.nanoTime() - startedAtNanos, retireReason(beforeFactory), null, null));
            return;
        }
        long startupNanos = System.nanoTime() - startedAtNanos;
        Outcome<S> factoryOutcome =
                failure == null ? new CreatedWorker<>(session, startupNanos) : new Failed<>(failure);
        if (outcome.complete(factoryOutcome)) {
            return;
        }
        Outcome<S> selected = outcome.join();
        lateCompletion.accept(
                new LateCompletion<>(session, startupNanos, retireReason(selected), failure, failureTarget));
    }

    private Outcome<S> awaitOutcome(long deadlineNanos) throws TimeoutException, InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("worker startup deadline elapsed");
        }
        try {
            return outcome.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException impossible) {
            throw new AssertionError("worker startup outcome completed exceptionally", impossible);
        }
    }

    private Outcome<S> decide(StopReason candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Outcome<S> selected = new Stopped<>(candidate);
        if (outcome.complete(selected)) {
            Thread running = thread;
            if (running != null) {
                running.interrupt();
            }
            return selected;
        }
        return outcome.join();
    }

    private static PooledWorkerRetireReason retireReason(Outcome<?> outcome) {
        if (!(outcome instanceof Stopped<?> stopped)) {
            throw new IllegalStateException("late startup must have a selected stop reason");
        }
        return switch (stopped.reason()) {
            case CLOSED -> PooledWorkerRetireReason.CLOSED;
            case TIMED_OUT -> PooledWorkerRetireReason.STARTUP_TIMEOUT;
            case INTERRUPTED -> PooledWorkerRetireReason.STARTUP_INTERRUPTED;
        };
    }

    private static BoundedFailureReporter.FailureTarget captureFailureTarget() {
        try {
            return BoundedFailureReporter.captureFailureTarget();
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    enum StopReason {
        TIMED_OUT,
        INTERRUPTED,
        CLOSED
    }

    sealed interface Outcome<S> permits CreatedWorker, Failed, Stopped {}

    record CreatedWorker<S>(S session, long startupNanos) implements Outcome<S> {

        CreatedWorker {
            Objects.requireNonNull(session, "session");
        }
    }

    record Failed<S>(Throwable failure) implements Outcome<S> {

        Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }

    record Stopped<S>(StopReason reason) implements Outcome<S> {

        Stopped {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record LateCompletion<S>(
            S session,
            long startupNanos,
            PooledWorkerRetireReason reason,
            Throwable failure,
            BoundedFailureReporter.FailureTarget failureTarget) {

        LateCompletion {
            Objects.requireNonNull(reason, "reason");
        }
    }

    @FunctionalInterface
    interface ThreadFactory {

        Thread unstarted(String threadPrefix, Runnable task);
    }
}

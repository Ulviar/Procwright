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

/** Owns one bounded worker-factory invocation and its terminal race. */
final class WorkerStartup<S> {

    private final Supplier<S> factory;
    private final String threadPrefix;
    private final Consumer<LateCompletion<S>> lateCompletion;
    private final ThreadFactory threadFactory;
    private final BoundedTaskRunner.CancellationSignal cancellation = new BoundedTaskRunner.CancellationSignal();
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

    BoundedTaskPermit acquirePermit(BoundedTaskLimiter limiter, long deadlineNanos)
            throws TimeoutException, InterruptedException, BoundedTaskRunner.TaskCancelledException {
        return Objects.requireNonNull(limiter, "limiter").acquire(deadlineNanos, cancellation);
    }

    void start(BoundedTaskPermit permit) {
        Objects.requireNonNull(permit, "permit");
        if (!startClaimed.compareAndSet(false, true)) {
            permit.close();
            throw new IllegalStateException("worker startup is already started");
        }
        if (outcome.isDone()) {
            permit.close();
            return;
        }
        try {
            startedAtNanos = System.nanoTime();
            Thread candidate = Objects.requireNonNull(
                    threadFactory.unstarted(threadPrefix, () -> run(permit)), "threadFactory returned null");
            thread = candidate;
            if (!outcome.isDone()) {
                candidate.start();
            } else {
                permit.close();
            }
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    CreatedWorker<S> await(long deadlineNanos) throws TimeoutException, InterruptedException, ExecutionException {
        Outcome<S> observed;
        boolean restoreInterrupted = false;
        try {
            observed = awaitOutcome(deadlineNanos);
        } catch (TimeoutException failure) {
            observed = decide(TerminalDecision.TIMED_OUT);
            if (observed.decision() != TerminalDecision.FACTORY_COMPLETED) {
                throw failure;
            }
        } catch (InterruptedException failure) {
            observed = decide(TerminalDecision.INTERRUPTED);
            if (observed.decision() != TerminalDecision.FACTORY_COMPLETED) {
                throw failure;
            }
            restoreInterrupted = true;
        }
        try {
            return createdWorker(observed);
        } finally {
            if (restoreInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    TerminalDecision signalTimeout() {
        return decide(TerminalDecision.TIMED_OUT).decision();
    }

    TerminalDecision signalClosed() {
        return decide(TerminalDecision.CLOSED).decision();
    }

    TerminalDecision signalInterrupted() {
        return decide(TerminalDecision.INTERRUPTED).decision();
    }

    TerminalDecision terminalDecision() {
        Outcome<S> selected = outcome.getNow(null);
        return selected == null ? TerminalDecision.UNDECIDED : selected.decision();
    }

    private void run(BoundedTaskPermit permit) {
        S session = null;
        Throwable failure = null;
        BoundedFailureReporter.FailureTarget failureTarget = null;
        Outcome<S> beforeFactory = outcome.getNow(null);
        try {
            if (beforeFactory == null) {
                try {
                    session = Objects.requireNonNull(factory.get(), "workerFactory returned null");
                } catch (Throwable startupFailure) {
                    failure = startupFailure;
                    failureTarget = captureFailureTarget();
                }
            }
        } finally {
            permit.close();
        }
        if (beforeFactory != null) {
            lateCompletion.accept(new LateCompletion<>(
                    null, System.nanoTime() - startedAtNanos, retireReason(beforeFactory.decision()), null, null));
            return;
        }
        if (session == null && failure == null) {
            return;
        }

        long startupNanos = System.nanoTime() - startedAtNanos;
        Outcome<S> factoryOutcome = Outcome.factoryCompleted(session, startupNanos, failure);
        if (outcome.complete(factoryOutcome)) {
            return;
        }
        Outcome<S> selected = outcome.join();
        lateCompletion.accept(
                new LateCompletion<>(session, startupNanos, retireReason(selected.decision()), failure, failureTarget));
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

    private Outcome<S> decide(TerminalDecision candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate == TerminalDecision.FACTORY_COMPLETED || candidate == TerminalDecision.UNDECIDED) {
            throw new IllegalArgumentException("external decision must stop worker startup");
        }
        Outcome<S> selected = Outcome.stopped(candidate);
        if (outcome.complete(selected)) {
            cancellation.cancel();
            Thread running = thread;
            if (running != null) {
                running.interrupt();
            }
            return selected;
        }
        return outcome.join();
    }

    private static <S> CreatedWorker<S> createdWorker(Outcome<S> outcome)
            throws TimeoutException, InterruptedException, ExecutionException {
        return switch (outcome.decision()) {
            case FACTORY_COMPLETED -> {
                if (outcome.failure() != null) {
                    throw new ExecutionException(outcome.failure());
                }
                yield new CreatedWorker<>(outcome.session(), outcome.startupNanos());
            }
            case CLOSED -> throw new TimeoutException("worker startup was closed");
            case TIMED_OUT -> throw new TimeoutException("worker startup deadline elapsed");
            case INTERRUPTED -> throw new InterruptedException("worker startup was interrupted");
            case UNDECIDED -> throw new AssertionError("worker startup has no terminal outcome");
        };
    }

    private static PooledWorkerRetireReason retireReason(TerminalDecision decision) {
        return switch (decision) {
            case CLOSED -> PooledWorkerRetireReason.CLOSED;
            case TIMED_OUT -> PooledWorkerRetireReason.STARTUP_TIMEOUT;
            case INTERRUPTED -> PooledWorkerRetireReason.STARTUP_INTERRUPTED;
            case FACTORY_COMPLETED, UNDECIDED ->
                throw new IllegalStateException("late startup has incompatible terminal decision: " + decision);
        };
    }

    private static BoundedFailureReporter.FailureTarget captureFailureTarget() {
        try {
            return BoundedFailureReporter.captureFailureTarget();
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    enum TerminalDecision {
        UNDECIDED,
        FACTORY_COMPLETED,
        TIMED_OUT,
        INTERRUPTED,
        CLOSED
    }

    private record Outcome<S>(TerminalDecision decision, S session, long startupNanos, Throwable failure) {

        private Outcome {
            Objects.requireNonNull(decision, "decision");
            if (decision == TerminalDecision.FACTORY_COMPLETED) {
                if ((session == null) == (failure == null)) {
                    throw new IllegalArgumentException(
                            "factory outcome must contain exactly one of session or failure");
                }
            } else if (decision == TerminalDecision.UNDECIDED
                    || session != null
                    || startupNanos != 0
                    || failure != null) {
                throw new IllegalArgumentException("stopped outcome must contain only its terminal decision");
            }
        }

        private static <S> Outcome<S> stopped(TerminalDecision decision) {
            return new Outcome<>(decision, null, 0, null);
        }

        private static <S> Outcome<S> factoryCompleted(S session, long startupNanos, Throwable failure) {
            return new Outcome<>(TerminalDecision.FACTORY_COMPLETED, session, startupNanos, failure);
        }
    }

    record CreatedWorker<S>(S session, long startupNanos) {

        CreatedWorker {
            Objects.requireNonNull(session, "session");
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

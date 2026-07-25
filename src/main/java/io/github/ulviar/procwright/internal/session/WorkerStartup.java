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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns one bounded worker-factory invocation and its terminal race. */
final class WorkerStartup<S> {

    private final Supplier<S> factory;
    private final String threadPrefix;
    private final Consumer<LateCompletion<S>> lateCompletion;
    private final ThreadFactory threadFactory;
    private final AtomicReference<TerminalDecision> terminal = new AtomicReference<>(TerminalDecision.UNDECIDED);
    private final CompletableFuture<CreatedWorker<S>> completion = new CompletableFuture<>();

    private AttemptState state = AttemptState.WAITING;
    private boolean taskFinished;
    private CreatedWorker<S> completedWorker;
    private Throwable completedFailure;
    private BoundedFailureReporter.FailureTarget completedFailureTarget;
    private PooledWorkerRetireReason abandonReason;
    private Thread thread;
    private long startedAtNanos;
    private boolean errorReported;

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

    void start(BoundedTaskPermit permit) {
        Objects.requireNonNull(permit, "permit");
        try {
            startedAtNanos = System.nanoTime();
            thread = Objects.requireNonNull(
                    threadFactory.unstarted(threadPrefix, () -> run(permit)), "threadFactory returned null");
            thread.start();
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    CreatedWorker<S> await(long deadlineNanos) throws TimeoutException, InterruptedException, ExecutionException {
        boolean restoreInterrupt = false;
        try {
            CreatedWorker<S> result;
            try {
                result = awaitCompletion(deadlineNanos);
            } catch (InterruptedException failure) {
                TerminalDecision decision = signalInterrupted();
                if (decision != TerminalDecision.FACTORY_COMPLETED) {
                    throw failure;
                }
                restoreInterrupt = true;
                result = awaitFactoryCompletionUninterruptibly();
            }
            synchronized (this) {
                if (state != AttemptState.WAITING) {
                    throw new TimeoutException("worker startup was abandoned");
                }
                acceptResult();
            }
            return result;
        } catch (ExecutionException failure) {
            synchronized (this) {
                if (state == AttemptState.WAITING) {
                    acceptResult();
                }
            }
            throw failure;
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void abandon(PooledWorkerRetireReason reason) {
        CreatedWorker<S> lateWorker;
        Throwable lateFailure;
        BoundedFailureReporter.FailureTarget lateFailureTarget;
        boolean finished;
        synchronized (this) {
            if (state != AttemptState.WAITING) {
                return;
            }
            state = AttemptState.ABANDONED;
            abandonReason = Objects.requireNonNull(reason, "reason");
            lateWorker = completedWorker;
            lateFailure = completedFailure;
            lateFailureTarget = completedFailureTarget;
            finished = taskFinished;
            clearCompletedResult();
        }
        thread.interrupt();
        if (finished) {
            finishStoredAbandonment(lateWorker, lateFailure, lateFailureTarget, reason);
        }
    }

    TerminalDecision signalTimeout() {
        return decideTerminal(TerminalDecision.TIMED_OUT);
    }

    TerminalDecision signalClosed() {
        return decideTerminal(TerminalDecision.CLOSED);
    }

    TerminalDecision signalInterrupted() {
        return decideTerminal(TerminalDecision.INTERRUPTED);
    }

    TerminalDecision terminalDecision() {
        return terminal.get();
    }

    private void run(BoundedTaskPermit permit) {
        S session = null;
        Throwable failure = null;
        BoundedFailureReporter.FailureTarget failureTarget = null;
        try {
            session = Objects.requireNonNull(factory.get(), "workerFactory returned null");
        } catch (Throwable startupFailure) {
            failure = startupFailure;
            failureTarget = captureFailureTarget();
        } finally {
            permit.close();
        }

        CreatedWorker<S> createdWorker =
                session == null ? null : new CreatedWorker<>(session, System.nanoTime() - startedAtNanos);
        decideTerminal(TerminalDecision.FACTORY_COMPLETED);
        PooledWorkerRetireReason reason = null;
        boolean reportError = false;
        synchronized (this) {
            if (state == AttemptState.WAITING) {
                taskFinished = true;
                completedWorker = createdWorker;
                completedFailure = failure;
                completedFailureTarget = failureTarget;
                if (failure == null) {
                    completion.complete(createdWorker);
                } else {
                    completion.completeExceptionally(failure);
                }
                return;
            }
            if (state == AttemptState.ABANDONED) {
                reason = abandonReason;
                state = AttemptState.FINISHED;
                reportError = failure instanceof Error && failureTarget != null && claimErrorReport();
            }
        }
        lateCompletion.accept(new LateCompletion<>(
                session,
                System.nanoTime() - startedAtNanos,
                Objects.requireNonNull(reason, "abandonReason"),
                failure,
                reportError ? Objects.requireNonNull(failureTarget, "failureTarget") : null));
    }

    private CreatedWorker<S> awaitCompletion(long deadlineNanos)
            throws TimeoutException, InterruptedException, ExecutionException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return resolveStartupTimeout(new TimeoutException("worker startup deadline elapsed"));
        }
        try {
            return completion.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            return resolveStartupTimeout(failure);
        }
    }

    private CreatedWorker<S> resolveStartupTimeout(TimeoutException failure)
            throws TimeoutException, InterruptedException, ExecutionException {
        TerminalDecision decision = signalTimeout();
        if (decision == TerminalDecision.FACTORY_COMPLETED) {
            return completion.get();
        }
        throw failure;
    }

    private CreatedWorker<S> awaitFactoryCompletionUninterruptibly() throws ExecutionException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return completion.get();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void finishStoredAbandonment(
            CreatedWorker<S> lateWorker,
            Throwable lateFailure,
            BoundedFailureReporter.FailureTarget lateFailureTarget,
            PooledWorkerRetireReason reason) {
        boolean reportError;
        synchronized (this) {
            if (state != AttemptState.ABANDONED) {
                return;
            }
            state = AttemptState.FINISHED;
            reportError = lateFailure instanceof Error && lateFailureTarget != null && claimErrorReport();
        }
        lateCompletion.accept(new LateCompletion<>(
                lateWorker == null ? null : lateWorker.session(),
                lateWorker == null ? System.nanoTime() - startedAtNanos : lateWorker.startupNanos(),
                reason,
                lateFailure,
                reportError ? Objects.requireNonNull(lateFailureTarget, "failureTarget") : null));
    }

    private TerminalDecision decideTerminal(TerminalDecision candidate) {
        terminal.compareAndSet(TerminalDecision.UNDECIDED, Objects.requireNonNull(candidate, "candidate"));
        return terminal.get();
    }

    private static BoundedFailureReporter.FailureTarget captureFailureTarget() {
        try {
            return BoundedFailureReporter.captureFailureTarget();
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    private void acceptResult() {
        state = AttemptState.ACCEPTED;
        clearCompletedResult();
    }

    private void clearCompletedResult() {
        completedWorker = null;
        completedFailure = null;
        completedFailureTarget = null;
    }

    private boolean claimErrorReport() {
        if (errorReported) {
            return false;
        }
        errorReported = true;
        return true;
    }

    enum TerminalDecision {
        UNDECIDED,
        FACTORY_COMPLETED,
        TIMED_OUT,
        INTERRUPTED,
        CLOSED
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
            BoundedFailureReporter.FailureTarget errorTarget) {

        LateCompletion {
            Objects.requireNonNull(reason, "reason");
        }
    }

    @FunctionalInterface
    interface ThreadFactory {

        Thread unstarted(String threadPrefix, Runnable task);
    }

    private enum AttemptState {
        WAITING,
        ACCEPTED,
        ABANDONED,
        FINISHED
    }
}

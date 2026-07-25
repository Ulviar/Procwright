/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns one stable process stream reference, its close permits, and its exact-once physical close.
 *
 * @hidden
 */
public final class ProcessStreamResource<T extends Closeable> {

    private final T stream;
    private final BoundedCloseDispatcher.Permit closePermit;
    private final BoundedLifecyclePublisher.Permit publicationPermit;
    private final Object closeClaimLock;
    private final Consumer<? super Throwable> inlineCloseFailureHandler;
    private final BiConsumer<BoundedFailureReporter.FailureTarget, Throwable> failureReporter;
    private final AtomicBoolean closeClaimed = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private final Object closeFailureLock = new Object();
    private Throwable closeFailure;

    ProcessStreamResource(
            T stream,
            BoundedCloseDispatcher.Permit closePermit,
            BoundedLifecyclePublisher.Permit publicationPermit,
            Object closeClaimLock,
            Consumer<? super Throwable> inlineCloseFailureHandler,
            BiConsumer<BoundedFailureReporter.FailureTarget, Throwable> failureReporter) {
        this.stream = Objects.requireNonNull(stream, "process stream");
        this.closePermit = Objects.requireNonNull(closePermit, "closePermit");
        this.publicationPermit = Objects.requireNonNull(publicationPermit, "publicationPermit");
        this.closeClaimLock = Objects.requireNonNull(closeClaimLock, "closeClaimLock");
        this.inlineCloseFailureHandler = Objects.requireNonNull(inlineCloseFailureHandler, "inlineCloseFailureHandler");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    public T stream() {
        return stream;
    }

    public boolean closeStarted() {
        return closeClaimed.get();
    }

    public void closeInline() throws IOException {
        if (!claimClose()) {
            return;
        }
        Throwable settledFailure = null;
        try {
            closePermit.closeInline(stream);
        } catch (IOException | RuntimeException | Error closeFailure) {
            settledFailure = FailureAggregation.combine(
                    closeFailure,
                    notifyInlineCloseFailure(closeFailure),
                    "Process stream close and its failure callback both failed");
            throw closeFailure;
        } finally {
            settleClose(settledFailure);
        }
    }

    public void closeAsync(String threadPrefix, Consumer<? super Throwable> failureHandler) {
        closeAsync(threadPrefix, failureHandler, () -> {});
    }

    public void closeAsync(
            String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        Objects.requireNonNull(completionHandler, "completionHandler");
        if (!claimClose()) {
            observeExistingClose(failureHandler, completionHandler);
            return;
        }
        BoundedCloseDispatcher.DispatchOutcome dispatchOutcome;
        try {
            dispatchOutcome =
                    closePermit.dispatchOutcome(closeRequest(threadPrefix, failureHandler, completionHandler));
        } catch (RuntimeException | Error dispatchFailure) {
            recordDispatchFailure(dispatchFailure);
            throw dispatchFailure;
        }
        dispatchOutcome.rethrowStartFailure();
    }

    public BoundedCloseDispatcher.DispatchOutcome closeOwnedAsync(
            String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        Objects.requireNonNull(completionHandler, "completionHandler");
        if (!claimClose()) {
            observeExistingClose(failureHandler, completionHandler);
            return BoundedCloseDispatcher.DispatchOutcome.accepted(null);
        }
        try {
            return closePermit.dispatchOutcome(ownedCloseRequest(threadPrefix, failureHandler, completionHandler));
        } catch (RuntimeException | Error dispatchFailure) {
            recordDispatchFailure(dispatchFailure);
            throw dispatchFailure;
        }
    }

    public CompletableFuture<Void> closeCompletion() {
        return closeCompletion.copy();
    }

    public Throwable closeResult() {
        synchronized (closeFailureLock) {
            return closeCompletion.isDone() ? closeFailure : null;
        }
    }

    void rollbackConstruction(List<? super Throwable> failures) {
        Objects.requireNonNull(failures, "failures");
        rollbackConstruction(failures, true);
    }

    void rollbackConstruction() {
        rollbackConstruction(null, false);
    }

    private void rollbackConstruction(List<? super Throwable> failures, boolean retainFailures) {
        if (!claimClose()) {
            return;
        }
        try {
            closePermit.closeInline(stream);
        } catch (Throwable closeFailure) {
            if (retainFailures) {
                failures.add(closeFailure);
            }
        }
        try {
            publicationPermit.release();
        } catch (Throwable releaseFailure) {
            if (retainFailures) {
                failures.add(releaseFailure);
            }
        }
    }

    static BoundedCloseDispatcher.DispatchOutcome closePairAsync(
            ProcessStreamResource<? extends Closeable> first,
            String firstThreadPrefix,
            Consumer<? super Throwable> firstFailureHandler,
            Runnable firstCompletionHandler,
            ProcessStreamResource<? extends Closeable> second,
            String secondThreadPrefix,
            Consumer<? super Throwable> secondFailureHandler,
            Runnable secondCompletionHandler) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        BoundedCloseDispatcher.CloseRequest firstRequest =
                first.ownedCloseRequest(firstThreadPrefix, firstFailureHandler, firstCompletionHandler);
        BoundedCloseDispatcher.CloseRequest secondRequest =
                second.ownedCloseRequest(secondThreadPrefix, secondFailureHandler, secondCompletionHandler);
        return dispatchPair(first, firstRequest, second, secondRequest);
    }

    private static BoundedCloseDispatcher.DispatchOutcome dispatchPair(
            ProcessStreamResource<? extends Closeable> first,
            BoundedCloseDispatcher.CloseRequest firstRequest,
            ProcessStreamResource<? extends Closeable> second,
            BoundedCloseDispatcher.CloseRequest secondRequest) {
        if (first == second || first.closeClaimLock != second.closeClaimLock) {
            throw new IllegalArgumentException("Paired close resources must be distinct owners from one process");
        }
        synchronized (first.closeClaimLock) {
            if (first.closeClaimed.get() || second.closeClaimed.get()) {
                throw new IllegalStateException("Paired process output close has already started");
            }
            first.closeClaimed.set(true);
            second.closeClaimed.set(true);
        }
        return first.closePermit.dispatchPairOutcome(firstRequest, second.closePermit, secondRequest);
    }

    private Throwable notifyInlineCloseFailure(Throwable failure) {
        try {
            inlineCloseFailureHandler.accept(failure);
            return null;
        } catch (Throwable callbackFailure) {
            return callbackFailure;
        }
    }

    private boolean claimClose() {
        synchronized (closeClaimLock) {
            return closeClaimed.compareAndSet(false, true);
        }
    }

    private BoundedCloseDispatcher.CloseRequest closeRequest(
            String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        return BoundedCloseDispatcher.ownedCloseRequest(
                stream, threadPrefix, this::settleClose, failureHandler, completionHandler);
    }

    private BoundedCloseDispatcher.CloseRequest ownedCloseRequest(
            String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        Objects.requireNonNull(completionHandler, "completionHandler");
        return BoundedCloseDispatcher.ownedCloseRequest(
                stream,
                threadPrefix,
                failure -> settleOwnedClose(failure, failureHandler, completionHandler),
                ignored -> {},
                () -> {});
    }

    private void settleOwnedClose(
            Throwable physicalFailure, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        recordCloseFailure(physicalFailure);
        BoundedFailureReporter.FailureTarget failureTarget = captureFailureTarget();
        publicationPermit.publish(() -> runWithFailureTarget(
                failureTarget,
                () -> publishOwnedClose(physicalFailure, failureHandler, completionHandler, failureTarget)));
    }

    private void publishOwnedClose(
            Throwable physicalFailure,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler,
            BoundedFailureReporter.FailureTarget failureTarget) {
        Throwable callbackFailure = null;
        try {
            if (physicalFailure != null) {
                failureHandler.accept(physicalFailure);
            }
        } catch (Throwable failure) {
            callbackFailure = failure;
        }
        try {
            completionHandler.run();
        } catch (Throwable failure) {
            callbackFailure = FailureAggregation.combine(
                    callbackFailure, failure, "Multiple process stream close callbacks failed");
        } finally {
            closeCompletion.complete(null);
        }
        if (callbackFailure != null) {
            reportCallbackFailure(failureTarget, callbackFailure);
        }
    }

    private void reportCallbackFailure(BoundedFailureReporter.FailureTarget failureTarget, Throwable callbackFailure) {
        if (failureTarget == null) {
            BoundedFailureReporter.reportBestEffort(callbackFailure);
            return;
        }
        try {
            failureReporter.accept(failureTarget, callbackFailure);
        } catch (RuntimeException | Error reporterFailure) {
            rethrowCombined(callbackFailure, reporterFailure);
        }
    }

    private static BoundedFailureReporter.FailureTarget captureFailureTarget() {
        try {
            return BoundedFailureReporter.captureFailureTarget();
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    private static void runWithFailureTarget(BoundedFailureReporter.FailureTarget failureTarget, Runnable task) {
        if (failureTarget == null) {
            task.run();
        } else {
            BoundedFailureReporter.withFailureTarget(failureTarget, task);
        }
    }

    private static void rethrowCombined(Throwable callbackFailure, Throwable reporterFailure) {
        Throwable primary = callbackFailure instanceof RuntimeException || callbackFailure instanceof Error
                ? callbackFailure
                : reporterFailure;
        Throwable combined = FailureAggregation.combineWithPrimary(
                primary,
                List.of(callbackFailure, reporterFailure),
                "Process stream close callback and failure reporting both failed");
        if (combined instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (combined instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked process stream reporting failure", combined);
    }

    private void settleClose(Throwable physicalFailure) {
        recordCloseFailure(physicalFailure);
        publicationPermit.publish(() -> closeCompletion.complete(null));
    }

    private void recordDispatchFailure(Throwable dispatchFailure) {
        recordCloseFailure(dispatchFailure);
        publicationPermit.publish(() -> closeCompletion.complete(null));
    }

    private void recordCloseFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        synchronized (closeFailureLock) {
            closeFailure = FailureAggregation.combine(
                    closeFailure, failure, "Multiple failures occurred while closing a process stream");
        }
    }

    private void observeExistingClose(Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        closeCompletion.whenComplete((ignored, impossible) -> {
            Thread sourceThread = Thread.currentThread();
            BoundedFailureReporter.shared().execute(sourceThread, () -> {
                Throwable failure;
                synchronized (closeFailureLock) {
                    failure = closeFailure;
                }
                if (failure != null) {
                    failureHandler.accept(failure);
                }
            });
            BoundedFailureReporter.shared().execute(sourceThread, completionHandler);
        });
    }
}

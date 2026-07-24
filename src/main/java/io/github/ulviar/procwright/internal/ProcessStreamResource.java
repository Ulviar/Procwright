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
        Throwable failure = null;
        try {
            closePermit.closeInline(stream);
        } catch (IOException | RuntimeException | Error closeFailure) {
            failure = closeFailure;
            notifyInlineCloseFailure(closeFailure);
            throw closeFailure;
        } finally {
            settleClose(failure);
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

    public void closeOwnedAsync(
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
                    closePermit.dispatchOutcome(ownedCloseRequest(threadPrefix, failureHandler, completionHandler));
        } catch (RuntimeException | Error dispatchFailure) {
            recordDispatchFailure(dispatchFailure);
            throw dispatchFailure;
        }
        dispatchOutcome.rethrowStartFailure();
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

    static void closePairAsync(
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
        if (first == second || first.closeClaimLock != second.closeClaimLock) {
            throw new IllegalArgumentException("Paired close resources must be distinct owners from one process");
        }
        BoundedCloseDispatcher.CloseRequest firstRequest =
                first.ownedCloseRequest(firstThreadPrefix, firstFailureHandler, firstCompletionHandler);
        BoundedCloseDispatcher.CloseRequest secondRequest =
                second.ownedCloseRequest(secondThreadPrefix, secondFailureHandler, secondCompletionHandler);
        synchronized (first.closeClaimLock) {
            if (first.closeClaimed.get() || second.closeClaimed.get()) {
                throw new IllegalStateException("Paired process output close has already started");
            }
            first.closeClaimed.set(true);
            second.closeClaimed.set(true);
        }
        first.closePermit.dispatchPair(firstRequest, second.closePermit, secondRequest);
    }

    private void notifyInlineCloseFailure(Throwable failure) {
        try {
            inlineCloseFailureHandler.accept(failure);
        } catch (Throwable callbackFailure) {
            SuppressionSupport.attach(failure, callbackFailure);
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
        BoundedFailureReporter.FailureTarget failureTarget = BoundedFailureReporter.captureFailureTarget();
        publicationPermit.publish(() -> BoundedFailureReporter.withFailureTarget(failureTarget, () -> {
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
                callbackFailure = SuppressionSupport.combine(callbackFailure, failure);
            }
            try {
                recordCloseFailure(callbackFailure);
                if (callbackFailure != null) {
                    try {
                        failureReporter.accept(failureTarget, callbackFailure);
                    } catch (RuntimeException | Error reporterFailure) {
                        rethrowCombined(callbackFailure, reporterFailure);
                    }
                }
            } finally {
                closeCompletion.complete(null);
            }
        }));
    }

    private static void rethrowCombined(Throwable callbackFailure, Throwable reporterFailure) {
        if (callbackFailure instanceof RuntimeException runtimeFailure) {
            SuppressionSupport.attach(runtimeFailure, reporterFailure);
            throw runtimeFailure;
        }
        if (callbackFailure instanceof Error error) {
            SuppressionSupport.attach(error, reporterFailure);
            throw error;
        }
        SuppressionSupport.attach(reporterFailure, callbackFailure);
        if (reporterFailure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) reporterFailure;
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
        Throwable primaryFailure;
        synchronized (closeFailureLock) {
            if (closeFailure == null) {
                closeFailure = failure;
                return;
            }
            primaryFailure = closeFailure;
        }
        SuppressionSupport.attach(primaryFailure, failure);
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

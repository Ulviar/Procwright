/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns one stable process stream reference, its close permit, and its exact-once physical close.
 *
 * @hidden
 */
public final class ProcessStreamResource<T extends Closeable> {

    private final T stream;
    private final BoundedCloseDispatcher.Permit closePermit;
    private final Object closeClaimLock;
    private final Consumer<? super Throwable> inlineCloseFailureHandler;
    private final AtomicBoolean closeClaimed = new AtomicBoolean();
    private final CompletableFuture<CloseOutcome> closeOutcome = new CompletableFuture<>();

    ProcessStreamResource(
            T stream,
            BoundedCloseDispatcher.Permit closePermit,
            Object closeClaimLock,
            Consumer<? super Throwable> inlineCloseFailureHandler) {
        this.stream = Objects.requireNonNull(stream, "process stream");
        this.closePermit = Objects.requireNonNull(closePermit, "closePermit");
        this.closeClaimLock = Objects.requireNonNull(closeClaimLock, "closeClaimLock");
        this.inlineCloseFailureHandler = Objects.requireNonNull(inlineCloseFailureHandler, "inlineCloseFailureHandler");
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

    public CompletableFuture<CloseOutcome> closeOutcome() {
        return closeOutcome.copy();
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
    }

    public static BoundedCloseDispatcher.DispatchOutcome closePairAsync(
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
        publishOwnedClose(physicalFailure, failureHandler, completionHandler);
    }

    private void publishOwnedClose(
            Throwable physicalFailure, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
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
            completeClose(physicalFailure);
        }
        if (callbackFailure != null) {
            BoundedFailureReporter.reportBestEffort(callbackFailure);
        }
    }

    private void settleClose(Throwable physicalFailure) {
        completeClose(physicalFailure);
    }

    private void recordDispatchFailure(Throwable dispatchFailure) {
        completeClose(dispatchFailure);
    }

    private void completeClose(Throwable failure) {
        if (!closeOutcome.complete(new CloseOutcome(failure))) {
            throw new IllegalStateException("Process stream close was already completed");
        }
    }

    private void observeExistingClose(Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        closeOutcome.thenAccept(outcome -> {
            Thread sourceThread = Thread.currentThread();
            BoundedFailureReporter.shared().execute(sourceThread, () -> {
                Throwable failure = outcome.failure();
                if (failure != null) {
                    failureHandler.accept(failure);
                }
            });
            BoundedFailureReporter.shared().execute(sourceThread, completionHandler);
        });
    }

    /** Immutable physical-close result; a {@code null} failure denotes success. */
    public record CloseOutcome(Throwable failure) {}
}

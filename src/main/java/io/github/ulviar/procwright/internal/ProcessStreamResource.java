/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns one stable process stream reference and its exact-once best-effort physical close.
 *
 * @hidden
 */
public final class ProcessStreamResource<T extends Closeable> {

    private final T stream;
    private final BoundedCloseDispatcher closeDispatcher;
    private final Object closeClaimLock;
    private final Consumer<? super Throwable> inlineCloseFailureHandler;
    private final AtomicBoolean closeClaimed = new AtomicBoolean();
    private final CompletableFuture<CloseOutcome> closeOutcome = new CompletableFuture<>();

    ProcessStreamResource(
            T stream,
            BoundedCloseDispatcher closeDispatcher,
            Object closeClaimLock,
            Consumer<? super Throwable> inlineCloseFailureHandler) {
        this.stream = Objects.requireNonNull(stream, "process stream");
        this.closeDispatcher = Objects.requireNonNull(closeDispatcher, "closeDispatcher");
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
            stream.close();
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
        try {
            closeDispatcher.dispatch(closeRequest(threadPrefix, failureHandler, completionHandler));
        } catch (RuntimeException | Error dispatchFailure) {
            recordDispatchFailure(dispatchFailure);
            observeExistingClose(failureHandler, completionHandler);
        }
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
        try {
            closeDispatcher.dispatch(ownedCloseRequest(threadPrefix, failureHandler, completionHandler));
        } catch (RuntimeException | Error dispatchFailure) {
            recordDispatchFailure(dispatchFailure);
            observeExistingClose(failureHandler, completionHandler);
        }
    }

    public void closeRequiredAsync(
            String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        Objects.requireNonNull(completionHandler, "completionHandler");
        if (!claimClose()) {
            return;
        }
        try {
            closeDispatcher.dispatchRequired(ownedCloseRequest(threadPrefix, failureHandler, completionHandler));
        } catch (RuntimeException | Error dispatchFailure) {
            if (!closeOutcome.isDone()) {
                publishOwnedClose(dispatchFailure, failureHandler, completionHandler);
            }
            throw dispatchFailure;
        }
    }

    public CompletableFuture<CloseOutcome> closeOutcome() {
        return closeOutcome.copy();
    }

    Throwable rollbackConstructionAsync(String threadPrefix) {
        try {
            closeAsync(threadPrefix, BoundedFailureReporter::reportBestEffort);
            return null;
        } catch (RuntimeException | Error dispatchFailure) {
            return dispatchFailure;
        }
    }

    public static void closePairAsync(
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
        dispatchPair(first, firstRequest, second, secondRequest);
    }

    private static void dispatchPair(
            ProcessStreamResource<? extends Closeable> first,
            BoundedCloseDispatcher.CloseRequest firstRequest,
            ProcessStreamResource<? extends Closeable> second,
            BoundedCloseDispatcher.CloseRequest secondRequest) {
        if (first == second || first.closeClaimLock != second.closeClaimLock) {
            throw new IllegalArgumentException("Paired close resources must be distinct owners from one process");
        }
        if (first.closeDispatcher != second.closeDispatcher) {
            throw new IllegalArgumentException("Paired close resources must use the same dispatcher");
        }
        synchronized (first.closeClaimLock) {
            if (first.closeClaimed.get() || second.closeClaimed.get()) {
                throw new IllegalStateException("Paired process output close has already started");
            }
            first.closeClaimed.set(true);
            second.closeClaimed.set(true);
        }
        try {
            first.closeDispatcher.dispatchPair(firstRequest, secondRequest);
        } catch (RuntimeException | Error dispatchFailure) {
            first.recordDispatchFailure(dispatchFailure);
            second.recordDispatchFailure(dispatchFailure);
            first.observeExistingClose(firstRequest.failureHandler(), firstRequest.completionHandler());
            second.observeExistingClose(secondRequest.failureHandler(), secondRequest.completionHandler());
        }
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

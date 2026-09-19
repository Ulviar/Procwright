/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns one stable process stream reference, one logical close claim, and at most one physical close attempt.
 *
 * <p>Async admission or thread-start failure settles the close outcome without invoking the physical close.
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
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (!claimClose()) {
            observeExistingClose(failureHandler);
            return;
        }
        dispatchClaimedClose(closeRequest(threadPrefix, failureHandler), failureHandler);
    }

    public void closeOwnedAsync(String threadPrefix, Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (!claimClose()) {
            observeExistingClose(failureHandler);
            return;
        }
        dispatchClaimedClose(ownedCloseRequest(threadPrefix, failureHandler), failureHandler);
    }

    public void closeRequiredAsync(String threadPrefix, Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (!claimClose()) {
            return;
        }
        try {
            closeDispatcher.dispatchRequired(ownedCloseRequest(threadPrefix, failureHandler));
        } catch (RuntimeException | Error dispatchFailure) {
            if (!closeOutcome.isDone()) {
                publishOwnedClose(dispatchFailure, failureHandler);
            }
            throw dispatchFailure;
        }
    }

    private void dispatchClaimedClose(
            BoundedCloseDispatcher.CloseRequest request, Consumer<? super Throwable> failureHandler) {
        try {
            closeDispatcher.dispatch(request);
        } catch (RuntimeException | Error dispatchFailure) {
            recordDispatchFailure(dispatchFailure);
            observeExistingClose(failureHandler);
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
            ProcessStreamResource<? extends Closeable> second,
            String secondThreadPrefix,
            Consumer<? super Throwable> secondFailureHandler) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        BoundedCloseDispatcher.CloseRequest firstRequest =
                first.ownedCloseRequest(firstThreadPrefix, firstFailureHandler);
        BoundedCloseDispatcher.CloseRequest secondRequest =
                second.ownedCloseRequest(secondThreadPrefix, secondFailureHandler);
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
        first.dispatchClaimedClose(firstRequest, firstFailureHandler);
        second.dispatchClaimedClose(secondRequest, secondFailureHandler);
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
            String threadPrefix, Consumer<? super Throwable> failureHandler) {
        return BoundedCloseDispatcher.ownedCloseRequest(stream, threadPrefix, this::settleClose, failureHandler);
    }

    private BoundedCloseDispatcher.CloseRequest ownedCloseRequest(
            String threadPrefix, Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(failureHandler, "failureHandler");
        return BoundedCloseDispatcher.ownedCloseRequest(
                stream, threadPrefix, failure -> publishOwnedClose(failure, failureHandler));
    }

    private void publishOwnedClose(Throwable physicalFailure, Consumer<? super Throwable> failureHandler) {
        Throwable callbackFailure = null;
        try {
            if (physicalFailure != null) {
                failureHandler.accept(physicalFailure);
            }
        } catch (Throwable failure) {
            callbackFailure = failure;
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

    private void observeExistingClose(Consumer<? super Throwable> failureHandler) {
        closeOutcome.thenAccept(outcome -> {
            Throwable failure = outcome.failure();
            if (failure != null) {
                BoundedFailureReporter.shared().execute(Thread.currentThread(), () -> failureHandler.accept(failure));
            }
        });
    }

    /** Immutable close outcome, including dispatch failures before physical close; a {@code null} failure denotes success. */
    public record CloseOutcome(Throwable failure) {}
}

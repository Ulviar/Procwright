package com.github.ulviar.icli.integration;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous integration call with an explicit cancellation hook.
 *
 * @param <T> response type
 */
public final class CancellableCall<T> {

    private final CompletableFuture<T> completion;
    private final Runnable cancelAction;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

    CancellableCall(CompletableFuture<T> completion, Runnable cancelAction) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.cancelAction = Objects.requireNonNull(cancelAction, "cancelAction");
    }

    /**
     * Returns a read-only completion future.
     *
     * @return completion future view
     */
    public CompletableFuture<T> completion() {
        return completion.copy();
    }

    /**
     * Requests cancellation through the integration-owned lifecycle path.
     *
     * @return {@code true} when this call observed the first cancellation request
     */
    public boolean cancel() {
        if (!cancellationRequested.compareAndSet(false, true)) {
            return false;
        }
        if (!completion.cancel(true)) {
            cancellationRequested.set(false);
            return false;
        }
        cancelAction.run();
        return true;
    }

    /**
     * Returns whether cancellation has been requested.
     *
     * @return cancellation state
     */
    public boolean cancellationRequested() {
        return cancellationRequested.get();
    }
}

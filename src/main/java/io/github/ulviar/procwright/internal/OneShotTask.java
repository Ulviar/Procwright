/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

/**
 * One-shot I/O task whose actual failure remains observable after {@link #cancel(boolean)}.
 */
final class OneShotTask<T> extends FutureTask<T> {

    private final CompletableFuture<Throwable> failure;

    private OneShotTask(Callable<T> task, CompletableFuture<Throwable> failure) {
        super(() -> call(task, failure));
        this.failure = failure;
    }

    static <T> OneShotTask<T> submit(Executor executor, Callable<T> task) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");
        OneShotTask<T> future = new OneShotTask<>(task, new CompletableFuture<>());
        try {
            executor.execute(future);
            return future;
        } catch (RuntimeException | Error submissionFailure) {
            future.cancel(false);
            throw submissionFailure;
        }
    }

    void onFailure(Consumer<? super Throwable> handler) {
        failure.thenAccept(Objects.requireNonNull(handler, "handler"));
    }

    private static <T> T call(Callable<T> task, CompletableFuture<Throwable> failure) throws Exception {
        try {
            return task.call();
        } catch (Throwable taskFailure) {
            failure.complete(taskFailure);
            if (taskFailure instanceof Exception exception) {
                throw exception;
            }
            if (taskFailure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("one-shot task failed with an unknown Throwable", taskFailure);
        }
    }
}

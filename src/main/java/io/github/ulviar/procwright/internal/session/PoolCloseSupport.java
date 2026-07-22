/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class PoolCloseSupport {

    private PoolCloseSupport() {}

    static CompletableFuture<Void> asyncView(CompletableFuture<Void> cleanup, FailureFactory failures) {
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(failures, "failures");
        CompletableFuture<Void> view = new CompletableFuture<>();
        cleanup.whenComplete((ignored, failure) -> {
            if (failure == null) {
                view.complete(null);
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof Error error) {
                view.completeExceptionally(error);
            } else {
                view.completeExceptionally(Objects.requireNonNull(failures.workerFailed(cause), "worker failure"));
            }
        });
        return view;
    }

    static void await(CompletableFuture<Void> cleanup, Duration timeout, FailureFactory failures) {
        Objects.requireNonNull(cleanup, "cleanup");
        Duration configuredTimeout = DurationSupport.requirePositive(timeout, "timeout");
        Objects.requireNonNull(failures, "failures");
        try {
            cleanup.get(DurationSupport.saturatedNanos(configuredTimeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw Objects.requireNonNull(failures.drainTimeout(configuredTimeout), "drain timeout failure");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw Objects.requireNonNull(failures.interrupted(exception), "interrupted failure");
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception.getCause());
            if (cause instanceof Error error) {
                throw error;
            }
            throw Objects.requireNonNull(failures.workerFailed(cause), "worker failure");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    interface FailureFactory {

        RuntimeException drainTimeout(Duration timeout);

        RuntimeException interrupted(InterruptedException cause);

        RuntimeException workerFailed(Throwable cause);
    }
}

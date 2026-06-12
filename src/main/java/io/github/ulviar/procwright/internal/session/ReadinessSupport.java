/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class ReadinessSupport {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private ReadinessSupport() {}

    public static <T> void check(T target, Consumer<? super T> readinessProbe, Duration timeout, Runnable close) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(readinessProbe, "readinessProbe");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(close, "close");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("readinessTimeout must be positive");
        }

        CompletableFuture<Void> ready = new CompletableFuture<>();
        Thread thread = Threading.start("procwright-readiness-", () -> {
            try {
                readinessProbe.accept(target);
                ready.complete(null);
            } catch (Throwable throwable) {
                ready.completeExceptionally(throwable);
            }
        });
        try {
            ready.get(DurationSupport.saturatedMillis(timeout), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            thread.interrupt();
            closeQuietly(close, exception);
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.READINESS_TIMEOUT, "Session readiness probe timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            thread.interrupt();
            closeQuietly(close, exception);
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.READINESS_FAILED,
                    "Interrupted while waiting for session readiness",
                    exception);
        } catch (ExecutionException exception) {
            closeQuietly(close, exception);
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.READINESS_FAILED,
                    "Session readiness probe failed",
                    exception.getCause());
        }
    }

    private static void closeQuietly(Runnable close, Exception primaryFailure) {
        try {
            close.run();
        } catch (RuntimeException closeFailure) {
            primaryFailure.addSuppressed(closeFailure);
        }
    }
}

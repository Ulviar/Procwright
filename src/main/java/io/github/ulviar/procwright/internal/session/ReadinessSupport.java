/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
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

        long deadlineNanos = DurationSupport.deadlineFromNow(timeout);
        try {
            BoundedTaskRunner.run(BoundedTaskRunner.READINESS_PROBES, "procwright-readiness-", deadlineNanos, () -> {
                readinessProbe.accept(target);
                return null;
            });
        } catch (TimeoutException exception) {
            throw closePreserving(
                    close,
                    new CommandExecutionException(
                            CommandExecutionException.Reason.READINESS_TIMEOUT,
                            "Session readiness probe timed out",
                            exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw closePreserving(
                    close,
                    new CommandExecutionException(
                            CommandExecutionException.Reason.READINESS_FAILED,
                            "Interrupted while waiting for session readiness",
                            exception));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                closePreserving(close, error);
                throw error;
            }
            throw closePreserving(
                    close,
                    new CommandExecutionException(
                            CommandExecutionException.Reason.READINESS_FAILED,
                            "Session readiness probe failed",
                            cause));
        }
    }

    private static <T extends Throwable> T closePreserving(Runnable close, T primaryFailure) {
        try {
            close.run();
        } catch (Throwable closeFailure) {
            SuppressionSupport.attach(primaryFailure, closeFailure);
        }
        return primaryFailure;
    }
}

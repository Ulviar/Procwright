package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.internal.DurationSupport;
import io.github.ulviar.icli.internal.Threading;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

final class WorkerHookSupport {

    private WorkerHookSupport() {}

    static <T> T run(
            String threadPrefix,
            Duration timeout,
            Supplier<T> hook,
            Supplier<? extends RuntimeException> timedOut,
            Function<Throwable, ? extends RuntimeException> failed) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(timedOut, "timedOut");
        Objects.requireNonNull(failed, "failed");

        CompletableFuture<T> completion = new CompletableFuture<>();
        Thread thread = Threading.start(threadPrefix, () -> {
            try {
                completion.complete(hook.get());
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        });
        try {
            return completion.get(Math.max(1, DurationSupport.saturatedMillis(timeout)), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            thread.interrupt();
            throw timedOut.get();
        } catch (InterruptedException exception) {
            thread.interrupt();
            Thread.currentThread().interrupt();
            throw timedOut.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            throw failed.apply(cause);
        }
    }

    static Duration boundedTimeout(Duration timeout, long deadlineNanos) {
        Objects.requireNonNull(timeout, "timeout");
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(Math.max(1, Math.min(DurationSupport.saturatedNanos(timeout), remainingNanos)));
    }
}

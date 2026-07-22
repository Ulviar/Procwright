/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
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
            Function<InterruptedException, ? extends RuntimeException> interrupted,
            Function<Throwable, ? extends RuntimeException> failed) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(timedOut, "timedOut");
        Objects.requireNonNull(interrupted, "interrupted");
        Objects.requireNonNull(failed, "failed");

        long deadlineNanos = DurationSupport.deadlineFromNow(timeout);
        try {
            return BoundedTaskRunner.run(BoundedTaskRunner.WORKER_HOOKS, threadPrefix, deadlineNanos, hook::get);
        } catch (TimeoutException exception) {
            throw timedOut.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw interrupted.apply(exception);
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

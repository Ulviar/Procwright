/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixed-parallelism executor for delayed replenishment attempts.
 *
 * <p>Each {@link PoolReplenisher} coalesces its work to one pending attempt, so queued work grows with live pools
 * rather than retry frequency.
 */
final class PoolReplenishmentScheduler {

    private static final int PARALLELISM = 8;
    private static final long IDLE_TIMEOUT_SECONDS = 30;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final ScheduledThreadPoolExecutor EXECUTOR = createExecutor();

    private PoolReplenishmentScheduler() {}

    static PoolReplenisher.Cancellation schedule(Runnable task, Duration delay) {
        Duration selectedDelay = DurationSupport.requireNonNegative(delay, "delay");
        EXECUTOR.prestartAllCoreThreads();
        var scheduled = EXECUTOR.schedule(
                Objects.requireNonNull(task, "task"),
                DurationSupport.saturatedNanos(selectedDelay),
                TimeUnit.NANOSECONDS);
        return () -> scheduled.cancel(false);
    }

    private static ScheduledThreadPoolExecutor createExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(PARALLELISM, task -> {
            Thread thread = Threading.unstartedPlatformNonInheriting(
                    "procwright-pool-replenishment-" + THREAD_SEQUENCE.getAndIncrement(), task);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            return thread;
        });
        executor.setKeepAliveTime(IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        try {
            executor.prestartAllCoreThreads();
            return executor;
        } catch (RuntimeException | Error failure) {
            executor.shutdownNow();
            throw failure;
        }
    }
}

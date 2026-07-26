/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Integration-test access to internal pool observations.
 */
public final class PoolTestAccess {

    private PoolTestAccess() {}

    public static int availableWorkerHookPermits() {
        return BoundedTaskLimits.WORKER_HOOKS.availablePermits();
    }

    public static boolean awaitLineMetrics(
            PooledLineSession pool, Predicate<PooledSessionMetrics> condition, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(pool, "pool");
        return awaitMetrics(pool::metrics, condition, timeout);
    }

    public static boolean awaitProtocolMetrics(
            PooledProtocolSession<?, ?> pool, Predicate<PooledSessionMetrics> condition, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(pool, "pool");
        return awaitMetrics(pool::metrics, condition, timeout);
    }

    private static boolean awaitMetrics(
            Supplier<PooledSessionMetrics> metrics, Predicate<PooledSessionMetrics> condition, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        long deadlineNanos = DurationSupport.deadlineFromNow(DurationSupport.requirePositive(timeout, "timeout"));
        while (!condition.test(metrics.get())) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(5)));
        }
        return true;
    }
}

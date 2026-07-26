/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

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
        if (!(pool instanceof DefaultPooledLineSession defaultPool)) {
            throw new IllegalArgumentException("pool is not a Procwright line pool");
        }
        return defaultPool.awaitMetrics(condition, timeout);
    }

    public static boolean awaitProtocolMetrics(
            PooledProtocolSession<?, ?> pool, Predicate<PooledSessionMetrics> condition, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(pool, "pool");
        if (!(pool instanceof DefaultPooledProtocolSession<?, ?> defaultPool)) {
            throw new IllegalArgumentException("pool is not a Procwright protocol pool");
        }
        return defaultPool.awaitMetrics(condition, timeout);
    }
}

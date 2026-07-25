/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.Objects;

/** Owns immutable pool configuration and worker reuse/replenishment policy. */
final class WorkerPoolPolicy {

    private final int maxSize;
    private final int warmupSize;
    private final int minIdle;
    private final Duration acquireTimeout;
    private final Duration closeTimeout;
    private final int maxRequestsPerWorker;
    private final Duration maxWorkerAge;
    private final boolean backgroundReplenishment;

    WorkerPoolPolicy(WorkerPoolSettings<?> settings) {
        WorkerPoolSettings<?> configured = Objects.requireNonNull(settings, "settings");
        maxSize = configured.maxSize();
        warmupSize = configured.warmupSize();
        minIdle = configured.minIdle();
        acquireTimeout = configured.acquireTimeout();
        closeTimeout = configured.closeTimeout();
        maxRequestsPerWorker = configured.maxRequestsPerWorker();
        maxWorkerAge = configured.maxWorkerAge();
        backgroundReplenishment = configured.backgroundReplenishment();
    }

    int maxSize() {
        return maxSize;
    }

    int warmupSize() {
        return warmupSize;
    }

    Duration acquireTimeout() {
        return acquireTimeout;
    }

    Duration closeTimeout() {
        return closeTimeout;
    }

    boolean replenishmentEnabled() {
        return backgroundReplenishment && minIdle > 0;
    }

    boolean needsReplenishment(int liveWorkers, int idleWorkers, int replenishingStarts) {
        return liveWorkers < maxSize && idleWorkers + replenishingStarts < minIdle;
    }

    PooledWorkerRetireReason retirementReasonFor(PoolWorker<?> worker) {
        Objects.requireNonNull(worker, "worker");
        if (worker.requests() >= maxRequestsPerWorker) {
            return PooledWorkerRetireReason.MAX_REQUESTS;
        }
        if (!maxWorkerAge.isZero()
                && System.nanoTime() - worker.createdAtNanos() >= DurationSupport.saturatedNanos(maxWorkerAge)) {
            return PooledWorkerRetireReason.AGE;
        }
        return null;
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.EnumMap;
import java.util.Objects;

/**
 * Owns the cumulative counters of one pool; current worker state remains owned by {@link PoolPartition}.
 *
 * <p>{@link WorkerPoolState} confines all access to its monitor.
 */
final class PoolMetrics {

    private final EnumMap<PooledWorkerRetireReason, Long> retireReasons = new EnumMap<>(PooledWorkerRetireReason.class);
    private long created;
    private long retired;
    private long completedRequests;
    private long failedRequests;
    private long failedStartups;
    private long failedWorkerCloses;
    private long totalAcquireWaitNanos;
    private long totalRequestDurationNanos;
    private long totalWorkerStartupNanos;

    void workerCreated(long startupNanos) {
        created++;
        totalWorkerStartupNanos += nonNegative(startupNanos);
    }

    void startupFailed() {
        failedStartups++;
    }

    void workerRetired(PooledWorkerRetireReason reason, boolean closeFailed) {
        retired++;
        retireReasons.merge(Objects.requireNonNull(reason, "reason"), 1L, Long::sum);
        if (closeFailed) {
            failedWorkerCloses++;
        }
    }

    void lateWorkerRetired(long startupNanos, PooledWorkerRetireReason reason, boolean closeFailed) {
        workerCreated(startupNanos);
        workerRetired(reason, closeFailed);
    }

    void acquired(long waitNanos) {
        totalAcquireWaitNanos += nonNegative(waitNanos);
    }

    void requestCompleted(boolean successful, long durationNanos) {
        if (successful) {
            completedRequests++;
        } else {
            failedRequests++;
        }
        totalRequestDurationNanos += nonNegative(durationNanos);
    }

    PooledSessionMetrics snapshot(PoolPartition.Counts workers) {
        Objects.requireNonNull(workers, "workers");
        return new PooledSessionMetrics(
                workers.size(),
                workers.idle(),
                workers.leased(),
                workers.starting(),
                workers.retiring(),
                created,
                retired,
                completedRequests,
                failedRequests,
                failedStartups,
                failedWorkerCloses,
                totalAcquireWaitNanos,
                totalRequestDurationNanos,
                totalWorkerStartupNanos,
                retireReasons);
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }
}

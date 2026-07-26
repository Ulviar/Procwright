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

    PooledSessionMetrics snapshot(int size, int idle, int leased, int starting, int retiring) {
        return new PooledSessionMetrics(
                size,
                idle,
                leased,
                starting,
                retiring,
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

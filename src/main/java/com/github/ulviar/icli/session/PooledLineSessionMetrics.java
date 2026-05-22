package com.github.ulviar.icli.session;

import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of pooled line-session runtime counters.
 *
 * @param size current live worker count
 * @param idle current idle worker count
 * @param leased current leased worker count
 * @param starting current workers being started
 * @param retiring current workers being retired
 * @param created total workers created
 * @param retired total workers retired
 * @param completedRequests total public requests completed successfully
 * @param failedRequests total public requests completed with failure
 * @param failedStartups total failed worker startups
 * @param totalAcquireWaitNanos accumulated worker acquire wait time
 * @param totalRequestDurationNanos accumulated request duration
 * @param totalWorkerStartupNanos accumulated successful worker startup duration
 * @param retireReasons retired worker counts by reason
 */
public record PooledLineSessionMetrics(
        int size,
        int idle,
        int leased,
        int starting,
        int retiring,
        long created,
        long retired,
        long completedRequests,
        long failedRequests,
        long failedStartups,
        long totalAcquireWaitNanos,
        long totalRequestDurationNanos,
        long totalWorkerStartupNanos,
        Map<PooledWorkerRetireReason, Long> retireReasons) {

    /**
     * Creates a basic metrics snapshot.
     *
     * @param size current live worker count
     * @param idle current idle worker count
     * @param leased current leased worker count
     * @param created total workers created
     * @param retired total workers retired
     * @param completedRequests total public requests completed successfully
     * @param failedRequests total public requests completed with failure
     */
    public PooledLineSessionMetrics(
            int size, int idle, int leased, long created, long retired, long completedRequests, long failedRequests) {
        this(size, idle, leased, 0, 0, created, retired, completedRequests, failedRequests, 0, 0, 0, 0, Map.of());
    }

    /**
     * Creates a metrics snapshot.
     *
     * @param size current live worker count
     * @param idle current idle worker count
     * @param leased current leased worker count
     * @param starting current workers being started
     * @param retiring current workers being retired
     * @param created total workers created
     * @param retired total workers retired
     * @param completedRequests total public requests completed successfully
     * @param failedRequests total public requests completed with failure
     * @param failedStartups total failed worker startups
     * @param totalAcquireWaitNanos accumulated worker acquire wait time
     * @param totalRequestDurationNanos accumulated request duration
     * @param totalWorkerStartupNanos accumulated successful worker startup duration
     * @param retireReasons retired worker counts by reason
     */
    public PooledLineSessionMetrics {
        requireNonNegative(size, "size");
        requireNonNegative(idle, "idle");
        requireNonNegative(leased, "leased");
        requireNonNegative(starting, "starting");
        requireNonNegative(retiring, "retiring");
        requireNonNegative(created, "created");
        requireNonNegative(retired, "retired");
        requireNonNegative(completedRequests, "completedRequests");
        requireNonNegative(failedRequests, "failedRequests");
        requireNonNegative(failedStartups, "failedStartups");
        requireNonNegative(totalAcquireWaitNanos, "totalAcquireWaitNanos");
        requireNonNegative(totalRequestDurationNanos, "totalRequestDurationNanos");
        requireNonNegative(totalWorkerStartupNanos, "totalWorkerStartupNanos");
        retireReasons = Map.copyOf(Objects.requireNonNull(retireReasons, "retireReasons"));
        if (idle > size) {
            throw new IllegalArgumentException("idle must not exceed size");
        }
        if (leased > size) {
            throw new IllegalArgumentException("leased must not exceed size");
        }
        if ((long) idle + leased > size) {
            throw new IllegalArgumentException("idle plus leased must not exceed size");
        }
        if (retired > created) {
            throw new IllegalArgumentException("retired must not exceed created");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}

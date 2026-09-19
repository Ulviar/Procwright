/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of one pooled-session runtime's counters.
 *
 * <p>The snapshot describes one pool. Its {@code size} is bounded by that pool's configured maximum from 1 through 256
 * and includes starting, idle, leased, and retiring workers. A startup detached by logical pool closure is no longer
 * current pool state. If its factory later returns a worker, {@code created} and {@code retired} advance together after
 * that worker is retired. The snapshot does not report workers owned by other pools or directly opened sessions.
 *
 * <p>This immutable snapshot copies its retirement-reason map. Counters are cumulative over this pool's lifetime;
 * polling does not reset them. Rejected calls that fail argument validation need not increment request counters.
 *
 * @param size current workers known to the pool, including workers that are starting or retiring
 * @param idle current idle worker count
 * @param leased current leased worker count
 * @param starting current workers being started
 * @param retiring current workers being retired
 * @param created total workers created
 * @param retired total workers retired
 * @param completedRequests requests with a successful response, including a subsequent reset failure
 * @param failedRequests observed requests that failed before a successful response
 * @param failedStartups total worker startup attempts that failed; a successful result retired only because the pool
 *     closed is not a failed startup
 * @param failedWorkerCloses total completed worker retirements whose close reported a failure
 * @param totalAcquireWaitNanos accumulated worker acquisition time, including startup and health checks
 * @param totalRequestDurationNanos accumulated request work time, including preparation and reset but excluding acquisition
 * @param totalWorkerStartupNanos accumulated successful worker startup time, including readiness
 * @param retireReasons retired worker counts by reason
 */
public record PooledSessionMetrics(
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
        long failedWorkerCloses,
        long totalAcquireWaitNanos,
        long totalRequestDurationNanos,
        long totalWorkerStartupNanos,
        Map<PooledWorkerRetireReason, Long> retireReasons) {

    /**
     * Creates a metrics snapshot.
     *
     * @param size current workers known to the pool, including workers that are starting or retiring
     * @param idle current idle worker count
     * @param leased current leased worker count
     * @param starting current workers being started
     * @param retiring current workers being retired
     * @param created total workers created
     * @param retired total workers retired
     * @param completedRequests requests with a successful response, including a subsequent reset failure
     * @param failedRequests observed requests that failed before a successful response
     * @param failedStartups total worker startup attempts that failed; a successful result retired only because the pool
     *     closed is not a failed startup
     * @param failedWorkerCloses total completed worker retirements whose close reported a failure
     * @param totalAcquireWaitNanos accumulated worker acquisition time, including startup and health checks
     * @param totalRequestDurationNanos accumulated request work time, including preparation and reset but excluding acquisition
     * @param totalWorkerStartupNanos accumulated successful worker startup time, including readiness
     * @param retireReasons retired worker counts by reason; copied into an unmodifiable map
     * @throws IllegalArgumentException if a count or duration is negative, worker partitions do not sum to size,
     *     created workers are not accounted for, close failures exceed retirements, or reason counts do not sum to retired
     */
    public PooledSessionMetrics {
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
        requireNonNegative(failedWorkerCloses, "failedWorkerCloses");
        requireNonNegative(totalAcquireWaitNanos, "totalAcquireWaitNanos");
        requireNonNegative(totalRequestDurationNanos, "totalRequestDurationNanos");
        requireNonNegative(totalWorkerStartupNanos, "totalWorkerStartupNanos");
        retireReasons = Map.copyOf(Objects.requireNonNull(retireReasons, "retireReasons"));
        retireReasons.forEach((reason, count) -> requireNonNegative(count, "retireReasonCount"));
        if ((long) idle + leased + starting + retiring != size) {
            throw new IllegalArgumentException("size must equal idle plus leased plus starting plus retiring");
        }
        if ((long) idle + leased + retiring + retired != created) {
            throw new IllegalArgumentException("created must equal retired plus idle plus leased plus retiring");
        }
        if (failedWorkerCloses > retired) {
            throw new IllegalArgumentException("failedWorkerCloses must not exceed retired");
        }
        if (retirementCount(retireReasons) != retired) {
            throw new IllegalArgumentException("retireReasons must account for every retired worker");
        }
    }

    private static long retirementCount(Map<PooledWorkerRetireReason, Long> retireReasons) {
        long total = 0;
        try {
            for (long count : retireReasons.values()) {
                total = Math.addExact(total, count);
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("retireReasons total is too large", overflow);
        }
        return total;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}

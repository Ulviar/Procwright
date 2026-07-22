/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of pooled protocol-session runtime counters.
 *
 * <p>This snapshot describes one pool. Its {@code size} is bounded by that pool's configured maximum from 1 through 256,
 * which neither reserves nor reports process-wide capacity. Across all line and protocol pools, a separate limit admits
 * at most 256 aggregate workers from before factory invocation through completed physical retirement, and another limit
 * retains at most 256 pool-completion owners and their pools concurrently. This snapshot exposes neither remaining
 * aggregate capacity nor any inter-pool acquisition order.
 *
 * @param size current occupied pool slots, including workers that are starting or retiring
 * @param idle current idle worker count
 * @param leased current leased worker count
 * @param starting current workers being started
 * @param retiring current workers being retired
 * @param created total workers created
 * @param retired total workers retired
 * @param completedRequests total public requests completed successfully
 * @param failedRequests total public requests completed with failure
 * @param failedStartups total worker factory invocations that failed or whose result could not be accepted
 * @param failedWorkerCloses total completed worker retirements whose close reported a failure
 * @param totalAcquireWaitNanos accumulated worker acquire wait time
 * @param totalRequestDurationNanos accumulated request duration
 * @param totalWorkerStartupNanos accumulated successful worker startup duration
 * @param retireReasons retired worker counts by reason
 */
public record PooledProtocolSessionMetrics(
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
     * @param size current occupied pool slots, including workers that are starting or retiring
     * @param idle current idle worker count
     * @param leased current leased worker count
     * @param starting current workers being started
     * @param retiring current workers being retired
     * @param created total workers created
     * @param retired total workers retired
     * @param completedRequests total public requests completed successfully
     * @param failedRequests total public requests completed with failure
     * @param failedStartups total worker factory invocations that failed or whose result could not be accepted
     * @param failedWorkerCloses total completed worker retirements whose close reported a failure
     * @param totalAcquireWaitNanos accumulated worker acquire wait time
     * @param totalRequestDurationNanos accumulated request duration
     * @param totalWorkerStartupNanos accumulated successful worker startup duration
     * @param retireReasons retired worker counts by reason
     */
    public PooledProtocolSessionMetrics {
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

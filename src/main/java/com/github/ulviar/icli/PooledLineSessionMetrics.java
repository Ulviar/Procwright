package com.github.ulviar.icli;

/**
 * Snapshot of pooled line-session runtime counters.
 *
 * @param size current live worker count
 * @param idle current idle worker count
 * @param leased current leased worker count
 * @param created total workers created
 * @param retired total workers retired
 * @param completedRequests total public requests completed successfully
 * @param failedRequests total public requests completed with failure
 */
public record PooledLineSessionMetrics(
        int size, int idle, int leased, long created, long retired, long completedRequests, long failedRequests) {

    /**
     * Creates a metrics snapshot.
     */
    public PooledLineSessionMetrics {
        requireNonNegative(size, "size");
        requireNonNegative(idle, "idle");
        requireNonNegative(leased, "leased");
        requireNonNegative(created, "created");
        requireNonNegative(retired, "retired");
        requireNonNegative(completedRequests, "completedRequests");
        requireNonNegative(failedRequests, "failedRequests");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}

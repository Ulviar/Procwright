/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import org.junit.jupiter.api.Test;

final class PoolMetricsTest {

    @Test
    void snapshotCombinesCurrentPartitionCountsWithCumulativeEvents() {
        PoolMetrics metrics = new PoolMetrics();
        metrics.workerCreated(11);
        metrics.workerCreated(-1);
        metrics.workerCreated(0);
        metrics.workerCreated(0);
        metrics.workerCreated(0);
        metrics.startupFailed();
        metrics.workerRetired(PooledWorkerRetireReason.AGE, false);
        metrics.workerRetired(PooledWorkerRetireReason.HEALTH_FAILED, true);
        metrics.acquired(7);
        metrics.acquired(-1);
        metrics.requestCompleted(true, 13);
        metrics.requestCompleted(false, 17);

        PooledSessionMetrics snapshot = metrics.snapshot(3, 1, 1, 0, 1);

        assertEquals(3, snapshot.size());
        assertEquals(1, snapshot.idle());
        assertEquals(1, snapshot.leased());
        assertEquals(0, snapshot.starting());
        assertEquals(1, snapshot.retiring());
        assertEquals(5, snapshot.created());
        assertEquals(2, snapshot.retired());
        assertEquals(1, snapshot.completedRequests());
        assertEquals(1, snapshot.failedRequests());
        assertEquals(1, snapshot.failedStartups());
        assertEquals(1, snapshot.failedWorkerCloses());
        assertEquals(7, snapshot.totalAcquireWaitNanos());
        assertEquals(30, snapshot.totalRequestDurationNanos());
        assertEquals(11, snapshot.totalWorkerStartupNanos());
        assertEquals(1, snapshot.retireReasons().get(PooledWorkerRetireReason.AGE));
        assertEquals(1, snapshot.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.retireReasons()
                .put(PooledWorkerRetireReason.CLOSED, 1L));
    }

    @Test
    void retirementRequiresAnExplicitReason() {
        PoolMetrics metrics = new PoolMetrics();

        assertThrows(NullPointerException.class, () -> metrics.workerRetired(null, false));
    }
}

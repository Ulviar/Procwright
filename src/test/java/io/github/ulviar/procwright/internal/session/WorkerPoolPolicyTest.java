/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class WorkerPoolPolicyTest {

    @Test
    void idleAndReplenishingWorkersBothSatisfyMinIdle() {
        WorkerPoolPolicy policy = new WorkerPoolPolicy(settings(3, 2, 10));

        assertTrue(policy.needsReplenishment(1, 1, 0));

        assertFalse(policy.needsReplenishment(2, 1, 1));
    }

    @Test
    void requestLimitRetiresWorkerAtTheConfiguredBoundary() {
        WorkerPoolPolicy policy = new WorkerPoolPolicy(settings(1, 0, 2));
        PoolWorker<String> worker = worker(PoolWorker.StartupPurpose.DEMAND);
        worker.recordRequest();
        assertNull(policy.retirementReasonFor(worker));

        worker.recordRequest();

        assertEquals(PooledWorkerRetireReason.MAX_REQUESTS, policy.retirementReasonFor(worker));
    }

    private static PoolWorker<String> worker(PoolWorker.StartupPurpose purpose) {
        return new PoolWorker<>(
                session -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success()), purpose);
    }

    private static WorkerPoolSettings<Object> settings(int maxSize, int minIdle, int maxRequests) {
        return WorkerPoolSettings.defaults()
                .withMaxSize(maxSize)
                .withMinIdle(minIdle)
                .withMaxRequestsPerWorker(maxRequests);
    }
}

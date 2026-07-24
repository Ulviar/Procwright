/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class WorkerPoolPolicyTest {

    @Test
    void minIdleRequiresBackgroundOwnership() {
        Options options = new Options(2, 0, 1, false, 10);

        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolPolicy(options));
    }

    @Test
    void idleAndReplenishingWorkersBothSatisfyMinIdle() {
        WorkerPoolPolicy policy = new WorkerPoolPolicy(new Options(3, 0, 2, true, 10));
        PoolPartition<PoolWorker<String>> partition = new PoolPartition<>(3);
        PoolWorker<String> idle = new PoolWorker<>();
        PoolWorker<String> replenishing = new PoolWorker<>();
        replenishing.startupPurpose(PoolWorker.StartupPurpose.REPLENISHMENT);
        partition.addStarting(idle);
        partition.startingToLeased(idle);
        partition.leasedToIdle(idle);

        assertTrue(policy.needsReplenishment(partition));

        partition.addStarting(replenishing);
        assertFalse(policy.needsReplenishment(partition));
    }

    @Test
    void requestLimitRetiresWorkerAtTheConfiguredBoundary() {
        WorkerPoolPolicy policy = new WorkerPoolPolicy(new Options(1, 0, 0, false, 2));
        PoolWorker<String> worker = new PoolWorker<>();
        worker.recordRequest();
        assertNull(policy.retirementReasonFor(worker));

        worker.recordRequest();

        assertEquals(PooledWorkerRetireReason.MAX_REQUESTS, policy.retirementReasonFor(worker));
    }

    private record Options(int maxSize, int warmupSize, int minIdle, boolean background, int maxRequests)
            implements WorkerPoolPolicy.Options {

        @Override
        public Duration acquireTimeout() {
            return Duration.ofSeconds(1);
        }

        @Override
        public int maxRequestsPerWorker() {
            return maxRequests;
        }

        @Override
        public Duration maxWorkerAge() {
            return Duration.ZERO;
        }

        @Override
        public boolean backgroundReplenishment() {
            return background;
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;

abstract class PooledLineSessionWorkerLifecycleIntegrationSupport extends PooledLineSessionIntegrationSupport {

    static boolean awaitIdle(PooledLineSession pool, int expectedIdle) throws InterruptedException {
        return PoolTestAccess.awaitLineMetrics(pool, metrics -> metrics.idle() == expectedIdle, Duration.ofSeconds(2));
    }

    static boolean awaitRetireReason(PooledLineSession pool, PooledWorkerRetireReason reason, long expectedCount) {
        try {
            return PoolTestAccess.awaitLineMetrics(
                    pool,
                    metrics -> metrics.retireReasons().getOrDefault(reason, 0L) == expectedCount,
                    Duration.ofSeconds(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

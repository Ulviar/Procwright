/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledRequestPreparationTest extends WorkerPoolControllerTestSupport {

    @Test
    void localPreparationErrorPreservesTheWorkerAndDoesNotRunRequestOrReset() throws Exception {
        AssertionError failure = new AssertionError("local preparation failed");
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger resets = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                WorkerPoolSettings.defaults().withWarmupSize(1));
        PooledRequestRunner<TestWorker> runner = new PooledRequestRunner<>(
                pool,
                () -> pool.acquire((worker, deadline) -> WorkerPoolController.HealthOutcome.HEALTHY),
                worker -> resets.incrementAndGet(),
                exception -> new PooledRequestRunner.Failure(PooledWorkerRetireReason.WORKER_FAILED, exception));
        try {
            AssertionError thrown = assertThrows(
                    AssertionError.class,
                    () -> runner.runPrepared(
                            () -> "validated",
                            prepared -> {
                                assertPartition(pool, 1, 0, 1, 0, 0);
                                throw failure;
                            },
                            (worker, prepared) -> requests.incrementAndGet()));

            assertSame(failure, thrown);
            assertEquals(0, requests.get());
            assertEquals(0, resets.get());
            assertEquals(1, pool.metrics().failedRequests());
            assertPartition(pool, 1, 1, 0, 0, 0);

            assertEquals(1, runner.run(TestWorker::id));
            assertEquals(1, resets.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(0, pool.metrics().retired());
            assertEquals(1, pool.metrics().completedRequests());
        } finally {
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }
}

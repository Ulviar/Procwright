/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.PROCESS_EXITED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerMetricsTest extends WorkerPoolControllerTestSupport {

    @Test
    void acquireRecordsOneEndToEndMeasurementAcrossHealthRetries() throws Exception {
        AtomicInteger clockReads = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                new java.util.function.Supplier<>() {
                    private final AtomicInteger ids = new AtomicInteger();

                    @Override
                    public TestWorker get() {
                        return new TestWorker(ids.incrementAndGet());
                    }
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                threadedScheduler("test-metrics-replenish-"),
                failure -> {},
                () -> switch (clockReads.incrementAndGet()) {
                    case 1 -> 100L;
                    case 2 -> 175L;
                    default -> throw new AssertionError("acquire metrics clock was read more than twice");
                });
        try {
            WorkerPoolState.Lease<TestWorker> lease =
                    pool.acquire((worker, deadline) -> worker.id() == 1 ? PROCESS_EXITED : HEALTHY);

            assertEquals(2, clockReads.get());
            assertEquals(75L, pool.metrics().totalAcquireWaitNanos());
            pool.releaseReusable(lease);
        } finally {
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestDurationIncludesEncodingAndExcludesAcquireWaitDeterministically() {
        AtomicReference<Long> now = new AtomicReference<>(100L);
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                threadedScheduler("test-replenish-"),
                failure -> {},
                now::get);

        RequestObservation observation = pool.observeRequest();
        now.set(150L);
        observation.pauseForAcquire();
        now.set(1_150L);
        observation.resumeAfterAcquire();
        now.set(1_220L);
        observation.succeed();

        assertEquals(120L, pool.metrics().totalRequestDurationNanos());
        assertEquals(1, pool.metrics().completedRequests());
        pool.closeAsync();
    }
}

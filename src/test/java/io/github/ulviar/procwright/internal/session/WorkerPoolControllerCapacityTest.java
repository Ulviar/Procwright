/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerCapacityTest extends WorkerPoolControllerTestSupport {

    @Test
    void warmupFailureDoesNotPoisonLaterPoolConstruction() throws Exception {
        IllegalStateException startupFailure = new IllegalStateException("warmup failed");
        WorkerPoolSettings<?> options =
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false);

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> controller(
                        () -> {
                            throw startupFailure;
                        },
                        worker -> {},
                        options));

        assertEquals(FailureKind.STARTUP_FAILED, observed.kind);
        assertSame(startupFailure, observed.getCause());

        WorkerPoolController<TestWorker> recovered = controller(() -> new TestWorker(1), worker -> {}, options);
        assertPartition(recovered, 1, 1, 0, 0, 0);
        recovered.closeAsync().get(1, TimeUnit.SECONDS);
        assertPartition(recovered, 0, 0, 0, 0, 0);
    }

    @Test
    void warmupFillsConfiguredPoolWithoutExceedingMaxSize() throws Exception {
        int maxSize = 4;
        AtomicInteger created = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {},
                settings(maxSize, maxSize, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            assertEquals(maxSize, created.get());
            assertPartition(pool, maxSize, maxSize, 0, 0, 0);
        } finally {
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void independentPoolsCanCollectivelyOwnMoreThanThePerPoolMaximum() throws Exception {
        int workersPerPool = WorkerPoolSettings.MAX_SIZE / 2 + 1;
        WorkerPoolSettings<?> options = settings(
                workersPerPool, workersPerPool, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false);
        AtomicInteger created = new AtomicInteger();
        WorkerPoolController<TestWorker> first =
                controller(() -> new TestWorker(created.incrementAndGet()), worker -> {}, options);
        WorkerPoolController<TestWorker> second = null;
        try {
            second = controller(() -> new TestWorker(created.incrementAndGet()), worker -> {}, options);

            assertEquals(workersPerPool * 2, created.get());
            assertPartition(first, workersPerPool, workersPerPool, 0, 0, 0);
            assertPartition(second, workersPerPool, workersPerPool, 0, 0, 0);
        } finally {
            first.closeAsync().get(5, TimeUnit.SECONDS);
            if (second != null) {
                second.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }
}

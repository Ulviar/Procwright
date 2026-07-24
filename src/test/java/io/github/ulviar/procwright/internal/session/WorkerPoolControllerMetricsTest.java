/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerMetricsTest extends WorkerPoolControllerTestSupport {

    @Test
    void acquireMetricFailureCannotStrandLeaseBeforeHandoff() throws Exception {
        AssertionError metricFailure = new AssertionError("acquire metric clock failed");
        AtomicInteger clockCalls = new AtomicInteger();
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> physicalCloses.incrementAndGet(),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                (thread, failure) -> {},
                () -> {
                    if (clockCalls.incrementAndGet() == 2) {
                        throw metricFailure;
                    }
                    return 100L;
                },
                null);
        try {
            AssertionError observed =
                    assertThrows(AssertionError.class, () -> pool.acquire((worker, deadline) -> HEALTHY));

            assertSame(metricFailure, observed);
            assertEquals(0, observed.getSuppressed().length);
            assertEquals(2, clockCalls.get());
            assertTrue(pool.awaitMetrics(metrics -> metrics.retired() == 1, Duration.ofSeconds(1)));
            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertEquals(0, pool.metrics().totalAcquireWaitNanos());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
            assertPartition(pool, 0, 0, 0, 0, 0);

            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void requestDurationIncludesEncodingAndExcludesAcquireWaitDeterministically() {
        AtomicReference<Long> now = new AtomicReference<>(100L);
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                (thread, failure) -> {},
                now::get,
                null);

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

    @Test
    void awaitMetricsWakesForAcquireWaitWithoutAnotherStateTransition() throws Exception {
        AtomicReference<Long> now = new AtomicReference<>(100L);
        CountDownLatch predicateStarted = new CountDownLatch(1);
        CountDownLatch waiterObservedLease = new CountDownLatch(1);
        CountDownLatch healthEntered = new CountDownLatch(1);
        CountDownLatch releaseHealth = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-metrics-replenish-", task),
                (thread, failure) -> {},
                now::get,
                null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> metricsWait = executor.submit(() -> pool.awaitMetrics(
                    metrics -> {
                        predicateStarted.countDown();
                        if (metrics.leased() == 1 && metrics.totalAcquireWaitNanos() == 0) {
                            waiterObservedLease.countDown();
                        }
                        return metrics.totalAcquireWaitNanos() == 75;
                    },
                    Duration.ofSeconds(1)));
            assertTrue(predicateStarted.await(1, TimeUnit.SECONDS));
            Future<WorkerPoolState.Lease<TestWorker>> acquire =
                    executor.submit(() -> pool.acquire((worker, deadline) -> {
                        healthEntered.countDown();
                        awaitIgnoringInterrupt(releaseHealth);
                        return HEALTHY;
                    }));
            assertTrue(healthEntered.await(1, TimeUnit.SECONDS));
            assertTrue(waiterObservedLease.await(1, TimeUnit.SECONDS));

            now.set(175L);
            releaseHealth.countDown();

            WorkerPoolState.Lease<TestWorker> worker = acquire.get(1, TimeUnit.SECONDS);
            assertTrue(metricsWait.get(1, TimeUnit.SECONDS));
            assertEquals(75L, pool.metrics().totalAcquireWaitNanos());
            pool.releaseReusable(worker);
        } finally {
            releaseHealth.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void awaitMetricsWakesForCompletedRequestWithoutStateTransition() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        CountDownLatch predicateStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> metricsWait = executor.submit(() -> pool.awaitMetrics(
                    metrics -> {
                        predicateStarted.countDown();
                        return metrics.completedRequests() == 1;
                    },
                    Duration.ofSeconds(1)));
            assertTrue(predicateStarted.await(1, TimeUnit.SECONDS));

            pool.observeRequest().succeed();

            assertTrue(metricsWait.get(1, TimeUnit.SECONDS));
            assertEquals(1, pool.metrics().completedRequests());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void awaitMetricsWakesForFailedRequestWithoutStateTransition() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        CountDownLatch predicateStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> metricsWait = executor.submit(() -> pool.awaitMetrics(
                    metrics -> {
                        predicateStarted.countDown();
                        return metrics.failedRequests() == 1;
                    },
                    Duration.ofSeconds(1)));
            assertTrue(predicateStarted.await(1, TimeUnit.SECONDS));

            pool.observeRequest().fail();

            assertTrue(metricsWait.get(1, TimeUnit.SECONDS));
            assertEquals(1, pool.metrics().failedRequests());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void awaitMetricsEvaluatesPredicateOutsidePoolMonitor() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        CountDownLatch predicateEntered = new CountDownLatch(1);
        CountDownLatch releasePredicate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> wait = executor.submit(() -> pool.awaitMetrics(
                    metrics -> {
                        predicateEntered.countDown();
                        awaitIgnoringInterrupt(releasePredicate);
                        return true;
                    },
                    Duration.ofSeconds(1)));
            assertTrue(predicateEntered.await(1, TimeUnit.SECONDS));

            Future<PoolMetrics.Snapshot> concurrentSnapshot = executor.submit(pool::metrics);
            concurrentSnapshot.get(200, TimeUnit.MILLISECONDS);

            releasePredicate.countDown();
            assertTrue(wait.get(1, TimeUnit.SECONDS));
        } finally {
            releasePredicate.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }
}

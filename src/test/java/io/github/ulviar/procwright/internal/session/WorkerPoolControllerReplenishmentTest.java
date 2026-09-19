/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerReplenishmentTest extends WorkerPoolControllerTestSupport {

    @Test
    void minIdleIsEstablishedAndMaintainedWhileWorkerIsLeased() throws Exception {
        AtomicInteger created = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {},
                settings(2, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO));
        try {
            assertTrue(awaitMetrics(pool, metrics -> metrics.idle() == 1, Duration.ofSeconds(1)));

            WorkerPoolState.Lease<TestWorker> leased = pool.acquire((worker, deadline) -> HEALTHY);

            assertTrue(awaitMetrics(pool, metrics -> metrics.idle() == 1, Duration.ofSeconds(1)));
            assertEquals(2, pool.metrics().size());
            assertEquals(1, pool.metrics().leased());
            pool.releaseReusable(leased);
        } finally {
            pool.closeAsync();
            assertTrue(pool.closeAsync().get(1, TimeUnit.SECONDS) == null);
        }
    }

    @Test
    void failedBackgroundReplenishmentCannotStrandDrain() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch replenishmentEntered = new CountDownLatch(1);
        CountDownLatch failReplenishment = new CountDownLatch(1);
        AtomicReference<Thread> replenishmentThread = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        return new TestWorker(1);
                    }
                    replenishmentThread.set(Thread.currentThread());
                    replenishmentEntered.countDown();
                    awaitIgnoringInterrupt(failReplenishment);
                    throw new IllegalStateException("startup failed");
                },
                worker -> {},
                settings(1, 1, 1, Duration.ofSeconds(1), 1, Duration.ZERO));

        try {
            WorkerPoolState.Lease<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);
            pool.recordRequestAndRetirementReason(worker);
            pool.releaseReusable(worker);
            assertTrue(replenishmentEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            failReplenishment.countDown();

            joinThread(replenishmentThread, "failed replenishment startup");
            assertEquals(0, pool.metrics().size());
            assertEquals(1, pool.metrics().failedStartups());
        } finally {
            failReplenishment.countDown();
            pool.closeAsync();
            joinThread(replenishmentThread, "failed replenishment startup");
        }
    }

    @Test
    void lateFatalBackgroundFailureDoesNotRewriteCompletedClose() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch replenishmentEntered = new CountDownLatch(1);
        CountDownLatch failReplenishment = new CountDownLatch(1);
        CountDownLatch reportReceived = new CountDownLatch(1);
        AtomicReference<Thread> replenishmentThread = new AtomicReference<>();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AssertionError fatalFailure = new AssertionError("fatal replenishment startup");
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        return new TestWorker(1);
                    }
                    replenishmentThread.set(Thread.currentThread());
                    replenishmentEntered.countDown();
                    awaitIgnoringInterrupt(failReplenishment);
                    throw fatalFailure;
                },
                worker -> {},
                settings(1, 1, 1, Duration.ofSeconds(1), 1, Duration.ZERO),
                threadedScheduler("test-replenish-"),
                failure -> {
                    reported.compareAndSet(null, failure);
                    reportReceived.countDown();
                },
                System::nanoTime);

        try {
            WorkerPoolState.Lease<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);
            pool.recordRequestAndRetirementReason(worker);
            pool.releaseReusable(worker);
            assertTrue(replenishmentEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            failReplenishment.countDown();

            joinThread(replenishmentThread, "fatal replenishment startup");
            assertTrue(reportReceived.await(1, TimeUnit.SECONDS));
            assertSame(fatalFailure, reported.get());
            assertTrue(pool.closeAsync().isDone());
            assertEquals(0, pool.metrics().size());
            assertEquals(1, pool.metrics().failedStartups());
        } finally {
            failReplenishment.countDown();
            pool.closeAsync();
            joinThread(replenishmentThread, "fatal replenishment startup");
        }
    }

    @Test
    void replenishmentSchedulingFailureClosesPoolWithoutLeakingLeasedWorker() throws Exception {
        IllegalStateException schedulingFailure = new IllegalStateException("thread creation failed");
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                settings(2, 1, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                (task, delay) -> {
                    throw schedulingFailure;
                });
        WorkerPoolState.Lease<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);

        assertEquals(1, pool.metrics().leased());
        pool.releaseReusable(worker);

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));
        assertSame(schedulingFailure, observed.getCause());
        assertEquals(0, pool.metrics().leased());
        assertEquals(0, pool.metrics().idle());
        assertEquals(1, pool.metrics().retired());
        assertPartition(pool, 0, 0, 0, 0, 0);
    }

    @Test
    void initialReplenishmentSchedulingFailureFailsConstructionAndClosesWarmWorkers() {
        IllegalStateException schedulingFailure = new IllegalStateException("thread creation failed");
        AtomicInteger closedWorkers = new AtomicInteger();
        AtomicInteger factoryInvocations = new AtomicInteger();

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> controller(
                        () -> new TestWorker(factoryInvocations.incrementAndGet()),
                        worker -> closedWorkers.incrementAndGet(),
                        settings(2, 1, 2, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                        (task, delay) -> {
                            throw schedulingFailure;
                        }));

        assertEquals(FailureKind.RETIREMENT_FAILED, observed.kind);
        PoolFailure primary = (PoolFailure) FailureAggregation.primary(observed.getCause());
        assertSame(schedulingFailure, primary.getCause());
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(1, factoryInvocations.get());
        assertEquals(1, closedWorkers.get());
    }

    @Test
    void fatalReplenishmentSchedulingErrorIsPropagatedAndWarmWorkersAreClosed() throws Exception {
        AssertionError schedulingError = new AssertionError("thread creation invariant failed");
        AtomicInteger closedWorkers = new AtomicInteger();
        CountDownLatch workerClosed = new CountDownLatch(1);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> controller(
                        () -> new TestWorker(1),
                        worker -> {
                            closedWorkers.incrementAndGet();
                            workerClosed.countDown();
                        },
                        settings(2, 1, 2, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                        (task, delay) -> {
                            throw schedulingError;
                        }));

        assertSame(schedulingError, thrown);
        assertTrue(workerClosed.await(1, TimeUnit.SECONDS));
        assertEquals(1, closedWorkers.get());
    }

    @Test
    void constructorPreservesSchedulingFailureAndClosesEveryWarmWorker() {
        AssertionError schedulingError = new AssertionError("replenishment scheduling failed");
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();

        Error thrown = assertThrows(
                Error.class,
                () -> controller(
                        () -> new TestWorker(created.incrementAndGet()),
                        worker -> {
                            closed.incrementAndGet();
                            throw new IllegalStateException("warm worker close failed: " + worker.id());
                        },
                        settings(3, 2, 3, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                        (task, delay) -> {
                            throw schedulingError;
                        }));

        assertSame(schedulingError, FailureAggregation.primary(thrown));
        assertEquals(2, created.get());
        assertEquals(2, closed.get());
    }

    @Test
    void replenishmentRetriesFailedStartupWithoutExternalActivity() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch reportReceived = new CountDownLatch(1);
        IllegalStateException startupFailure = new IllegalStateException("transient startup failure");
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw startupFailure;
                    }
                    return new TestWorker(2);
                },
                worker -> {},
                settings(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                PoolReplenishmentScheduler::schedule,
                failure -> {
                    reports.incrementAndGet();
                    reported.compareAndSet(null, failure);
                    reportReceived.countDown();
                },
                System::nanoTime);

        assertTrue(awaitMetrics(pool, metrics -> metrics.idle() == 1, Duration.ofSeconds(1)));
        assertTrue(reportReceived.await(1, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
        assertEquals(1, pool.metrics().failedStartups());
        PoolFailure diagnostic = (PoolFailure) reported.get();
        assertSame(startupFailure, diagnostic.getCause());
        assertEquals(1, reports.get());
        pool.closeAsync();
        pool.closeAsync().get(1, TimeUnit.SECONDS);
    }

    @Test
    void closeStopsReplenishmentDuringRetryBackoff() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<Runnable> initial = new AtomicReference<>();
        AtomicReference<Runnable> retry = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("startup unavailable");
                },
                worker -> {},
                settings(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                (task, delay) -> {
                    if (delay.isZero()) {
                        initial.set(task);
                    } else {
                        retry.set(task);
                    }
                    return cancellations::incrementAndGet;
                });
        try {
            initial.get().run();
            assertEquals(1, attempts.get());
            assertNotNull(retry.get());

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            int attemptsAfterDrain = attempts.get();
            retry.get().run();

            assertEquals(attemptsAfterDrain, attempts.get());
            assertEquals(1, cancellations.get());
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void lateRetrySchedulingFailureAfterSuccessfulDrainIsReportedExactlyOnce() throws Exception {
        CountDownLatch retrySchedulingEntered = new CountDownLatch(1);
        CountDownLatch releaseRetryScheduling = new CountDownLatch(1);
        CountDownLatch startupFailureReported = new CountDownLatch(1);
        CountDownLatch lateFailureReported = new CountDownLatch(1);
        AtomicInteger startupReports = new AtomicInteger();
        AtomicInteger lateReports = new AtomicInteger();
        AtomicReference<Throwable> startupReport = new AtomicReference<>();
        IllegalStateException startupFailure = new IllegalStateException("startup unavailable");
        AssertionError lateFailure = new AssertionError("late replenishment scheduling failure");
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    throw startupFailure;
                },
                worker -> {},
                settings(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                (task, delay) -> {
                    if (delay.isZero()) {
                        Threading.start("test-replenish-", task);
                        return PoolReplenisher.Cancellation.NONE;
                    }
                    retrySchedulingEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetryScheduling);
                    throw lateFailure;
                },
                failure -> {
                    if (failure == lateFailure) {
                        lateReports.incrementAndGet();
                        lateFailureReported.countDown();
                    } else {
                        startupReports.incrementAndGet();
                        startupReport.set(failure);
                        startupFailureReported.countDown();
                    }
                },
                System::nanoTime);
        try {
            assertTrue(retrySchedulingEntered.await(1, TimeUnit.SECONDS));
            assertTrue(startupFailureReported.await(1, TimeUnit.SECONDS));

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            releaseRetryScheduling.countDown();

            assertTrue(lateFailureReported.await(1, TimeUnit.SECONDS));
            assertSame(startupFailure, ((PoolFailure) startupReport.get()).getCause());
            assertEquals(1, startupReports.get());
            assertEquals(1, lateReports.get());
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            releaseRetryScheduling.countDown();
            pool.closeAsync();
        }
    }
}

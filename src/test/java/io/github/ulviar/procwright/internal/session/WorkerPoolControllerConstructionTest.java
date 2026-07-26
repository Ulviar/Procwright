/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerConstructionTest extends WorkerPoolControllerTestSupport {

    @Test
    void settingsValidationPrecedesWorkerFactory() {
        AtomicInteger factoryCalls = new AtomicInteger();

        assertThrows(
                IllegalArgumentException.class,
                () -> controller(
                        () -> new TestWorker(factoryCalls.incrementAndGet()),
                        worker -> {},
                        settings(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false)));

        assertEquals(0, factoryCalls.get());
    }

    @Test
    void fatalReplenishmentErrorAfterCommitClosesPoolWithoutExternalActivity() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        AssertionError fatalError = new AssertionError("background worker startup failed");
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch ownerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    throw fatalError;
                },
                worker -> {},
                settings(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                task -> Threading.start("test-fatal-replenish-", () -> {
                    try {
                        task.run();
                    } catch (Throwable failure) {
                        ownerFailure.set(failure);
                    } finally {
                        ownerFinished.countDown();
                    }
                }),
                (thread, failure) -> {},
                System::nanoTime,
                null);
        try {
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            releaseFactory.countDown();
            assertTrue(ownerFinished.await(1, TimeUnit.SECONDS));

            assertSame(fatalError, ownerFailure.get());
            ExecutionException drainFailure = assertThrows(
                    ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));
            assertSame(fatalError, drainFailure.getCause());
            assertEquals(1, factoryCalls.get());
            assertEquals(1, pool.metrics().failedStartups());
            assertPartition(pool, 0, 0, 0, 0, 0);
            PoolFailure closed = assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> HEALTHY));
            assertEquals(FailureKind.CLOSED, closed.kind);
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        } finally {
            releaseFactory.countDown();
            pool.closeAsync();
        }
    }

    @Test
    void queuedLateErrorReportCannotRetainAbandonedStartupSlot() throws Exception {
        CountDownLatch releaseReports = new CountDownLatch(1);
        CountDownLatch reportsStarted = new CountDownLatch(8);
        CountDownLatch reportsFinished = new CountDownLatch(8);
        for (int index = 0; index < 8; index++) {
            PoolLifecycleDispatcher.report(() -> {
                reportsStarted.countDown();
                awaitIgnoringInterrupt(releaseReports);
                reportsFinished.countDown();
            });
        }
        AssertionError lateError = new AssertionError("late startup failed");
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch reporterEntered = new CountDownLatch(1);
        CountDownLatch releaseReporter = new CountDownLatch(1);
        CountDownLatch startupFinished = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryEntered.countDown();
                    try {
                        awaitIgnoringInterrupt(releaseFactory);
                        throw lateError;
                    } finally {
                        startupFinished.countDown();
                    }
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                (thread, failure) -> {
                    reported.set(failure);
                    reporterEntered.countDown();
                    awaitIgnoringInterrupt(releaseReporter);
                },
                System::nanoTime,
                null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(reportsStarted.await(1, TimeUnit.SECONDS));
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            ExecutionException timeout = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, ((PoolFailure) timeout.getCause()).kind);
            pool.closeAsync().get(1, TimeUnit.SECONDS);

            releaseFactory.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            assertEquals(1L, reporterEntered.getCount(), "late report must remain queued behind active owners");
            assertTrue(awaitMetrics(pool, metrics -> metrics.failedStartups() == 1, Duration.ofSeconds(1)));
            assertPartition(pool, 0, 0, 0, 0, 0);

            releaseReports.countDown();
            assertTrue(reporterEntered.await(1, TimeUnit.SECONDS));
            assertSame(lateError, reported.get());
        } finally {
            releaseFactory.countDown();
            releaseReporter.countDown();
            releaseReports.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            assertTrue(reportsFinished.await(1, TimeUnit.SECONDS));
            pool.closeAsync();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedConstructionRetainsTerminalCleanupUntilLateWorkerClosesExactlyOnce() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch startupFinished = new CountDownLatch(1);
        CountDownLatch workerClosed = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        AtomicInteger closeCalls = new AtomicInteger();
        ExecutorService constructorExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<PoolFailure> construction = constructorExecutor.submit(() -> assertThrows(
                    PoolFailure.class,
                    () -> controller(
                            () -> {
                                startupThread.set(Thread.currentThread());
                                startupEntered.countDown();
                                try {
                                    awaitIgnoringInterrupt(releaseStartup);
                                    return new TestWorker(1);
                                } finally {
                                    startupFinished.countDown();
                                }
                            },
                            worker -> {
                                closeThread.set(Thread.currentThread());
                                closeCalls.incrementAndGet();
                                workerClosed.countDown();
                            },
                            settings(1, 1, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false))));

            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            PoolFailure primary = construction.get(1, TimeUnit.SECONDS);
            assertEquals(FailureKind.STARTUP_FAILED, primary.kind);
            releaseStartup.countDown();

            assertTrue(workerClosed.await(1, TimeUnit.SECONDS));
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            assertFalse(closeThread.get() == startupThread.get(), "late worker closed on startup caller thread");
            assertTrue(closeThread.get().getName().startsWith("procwright-worker-close-"));
            assertEquals(1, closeCalls.get());
        } finally {
            releaseStartup.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            constructorExecutor.shutdownNow();
            assertTrue(constructorExecutor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void failedWarmupRetainsTypedCleanupFailureWithoutMutatingThePrimary() {
        AtomicInteger starts = new AtomicInteger();
        IllegalStateException startupFailure = new IllegalStateException("second startup failed");
        IllegalStateException closeFailure = new IllegalStateException("first close failed");

        PoolFailure thrown = assertThrows(
                PoolFailure.class,
                () -> controller(
                        () -> {
                            if (starts.incrementAndGet() == 1) {
                                return new TestWorker(1);
                            }
                            throw startupFailure;
                        },
                        worker -> {
                            throw closeFailure;
                        },
                        settings(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false)));

        assertEquals(FailureKind.STARTUP_FAILED, thrown.kind);
        Throwable aggregate = thrown.getCause();
        PoolFailure primary = (PoolFailure) FailureAggregation.primary(aggregate);
        assertSame(startupFailure, primary.getCause());
        assertEquals(2, FailureAggregation.sources(aggregate).size());
        PoolFailure cleanup =
                (PoolFailure) FailureAggregation.sources(aggregate).get(1);
        assertEquals(FailureKind.RETIREMENT_FAILED, cleanup.kind);
        assertSame(closeFailure, cleanup.getCause());
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, cleanup.getSuppressed().length);
    }

    @Test
    void lateWorkerCloseFailureAfterFailedWarmupIsReportedExactlyOnce() throws Exception {
        CountDownLatch lateStartupEntered = new CountDownLatch(1);
        CountDownLatch releaseLateStartup = new CountDownLatch(1);
        AtomicReference<Thread> lateStartupThread = new AtomicReference<>();
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        CountDownLatch reportPublished = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        IllegalStateException lateCloseFailure = new IllegalStateException("late worker close failed");
        PoolFailure thrown = assertThrows(
                PoolFailure.class,
                () -> controller(
                        () -> {
                            int id = starts.incrementAndGet();
                            if (id == 2) {
                                lateStartupThread.set(Thread.currentThread());
                                lateStartupEntered.countDown();
                                awaitIgnoringInterrupt(releaseLateStartup);
                            }
                            return new TestWorker(id);
                        },
                        worker -> {
                            if (worker.id() == 2) {
                                throw lateCloseFailure;
                            }
                        },
                        settings(2, 2, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false),
                        task -> Threading.start("test-replenish-", task),
                        (thread, failure) -> {
                            reported.compareAndSet(null, failure);
                            reports.incrementAndGet();
                            reportPublished.countDown();
                        },
                        System::nanoTime,
                        null));

        try {
            assertEquals(FailureKind.STARTUP_FAILED, thrown.kind);
            assertTrue(lateStartupEntered.await(1, TimeUnit.SECONDS));
            releaseLateStartup.countDown();
            assertTrue(reportPublished.await(1, TimeUnit.SECONDS));
            assertSame(lateCloseFailure, reported.get());
            assertEquals(1, reports.get());
        } finally {
            releaseLateStartup.countDown();
            joinThread(lateStartupThread, "late warmup startup");
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void warmupTimeoutUsesStartupFailureTaxonomy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        try {
            PoolFailure failure = assertThrows(
                    PoolFailure.class,
                    () -> controller(
                            () -> {
                                startupThread.set(Thread.currentThread());
                                awaitIgnoringInterrupt(release);
                                return new TestWorker(1);
                            },
                            worker -> {},
                            settings(1, 1, 0, Duration.ofMillis(30), Integer.MAX_VALUE, Duration.ZERO, false)));

            assertEquals(FailureKind.STARTUP_FAILED, failure.kind);
        } finally {
            release.countDown();
            joinThread(startupThread, "warmup startup");
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void seriousWorkerFactoryFailureReleasesReservedCapacity() {
        AssertionError startupFailure = new AssertionError("startup failed");
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    throw startupFailure;
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

        AssertionError thrown = assertThrows(AssertionError.class, () -> pool.acquire((worker, deadline) -> HEALTHY));

        assertEquals(startupFailure, thrown);
        assertEquals(0, pool.metrics().size());
        assertEquals(0, pool.metrics().starting());
        assertEquals(1, pool.metrics().failedStartups());
        pool.closeAsync();
    }
}

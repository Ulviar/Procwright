/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerStartupRaceTest extends WorkerPoolControllerTestSupport {

    @Test
    void closeBeforeFactorySuccessSignalRetiresLateWorkerWithoutPublishingIt() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return new TestWorker(1);
                },
                worker -> {
                    physicalCloses.incrementAndGet();
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 1, 0);

            pool.closeAsync().get(1, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure poolFailure = (PoolFailure) failure.getCause();
            assertEquals(FailureKind.CLOSED, poolFailure.kind);
            assertPartition(pool, 0, 0, 0, 0, 0);
            releaseFactory.countDown();

            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertTrue(pool.closeAsync().isDone(), "late physical close must not gate logical pool termination");
            assertPartition(pool, 0, 0, 0, 0, 0);
            assertEquals(0, pool.metrics().created());
            assertEquals(0, pool.metrics().retired());

            releaseRetirement.countDown();
            assertTrue(awaitMetrics(pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(1)));

            assertEquals(1, factoryCalls.get());
            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedStartups());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.CLOSED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseFactory.countDown();
            releaseRetirement.countDown();
            pool.closeAsync();
            try {
                pool.closeAsync().get(1, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void lateWorkerCloseFailureIsReportedWithoutRewritingCompletedPoolClose() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch reportReceived = new CountDownLatch(1);
        IllegalStateException closeFailure = new IllegalStateException("controlled late close failure");
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return new TestWorker(1);
                },
                worker -> {
                    closeCalls.incrementAndGet();
                    throw closeFailure;
                },
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                (thread, failure) -> {
                    reports.incrementAndGet();
                    reported.compareAndSet(null, failure);
                    reportReceived.countDown();
                },
                System::nanoTime,
                null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            ExecutionException acquisitionFailure =
                    assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.CLOSED, ((PoolFailure) acquisitionFailure.getCause()).kind);

            releaseFactory.countDown();
            assertTrue(reportReceived.await(1, TimeUnit.SECONDS));
            assertTrue(awaitMetrics(pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(1)));

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertSame(closeFailure, reported.get());
            assertEquals(1, reports.get());
            assertEquals(1, closeCalls.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().failedWorkerCloses());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.CLOSED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseFactory.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void leasedWorkerIsRetiredAsClosedWhenPoolClosesBeforeRelease() throws Exception {
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {
                    physicalCloses.incrementAndGet();
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 0, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false));
        WorkerPoolState.Lease<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);
        boolean released = false;
        try {
            pool.closeAsync();
            assertEquals(1, pool.metrics().created());
            assertEquals(0, pool.metrics().failedStartups());
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertEquals(0, physicalCloses.get());
            assertPartition(pool, 1, 0, 1, 0, 0);

            pool.releaseReusable(worker);
            released = true;
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertFalse(pool.closeAsync().isDone(), "the physical worker close is still in progress");
            assertPartition(pool, 1, 0, 0, 0, 1);
            assertEquals(1, physicalCloses.get());

            releaseRetirement.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);

            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedStartups());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.CLOSED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            if (!released) {
                pool.releaseReusable(worker);
            }
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeFirstWinsWhenRunningFactoryExceedsStartupDeadline() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return new TestWorker(1);
                },
                worker -> {
                    physicalCloses.incrementAndGet();
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 0, 0, Duration.ofMillis(150), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync().get(1, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.CLOSED, ((PoolFailure) failure.getCause()).kind);
            assertPartition(pool, 0, 0, 0, 0, 0);

            releaseFactory.countDown();
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertTrue(pool.closeAsync().isDone(), "late physical close must not gate logical pool termination");
            assertPartition(pool, 0, 0, 0, 0, 0);
            assertEquals(0, pool.metrics().created());
            assertEquals(0, pool.metrics().retired());

            releaseRetirement.countDown();
            assertTrue(awaitMetrics(pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(1)));
            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedStartups());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.CLOSED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseFactory.countDown();
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeFirstWinsOverOrdinaryFactoryFailure() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        IllegalStateException startupFailure = new IllegalStateException("controlled startup failure");
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch reportReceived = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    throw startupFailure;
                },
                worker -> physicalCloses.incrementAndGet(),
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                (thread, failure) -> {
                    reported.compareAndSet(null, failure);
                    reportReceived.countDown();
                },
                System::nanoTime,
                null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync();
            releaseFactory.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure poolFailure = (PoolFailure) failure.getCause();
            assertEquals(FailureKind.CLOSED, poolFailure.kind);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertTrue(reportReceived.await(1, TimeUnit.SECONDS));
            assertSame(startupFailure, reported.get());
            assertEquals(0, physicalCloses.get(), "a failed factory did not create a worker to close");
            assertEquals(1, pool.metrics().failedStartups());
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseFactory.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void factoryFailureFirstRemainsStartupFailedWhenPoolClosesLater() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        IllegalStateException startupFailure = new IllegalStateException("controlled startup failure");
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    throw startupFailure;
                },
                worker -> physicalCloses.incrementAndGet(),
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            releaseFactory.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure poolFailure = (PoolFailure) failure.getCause();
            assertEquals(FailureKind.STARTUP_FAILED, poolFailure.kind);
            assertSame(startupFailure, poolFailure.getCause());

            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(0, physicalCloses.get());
            assertEquals(1, pool.metrics().failedStartups());
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseFactory.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedStartupTimeoutRetiresLateWorkerWithItsOriginalReason() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return new TestWorker(1);
                },
                worker -> {
                    physicalCloses.incrementAndGet();
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 0, 0, Duration.ofMillis(80), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure failure = (PoolFailure) observed.getCause();
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, failure.kind);
            assertEquals(0, failure.getSuppressed().length);
            assertPartition(pool, 1, 0, 0, 1, 0);

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            releaseFactory.countDown();
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 0, 0, 0, 0, 0);
            assertEquals(0, pool.metrics().created());
            assertEquals(0, pool.metrics().retired());
            releaseRetirement.countDown();
            assertTrue(awaitMetrics(pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(1)));

            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().failedStartups());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_TIMEOUT));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.CLOSED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseFactory.countDown();
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void startupPermitTimeoutRestoresCapacityWithoutCallingFactory() throws Exception {
        List<BoundedTaskPermit> occupied = new ArrayList<>();
        int capacity = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        long permitDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskLimits.WORKER_STARTUPS.acquire(permitDeadline));
        }
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofMillis(80), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, ((PoolFailure) observed.getCause()).kind);
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(0, factoryCalls.get());
            assertEquals(0, pool.metrics().failedStartups());
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            occupied.forEach(BoundedTaskPermit::close);
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(capacity, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void closeCancelsStartupQueuedForExecutionPermitWithoutCallingFactory() throws Exception {
        List<BoundedTaskPermit> occupied = new ArrayList<>();
        int capacity = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        long permitDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskLimits.WORKER_STARTUPS.acquire(permitDeadline));
        }
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(awaitMetrics(pool, metrics -> metrics.starting() == 1, Duration.ofSeconds(1)));

            pool.closeAsync();
            assertPartition(pool, 0, 0, 0, 0, 0);

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.CLOSED, ((PoolFailure) failure.getCause()).kind);
            assertEquals(0, factoryCalls.get());
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            occupied.forEach(BoundedTaskPermit::close);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
        }
    }

    @Test
    void closeWinnerSurvivesInterruptionWhileStartupWaitsForExecutionPermit() throws Exception {
        List<BoundedTaskPermit> occupied = new ArrayList<>();
        int capacity = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        long permitDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskLimits.WORKER_STARTUPS.acquire(permitDeadline));
        }
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicReference<Throwable> acquireFailure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        Thread acquire = new Thread(() -> {
            try {
                pool.acquire((worker, deadline) -> HEALTHY);
            } catch (Throwable failure) {
                acquireFailure.set(failure);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            acquire.start();
            assertTrue(awaitMetrics(pool, metrics -> metrics.starting() == 1, Duration.ofSeconds(1)));
            assertTrue(awaitStackFrame(acquire, BoundedTaskLimiter.class.getName(), "acquire", Duration.ofSeconds(1)));

            pool.closeAsync();
            acquire.interrupt();
            acquire.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(acquire.isAlive());
            assertEquals(FailureKind.CLOSED, ((PoolFailure) acquireFailure.get()).kind);
            assertTrue(interrupted.get());
            assertEquals(0, factoryCalls.get());
            assertPartition(pool, 0, 0, 0, 0, 0);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            occupied.forEach(BoundedTaskPermit::close);
            acquire.interrupt();
            acquire.join(TimeUnit.SECONDS.toMillis(1));
            pool.closeAsync();
        }
    }

    @Test
    void queuedStartupPermitTimeoutAfterCloseReturnsTypedClosedFailure() throws Exception {
        List<BoundedTaskPermit> occupied = new ArrayList<>();
        int capacity = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        long permitDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskLimits.WORKER_STARTUPS.acquire(permitDeadline));
        }
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofMillis(60), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(awaitMetrics(pool, metrics -> metrics.starting() == 1, Duration.ofSeconds(1)));
            pool.closeAsync();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> acquire.get(500, TimeUnit.MILLISECONDS));

            assertEquals(FailureKind.CLOSED, ((PoolFailure) failure.getCause()).kind);
            assertEquals(0, factoryCalls.get());
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            occupied.forEach(BoundedTaskPermit::close);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
        }
    }

    @Test
    void warmupInterruptionPreservesInterruptionAndUsesTypedFailure() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch allowStartupToFinish = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        AtomicInteger closedWorkers = new AtomicInteger();
        CountDownLatch workerClosed = new CountDownLatch(1);
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        AtomicBoolean interruptedStatus = new AtomicBoolean();

        Thread constructor = new Thread(() -> {
            try {
                controller(
                        () -> {
                            startupThread.set(Thread.currentThread());
                            startupEntered.countDown();
                            awaitIgnoringInterrupt(allowStartupToFinish);
                            return new TestWorker(1);
                        },
                        worker -> {
                            closedWorkers.incrementAndGet();
                            workerClosed.countDown();
                        },
                        settings(1, 1, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false));
            } catch (Throwable failure) {
                observedFailure.set(failure);
                interruptedStatus.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            constructor.start();
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));

            constructor.interrupt();
            constructor.join(1_000);

            assertFalse(constructor.isAlive());
            assertTrue(observedFailure.get() instanceof PoolFailure);
            assertEquals(FailureKind.INTERRUPTED, ((PoolFailure) observedFailure.get()).kind);
            assertTrue(interruptedStatus.get());

            allowStartupToFinish.countDown();
            assertTrue(workerClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1, closedWorkers.get());
        } finally {
            allowStartupToFinish.countDown();
            constructor.interrupt();
            constructor.join(1_000);
            joinThread(startupThread, "interrupted warmup startup");
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void lateStartupFailureReleasesSlotAfterAcquireTimeout() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    startupThread.set(Thread.currentThread());
                    startupEntered.countDown();
                    awaitIgnoringInterrupt(releaseStartup);
                    throw new IllegalStateException("late startup failed");
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false));

        try {
            assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 1, 0);

            releaseStartup.countDown();
            assertTrue(awaitMetrics(pool, metrics -> metrics.size() == 0, Duration.ofSeconds(1)));
            assertEquals(1, pool.metrics().failedStartups());
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            releaseStartup.countDown();
            joinThread(startupThread, "late failed startup");
            pool.closeAsync();
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void abandonedStartupReleasesExecutionPermitBeforeLateWorkerRetirementCompletes() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch startupFinished = new CountDownLatch(1);
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    startupEntered.countDown();
                    try {
                        awaitIgnoringInterrupt(releaseStartup);
                        return new TestWorker(1);
                    } finally {
                        startupFinished.countDown();
                    }
                },
                worker -> {
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false));

        try {
            assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore - 1, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());

            releaseStartup.countDown();
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
            assertPartition(pool, 1, 0, 0, 0, 1);

            releaseRetirement.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_TIMEOUT));
        } finally {
            releaseStartup.countDown();
            releaseRetirement.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            pool.closeAsync();
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void interruptedStartupUsesExactReasonAndRestoresExecutionCapacity() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    startupThread.set(Thread.currentThread());
                    startupEntered.countDown();
                    awaitIgnoringInterrupt(releaseStartup);
                    return new TestWorker(1);
                },
                worker -> {},
                settings(1, 0, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false));
        Thread caller = new Thread(() -> {
            try {
                pool.acquire((worker, deadline) -> HEALTHY);
            } catch (Throwable observed) {
                failure.set(observed);
            }
        });
        try {
            caller.start();
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));

            caller.interrupt();
            caller.join(1_000);
            assertFalse(caller.isAlive());
            assertEquals(FailureKind.INTERRUPTED, ((PoolFailure) failure.get()).kind);

            releaseStartup.countDown();
            assertTrue(awaitMetrics(pool, metrics -> metrics.size() == 0, Duration.ofSeconds(1)));
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_INTERRUPTED));
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            releaseStartup.countDown();
            caller.interrupt();
            caller.join(1_000);
            joinThread(startupThread, "interrupted late startup");
            pool.closeAsync();
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void abandonedStartupReportsLateErrorExactlyOnce() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch startupFinished = new CountDownLatch(1);
        CountDownLatch reportReceived = new CountDownLatch(1);
        AssertionError lateError = new AssertionError("late startup error");
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        try {
            WorkerPoolController<TestWorker> pool = controller(
                    () -> {
                        startupEntered.countDown();
                        try {
                            awaitIgnoringInterrupt(releaseStartup);
                            throw lateError;
                        } finally {
                            startupFinished.countDown();
                        }
                    },
                    worker -> {},
                    settings(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false),
                    task -> Threading.start("test-replenish-", task),
                    (thread, failure) -> {
                        reported.compareAndSet(null, failure);
                        reports.incrementAndGet();
                        reportReceived.countDown();
                    },
                    System::nanoTime,
                    null);

            assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            releaseStartup.countDown();

            assertTrue(reportReceived.await(1, TimeUnit.SECONDS));
            assertSame(lateError, reported.get());
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.metrics().size());
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
            assertEquals(1, reports.get());
        } finally {
            releaseStartup.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    private static boolean awaitStackFrame(Thread thread, String className, String methodName, Duration timeout)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(className)
                        && frame.getMethodName().equals(methodName)) {
                    return true;
                }
            }
            Thread.sleep(1);
        }
        return false;
    }
}

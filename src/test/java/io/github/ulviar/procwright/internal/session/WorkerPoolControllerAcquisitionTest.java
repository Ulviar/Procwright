/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.ACQUIRE_TIMEOUT;
import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTH_FAILED;
import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.PROCESS_EXITED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
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

final class WorkerPoolControllerAcquisitionTest extends WorkerPoolControllerTestSupport {

    @Test
    void processExitHealthOutcomeUsesOnlyProcessExitedRetirementReason() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> physicalCloses.incrementAndGet(),
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            WorkerPoolState.Lease<TestWorker> worker =
                    pool.acquire((candidate, deadline) -> candidate.id() == 1 ? PROCESS_EXITED : HEALTHY);

            assertEquals(2, worker.session().id());
            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.PROCESS_EXITED));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.HEALTH_FAILED));
            pool.releaseReusable(worker);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void rejectedUserHealthOutcomeUsesOnlyHealthFailedRetirementReason() {
        AtomicInteger created = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {},
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            WorkerPoolState.Lease<TestWorker> worker =
                    pool.acquire((candidate, deadline) -> candidate.id() == 1 ? HEALTH_FAILED : HEALTHY);

            assertEquals(2, worker.session().id());
            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.PROCESS_EXITED));
            pool.releaseReusable(worker);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void deadlineAfterHealthyOutcomeReturnsSameWorkerWithoutHealthRetirement() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> physicalCloses.incrementAndGet(),
                settings(1, 1, 0, Duration.ofMillis(25), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            PoolFailure timeout = assertThrows(
                    PoolFailure.class,
                    () -> pool.acquire((worker, deadline) -> {
                        while (deadline - System.nanoTime() > 0) {
                            Thread.onSpinWait();
                        }
                        return HEALTHY;
                    }));

            assertEquals(FailureKind.ACQUIRE_TIMEOUT, timeout.kind);
            assertEquals(1, created.get());
            assertEquals(0, physicalCloses.get());
            assertEquals(0, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedStartups());
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertPartition(pool, 1, 1, 0, 0, 0);

            WorkerPoolState.Lease<TestWorker> sameWorker = pool.acquire((worker, deadline) -> HEALTHY);
            assertEquals(1, sameWorker.session().id());
            pool.releaseReusable(sameWorker);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void acquireTimeoutHealthOutcomeReturnsWorkerWithoutHealthRetirement() {
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(1),
                worker -> {},
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            PoolFailure timeout =
                    assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> ACQUIRE_TIMEOUT));

            assertEquals(FailureKind.ACQUIRE_TIMEOUT, timeout.kind);
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertPartition(pool, 1, 1, 0, 0, 0);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void interruptedCapacityWaitRestoresInterruptAndLeavesTheExistingLeaseUntouched() throws Exception {
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(1),
                worker -> {},
                settings(1, 1, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false));
        WorkerPoolState.Lease<TestWorker> leased = pool.acquire((worker, deadline) -> HEALTHY);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread waiting = new Thread(() -> {
            try {
                pool.acquire((worker, deadline) -> HEALTHY);
            } catch (Throwable observed) {
                failure.set(observed);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            waiting.start();
            assertTrue(awaitState(waiting, Thread.State.TIMED_WAITING, Duration.ofSeconds(1)));

            waiting.interrupt();
            waiting.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(waiting.isAlive());
            PoolFailure observed = (PoolFailure) failure.get();
            assertEquals(FailureKind.INTERRUPTED, observed.kind);
            assertTrue(observed.getCause() instanceof InterruptedException);
            assertEquals(Boolean.TRUE, interrupted.get());
            assertPartition(pool, 1, 0, 1, 0, 0);
        } finally {
            waiting.interrupt();
            waiting.join(TimeUnit.SECONDS.toMillis(1));
            pool.releaseReusable(leased);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void healthCheckFailureRetiresTheLeaseAndPreservesFailureIdentity() throws Exception {
        AssertionError healthFailure = new AssertionError("health check failed");
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(1),
                worker -> physicalCloses.incrementAndGet(),
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            AssertionError observed = assertThrows(
                    AssertionError.class,
                    () -> pool.acquire((worker, deadline) -> {
                        throw healthFailure;
                    }));

            assertSame(healthFailure, observed);
            assertTrue(awaitMetrics(pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(1)));
            assertEquals(1, physicalCloses.get());
            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void healthRetriesShareOneAbsoluteAcquireDeadline() {
        AtomicInteger created = new AtomicInteger();
        AtomicReference<Long> firstDeadline = new AtomicReference<>();
        AtomicReference<Long> secondDeadline = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {},
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            WorkerPoolState.Lease<TestWorker> worker = pool.acquire((candidate, deadline) -> {
                if (candidate.id() == 1) {
                    firstDeadline.set(deadline);
                    return HEALTH_FAILED;
                }
                secondDeadline.set(deadline);
                return HEALTHY;
            });

            assertEquals(2, worker.session().id());
            assertEquals(firstDeadline.get(), secondDeadline.get());
            pool.releaseReusable(worker);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void acquireTimeoutBoundsWorkerStartupAndRecordsFailedWait() throws Exception {
        int permitsBefore = BoundedTaskLimits.WORKER_STARTUPS.availablePermits();
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch allowStartupToFinish = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        AtomicInteger startupAttempts = new AtomicInteger();
        AtomicInteger closedWorkers = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    startupThread.set(Thread.currentThread());
                    startupAttempts.incrementAndGet();
                    startupEntered.countDown();
                    awaitIgnoringInterrupt(allowStartupToFinish);
                    return new TestWorker(1);
                },
                worker -> closedWorkers.incrementAndGet(),
                settings(1, 0, 0, Duration.ofMillis(50), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));

            ExecutionException exception =
                    assertThrows(ExecutionException.class, () -> acquire.get(500, TimeUnit.MILLISECONDS));

            assertTrue(exception.getCause() instanceof PoolFailure);
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, ((PoolFailure) exception.getCause()).kind);
            assertTrue(pool.metrics().totalAcquireWaitNanos() > 0);
            assertEquals(1, pool.metrics().size());
            assertEquals(1, pool.metrics().starting());
            assertEquals(0, pool.metrics().failedStartups());

            Future<?> secondAcquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            ExecutionException secondException =
                    assertThrows(ExecutionException.class, () -> secondAcquire.get(500, TimeUnit.MILLISECONDS));
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, ((PoolFailure) secondException.getCause()).kind);
            assertEquals(1, startupAttempts.get(), "the abandoned startup must keep the only worker slot");

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            allowStartupToFinish.countDown();

            assertTrue(awaitMetrics(pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(1)));
            assertEquals(1, closedWorkers.get());
            assertEquals(0, pool.metrics().size());
            assertEquals(0, pool.metrics().starting());
            assertEquals(1, pool.metrics().failedStartups());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_TIMEOUT));
        } finally {
            allowStartupToFinish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            joinThread(startupThread, "timed-out startup");
            assertEquals(permitsBefore, BoundedTaskLimits.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void slotPartitionIsExactAcrossStartupLeaseIdleAndRetirement() throws Exception {
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    startupEntered.countDown();
                    awaitIgnoringInterrupt(releaseStartup);
                    return new TestWorker(1);
                },
                worker -> {
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkerPoolState.Lease<TestWorker>> acquiring =
                    executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 1, 0);

            releaseStartup.countDown();
            WorkerPoolState.Lease<TestWorker> worker = acquiring.get(1, TimeUnit.SECONDS);
            assertPartition(pool, 1, 0, 1, 0, 0);

            pool.releaseReusable(worker);
            assertPartition(pool, 1, 1, 0, 0, 0);

            pool.closeAsync();
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 0, 1);

            releaseRetirement.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseStartup.countDown();
            releaseRetirement.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
        }
    }

    @Test
    void blockingRetirementDoesNotExtendAcquireDeadline() throws Exception {
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 1, 0, Duration.ofMillis(50), Integer.MAX_VALUE, Duration.ofNanos(1), false));
        ExecutorService acquireExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<PoolFailure> acquisition = acquireExecutor.submit(
                    () -> assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> HEALTHY)));
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            PoolFailure failure = acquisition.get(1, TimeUnit.SECONDS);

            assertEquals(FailureKind.ACQUIRE_TIMEOUT, failure.kind);
            assertEquals(1L, releaseRetirement.getCount(), "acquire must finish while retirement remains blocked");
            assertPartition(pool, 1, 0, 0, 0, 1);
        } finally {
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            acquireExecutor.shutdownNow();
            assertTrue(acquireExecutor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static boolean awaitState(Thread thread, Thread.State expected, Duration timeout)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (thread.getState() != expected && System.nanoTime() < deadlineNanos) {
            Thread.sleep(1);
        }
        return thread.getState() == expected;
    }
}

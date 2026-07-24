/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                new Options(2, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true));
        try {
            assertTrue(pool.awaitMetrics(metrics -> metrics.idle() == 1, Duration.ofSeconds(1)));

            PoolWorker<TestWorker> leased = pool.acquire((worker, deadline) -> HEALTHY);

            assertTrue(pool.awaitMetrics(metrics -> metrics.idle() == 1, Duration.ofSeconds(1)));
            assertEquals(2, pool.metrics().size());
            assertEquals(1, pool.metrics().leased());
            pool.release(leased, true, null);
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
                new Options(1, 1, 1, Duration.ofSeconds(1), 1, Duration.ZERO, true));

        try {
            PoolWorker<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);
            worker.recordRequest();
            pool.release(worker, true, null);
            assertTrue(replenishmentEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync();
            assertFalse(pool.closeAsync().isDone());
            failReplenishment.countDown();

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.metrics().size());
            assertEquals(1, pool.metrics().failedStartups());
        } finally {
            failReplenishment.countDown();
            pool.closeAsync();
            joinThread(replenishmentThread, "failed replenishment startup");
        }
    }

    @Test
    void replenishmentSchedulingFailureClosesPoolWithoutLeakingLeasedWorker() throws Exception {
        IllegalStateException schedulingFailure = new IllegalStateException("thread creation failed");
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(2, 1, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                task -> {
                    throw schedulingFailure;
                });
        PoolWorker<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);

        assertEquals(1, pool.metrics().leased());
        pool.release(worker, true, null);

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
                        new Options(2, 1, 2, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                        task -> {
                            throw schedulingFailure;
                        }));

        assertEquals(FailureKind.RETIREMENT_FAILED, observed.kind);
        assertSame(schedulingFailure, observed.getCause());
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
                        new Options(2, 1, 2, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                        task -> {
                            throw schedulingError;
                        }));

        assertSame(schedulingError, thrown);
        assertTrue(workerClosed.await(1, TimeUnit.SECONDS));
        assertEquals(1, closedWorkers.get());
    }

    @Test
    void constructorAttachesWarmWorkerCloseFailureAfterFatalReplenishmentScheduling() {
        AssertionError schedulingError = new AssertionError("replenishment scheduling failed");
        IllegalStateException closeFailure = new IllegalStateException("warm worker close failed");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> controller(
                        () -> new TestWorker(1),
                        worker -> {
                            throw closeFailure;
                        },
                        new Options(2, 1, 2, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                        task -> {
                            throw schedulingError;
                        }));

        assertSame(schedulingError, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void replenishmentRetriesFailedStartupWithoutExternalActivity() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("transient startup failure");
                    }
                    return new TestWorker(2);
                },
                worker -> {},
                new Options(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true));

        assertTrue(pool.awaitMetrics(metrics -> metrics.idle() == 1, Duration.ofSeconds(1)));
        assertEquals(2, attempts.get());
        assertEquals(1, pool.metrics().failedStartups());
        pool.closeAsync();
        pool.closeAsync().get(1, TimeUnit.SECONDS);
    }

    @Test
    void closeStopsReplenishmentDuringRetryBackoff() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch backoffEntered = new CountDownLatch(1);
        CountDownLatch releaseBackoff = new CountDownLatch(1);
        CountDownLatch ownerFinished = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("startup unavailable");
                },
                worker -> {},
                new Options(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                task -> Threading.start("test-replenish-", () -> {
                    try {
                        task.run();
                    } finally {
                        ownerFinished.countDown();
                    }
                }),
                (thread, failure) -> {},
                System::nanoTime,
                backoff -> {
                    if (backoff.isZero()) {
                        return true;
                    }
                    backoffEntered.countDown();
                    awaitIgnoringInterrupt(releaseBackoff);
                    return true;
                });
        try {
            assertTrue(backoffEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            int attemptsAfterDrain = attempts.get();
            releaseBackoff.countDown();
            assertTrue(ownerFinished.await(1, TimeUnit.SECONDS));

            assertEquals(attemptsAfterDrain, attempts.get());
        } finally {
            releaseBackoff.countDown();
            pool.closeAsync();
            assertTrue(ownerFinished.await(1, TimeUnit.SECONDS));
        }
    }
}

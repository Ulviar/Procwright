/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerRetirementTest extends WorkerPoolControllerTestSupport {

    @Test
    void retirementInitiatesWholeBatchAndCompletesReadyWorkersIndependently() throws Exception {
        CompletableFuture<WorkerRetirement.Outcome> firstOutcome = new CompletableFuture<>();
        CompletableFuture<WorkerRetirement.Outcome> secondOutcome =
                CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
        CountDownLatch firstInitiated = new CountDownLatch(1);
        CountDownLatch secondInitiated = new CountDownLatch(1);
        AtomicInteger workerIds = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = WorkerPoolController.fromSettings(
                () -> new TestWorker(workerIds.incrementAndGet()),
                worker -> {
                    if (worker.id() == 1) {
                        firstInitiated.countDown();
                        return firstOutcome;
                    }
                    secondInitiated.countDown();
                    return secondOutcome;
                },
                settings(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "test worker",
                "test-two-phase-",
                System::nanoTime);
        try {
            CompletableFuture<Void> drained = pool.closeAsync();

            assertTrue(firstInitiated.await(1, TimeUnit.SECONDS));
            assertTrue(secondInitiated.await(1, TimeUnit.SECONDS));
            assertTrue(pool.awaitMetrics(metrics -> metrics.size() == 1, Duration.ofSeconds(1)));
            assertFalse(drained.isDone());
            assertPartition(pool, 1, 0, 0, 0, 1);
            assertEquals(1, pool.metrics().retired());

            firstOutcome.complete(WorkerRetirement.Outcome.success());
            drained.get(1, TimeUnit.SECONDS);

            assertPartition(pool, 0, 0, 0, 0, 0);
            assertEquals(2, pool.metrics().retired());
        } finally {
            firstOutcome.complete(WorkerRetirement.Outcome.success());
            pool.closeAsync();
        }
    }

    @Test
    void retiringWorkerKeepsItsSlotUntilCloseCompletes() throws Exception {
        AtomicInteger created = new AtomicInteger();
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch allowRetirement = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {
                    if (worker.id() == 1) {
                        retirementEntered.countDown();
                        awaitIgnoringInterrupt(allowRetirement);
                    }
                },
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ofNanos(1), false));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkerPoolState.Lease<TestWorker>> first =
                    executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));

            assertEquals(1, created.get(), "a replacement must not start before the retiring process closes");
            assertEquals(1, pool.metrics().retiring());
            assertEquals(1, pool.metrics().size());
            assertFalse(first.isDone(), "the acquire must remain pending while the only slot is retiring");

            allowRetirement.countDown();
            WorkerPoolState.Lease<TestWorker> leased = first.get(1, TimeUnit.SECONDS);
            assertEquals(2, created.get());
            pool.retire(leased, PooledWorkerRetireReason.CLOSED);
        } finally {
            allowRetirement.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerCloseFailureMakesDrainExceptional() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {
                    throw new IllegalStateException("close failed");
                },
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

        pool.closeAsync();
        assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

        assertTrue(pool.awaitMetrics(metrics -> metrics.failedWorkerCloses() == 1, Duration.ofSeconds(1)));
        assertEquals(0, pool.metrics().size());
        assertEquals(0, pool.metrics().retiring());
        assertEquals(1, pool.metrics().retired());
        assertEquals(1, pool.metrics().failedWorkerCloses());
        assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.CLOSED));
        assertTrue(pool.closeAsync().isCompletedExceptionally());
    }

    @Test
    void laterFatalWorkerCloseFailureBecomesDrainPrimary() {
        AtomicInteger workerIds = new AtomicInteger();
        IllegalStateException runtimeFailure = new IllegalStateException("first close failed");
        AssertionError fatalFailure = new AssertionError("second close failed fatally");
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(workerIds.incrementAndGet()),
                worker -> {
                    if (worker.id() == 1) {
                        throw runtimeFailure;
                    }
                    throw fatalFailure;
                },
                settings(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

        Throwable aggregate = observed.getCause();
        assertSame(fatalFailure, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, runtimeFailure);
        assertEquals(0, fatalFailure.getSuppressed().length);
        assertEquals(2, pool.metrics().failedWorkerCloses());
    }

    @Test
    void firstFatalWorkerCloseFailureRemainsDrainPrimary() {
        AtomicInteger workerIds = new AtomicInteger();
        AssertionError fatalFailure = new AssertionError("first close failed fatally");
        IllegalStateException runtimeFailure = new IllegalStateException("second close failed");
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(workerIds.incrementAndGet()),
                worker -> {
                    if (worker.id() == 1) {
                        throw fatalFailure;
                    }
                    throw runtimeFailure;
                },
                settings(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

        Throwable aggregate = observed.getCause();
        assertSame(fatalFailure, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, runtimeFailure);
        assertEquals(0, fatalFailure.getSuppressed().length);
        assertEquals(2, pool.metrics().failedWorkerCloses());
    }

    @Test
    void firstFatalWorkerCloseFailureWinsOverLaterFatalFailure() {
        AtomicInteger workerIds = new AtomicInteger();
        AssertionError firstFatal = new AssertionError("first fatal close failed");
        OutOfMemoryError secondFatal = new OutOfMemoryError("second fatal close failed");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime close failed");
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(workerIds.incrementAndGet()),
                worker -> {
                    switch (worker.id()) {
                        case 1 -> throw firstFatal;
                        case 2 -> throw runtimeFailure;
                        default -> throw secondFatal;
                    }
                },
                settings(3, 3, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

        Throwable aggregate = observed.getCause();
        assertSame(firstFatal, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, runtimeFailure);
        assertSuppressedExactlyOnce(aggregate, secondFatal);
        assertEquals(0, firstFatal.getSuppressed().length);
        assertEquals(3, pool.metrics().failedWorkerCloses());
    }

    @Test
    void poolObservesFailedFirstPhysicalCloseAfterWorkerSelfCloses() throws Exception {
        IllegalStateException firstCloseFailure = new IllegalStateException("first physical close failed");
        CloseAwareWorker session = new CloseAwareWorker();
        WorkerPoolController<CloseAwareWorker> pool = WorkerPoolController.fromSettings(
                () -> session,
                worker -> WorkerCloseSupport.closeOutcome(worker, worker.terminal, worker.physicalCleanup),
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "close-aware worker",
                "test-close-aware-",
                System::nanoTime);
        WorkerPoolState.Lease<CloseAwareWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);
        session.failPhysicalClose(firstCloseFailure);

        pool.retire(worker, PooledWorkerRetireReason.WORKER_FAILED);

        assertTrue(pool.awaitMetrics(metrics -> metrics.failedWorkerCloses() == 1, Duration.ofSeconds(1)));
        assertEquals(1, session.physicalCloseCalls.get());
        assertPartition(pool, 0, 0, 0, 0, 0);
        assertEquals(1, pool.metrics().retired());
        assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
        assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));
        pool.closeAsync();
        assertEquals(1, session.physicalCloseCalls.get(), "repeated pool close must use the memoized outcome");
    }

    @Test
    void releaseHandsRetirementToReaperWithoutBlockingCaller() throws Exception {
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        CountDownLatch releaseReturned = new CountDownLatch(1);
        ExecutorService releaseExecutor = Executors.newSingleThreadExecutor();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {
                    retirementEntered.countDown();
                    awaitIgnoringInterrupt(releaseRetirement);
                },
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        WorkerPoolState.Lease<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);

        try {
            Future<?> releaseCall = releaseExecutor.submit(() -> {
                try {
                    pool.retire(worker, PooledWorkerRetireReason.WORKER_FAILED);
                } finally {
                    releaseReturned.countDown();
                }
            });

            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertTrue(releaseReturned.await(1, TimeUnit.SECONDS), "release remained coupled to physical close");
            releaseCall.get(1, TimeUnit.SECONDS);
            assertPartition(pool, 1, 0, 0, 0, 1);
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.metrics().size());
        } finally {
            releaseRetirement.countDown();
            pool.closeAsync();
            releaseExecutor.shutdownNow();
            assertTrue(releaseExecutor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static void assertSuppressedExactlyOnce(Throwable primary, Throwable expected) {
        int matches = 0;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected) {
                matches++;
            }
        }
        assertEquals(1, matches);
    }

    private static final class CloseAwareWorker implements AutoCloseable {

        final CompletableFuture<Void> terminal = CompletableFuture.completedFuture(null);
        final CompletableFuture<Void> physicalCleanup = new CompletableFuture<>();
        final AtomicBoolean physicallyClosed = new AtomicBoolean();
        final AtomicInteger physicalCloseCalls = new AtomicInteger();

        void failPhysicalClose(Throwable failure) {
            if (physicallyClosed.compareAndSet(false, true)) {
                physicalCloseCalls.incrementAndGet();
                physicalCleanup.completeExceptionally(failure);
            }
        }

        @Override
        public void close() {
            if (physicallyClosed.compareAndSet(false, true)) {
                physicalCloseCalls.incrementAndGet();
                physicalCleanup.complete(null);
            }
        }
    }
}

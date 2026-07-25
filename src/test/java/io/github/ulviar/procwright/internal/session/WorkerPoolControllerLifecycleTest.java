/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static io.github.ulviar.procwright.internal.session.WorkerPoolController.HealthOutcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerPoolControllerLifecycleTest extends WorkerPoolControllerTestSupport {

    @Test
    void typedAggregateExposureDoesNotWaitForOrMutateTheSourceFailure() throws Exception {
        PoolFailure primary = new PoolFailure(FailureKind.CLOSED, "closed", null);
        IllegalStateException cleanup = new IllegalStateException("cleanup failed");
        Throwable aggregate = FailureAggregation.combine(primary, cleanup, "pool failed and cleanup failed");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            Future<Throwable> exposure = executor.submit(() -> Failures.INSTANCE.expose(aggregate));

            PoolFailure exposed = (PoolFailure) exposure.get(1, TimeUnit.SECONDS);
            assertEquals(FailureKind.CLOSED, exposed.kind);
            assertSame(aggregate, exposed.getCause());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, cleanup.getSuppressed().length);
    }

    @Test
    void warmupFailureDoesNotPoisonLaterPoolConstruction() throws Exception {
        BoundedTaskLimiter workerPermits = new BoundedTaskLimiter(1);
        IllegalStateException startupFailure = new IllegalStateException("warmup failed");

        PoolFailure observed = assertThrows(
                PoolFailure.class,
                () -> controllerWithPermits(
                        () -> {
                            throw startupFailure;
                        },
                        worker -> {},
                        new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                        workerPermits));

        assertEquals(FailureKind.STARTUP_FAILED, observed.kind);
        assertSame(startupFailure, observed.getCause());
        assertEquals(1, workerPermits.availablePermits());

        WorkerPoolController<TestWorker> recovered = controllerWithPermits(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                workerPermits);
        assertEquals(0, workerPermits.availablePermits());
        recovered.closeAsync().get(1, TimeUnit.SECONDS);
        assertEquals(1, workerPermits.availablePermits());
    }

    @Test
    void closeAsyncReturnsCancellationIsolatedViewsOfOneCleanup() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {
                    closeCalls.incrementAndGet();
                    closeEntered.countDown();
                    awaitIgnoringInterrupt(releaseClose);
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            CompletableFuture<Void> cancelledView = pool.closeAsync();
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> observedView = pool.closeAsync();

            assertTrue(cancelledView.cancel(true));
            assertFalse(observedView.isCancelled());
            releaseClose.countDown();

            observedView.get(1, TimeUnit.SECONDS);
            assertEquals(1, closeCalls.get());
            assertEquals(0, pool.metrics().size());
        } finally {
            releaseClose.countDown();
            pool.closeAsync();
        }
    }

    @Test
    void blockedPublicCloseContinuationDoesNotDelayAnotherAcceptedPool() throws Exception {
        CountDownLatch firstWorkerCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWorkerClose = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        WorkerPoolController<TestWorker> first = controller(
                () -> new TestWorker(1),
                worker -> {
                    firstWorkerCloseEntered.countDown();
                    awaitIgnoringInterrupt(releaseFirstWorkerClose);
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        WorkerPoolController<TestWorker> second = controller(
                () -> new TestWorker(2),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        CompletableFuture<Void> callback = null;
        try {
            CompletableFuture<Void> firstClose = publicCloseView(first);
            assertTrue(firstWorkerCloseEntered.await(1, TimeUnit.SECONDS));
            callback = firstClose.thenRun(() -> {
                callbackEntered.countDown();
                awaitIgnoringInterrupt(releaseCallback);
            });
            releaseFirstWorkerClose.countDown();
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));

            publicCloseView(second).get(1, TimeUnit.SECONDS);
            assertEquals(1, second.metrics().retired());
            assertEquals(0, second.metrics().size());
            assertFalse(callback.isDone());
        } finally {
            releaseFirstWorkerClose.countDown();
            releaseCallback.countDown();
            first.closeAsync().get(1, TimeUnit.SECONDS);
            second.closeAsync().get(1, TimeUnit.SECONDS);
            if (callback != null) {
                callback.get(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void blockedPublicCloseContinuationDoesNotRetainWorkerPermit() throws Exception {
        BoundedTaskLimiter workerPermits = new BoundedTaskLimiter(1);
        CountDownLatch firstWorkerCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWorkerClose = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        WorkerPoolController<TestWorker> first = controllerWithPermits(
                () -> new TestWorker(1),
                worker -> {
                    firstWorkerCloseEntered.countDown();
                    awaitIgnoringInterrupt(releaseFirstWorkerClose);
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                workerPermits);
        WorkerPoolController<TestWorker> second = null;
        CompletableFuture<Void> callback = null;
        try {
            CompletableFuture<Void> firstClose = publicCloseView(first);
            assertTrue(firstWorkerCloseEntered.await(1, TimeUnit.SECONDS));
            callback = firstClose.thenRun(() -> {
                callbackEntered.countDown();
                awaitIgnoringInterrupt(releaseCallback);
            });
            releaseFirstWorkerClose.countDown();
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, workerPermits.availablePermits());

            second = controllerWithPermits(
                    () -> new TestWorker(2),
                    worker -> {},
                    new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                    workerPermits);
            assertEquals(0, workerPermits.availablePermits());
            assertFalse(callback.isDone());
        } finally {
            releaseFirstWorkerClose.countDown();
            releaseCallback.countDown();
            first.closeAsync().get(1, TimeUnit.SECONDS);
            if (second != null) {
                second.closeAsync().get(1, TimeUnit.SECONDS);
            }
            if (callback != null) {
                callback.get(1, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, workerPermits.availablePermits());
    }

    @Test
    void eightBlockingWorkerExitCallbacksDoNotStarveNinthPoolClose() throws Exception {
        int blockingPools = 8;
        int workerCapacity = blockingPools + 1;
        BoundedTaskLimiter workerPermits = new BoundedTaskLimiter(workerCapacity);
        WorkerPoolController.WorkerPermitProvider workerPermitProvider = deadlineNanos -> {
            BoundedTaskPermit workerPermit = workerPermits.tryAcquire();
            if (workerPermit == null) {
                throw new java.util.concurrent.TimeoutException("test lifecycle capacity exhausted");
            }
            return workerPermit;
        };
        CountDownLatch callbacksEntered = new CountDownLatch(blockingPools);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        List<ExitCallbackWorker> workers = new ArrayList<>();
        List<WorkerPoolController<ExitCallbackWorker>> pools = new ArrayList<>();
        List<CompletableFuture<Void>> callbacks = new ArrayList<>();
        List<CompletableFuture<Void>> closes = new ArrayList<>();
        try {
            for (int index = 0; index <= blockingPools; index++) {
                ExitCallbackWorker worker = new ExitCallbackWorker();
                workers.add(worker);
                pools.add(new WorkerPoolController<>(
                        () -> worker,
                        session ->
                                WorkerCloseSupport.closeOutcome(session, session.onExit(), session.physicalCleanup()),
                        new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                        Failures.INSTANCE,
                        "exit-callback worker",
                        "test-exit-callback-",
                        new WorkerPoolController.Dependencies(
                                task -> Threading.start("test-exit-callback-replenish-", task),
                                (thread, failure) -> {},
                                System::nanoTime,
                                null,
                                workerPermitProvider)));
            }
            assertEquals(0, workerPermits.availablePermits(), "all workers must hold permits before close");

            for (int index = 0; index < blockingPools; index++) {
                callbacks.add(workers.get(index).onExit().thenRun(() -> {
                    callbacksEntered.countDown();
                    awaitIgnoringInterrupt(releaseCallbacks);
                }));
                closes.add(pools.get(index).closeAsync());
            }
            assertTrue(callbacksEntered.await(1, TimeUnit.SECONDS));
            for (int index = 0; index < blockingPools; index++) {
                assertPartition(pools.get(index), 1, 0, 0, 0, 1);
            }

            CompletableFuture<Void> ninthClose = pools.get(blockingPools).closeAsync();
            ninthClose.get(1, TimeUnit.SECONDS);
            awaitAvailablePermits(workerPermits, 1);

            assertPartition(pools.get(blockingPools), 0, 0, 0, 0, 0);
            assertEquals(1, pools.get(blockingPools).metrics().retired());
            assertEquals(1, workers.get(blockingPools).closeCalls.get());
        } finally {
            releaseCallbacks.countDown();
            for (WorkerPoolController<ExitCallbackWorker> pool : pools) {
                pool.closeAsync();
            }
            for (CompletableFuture<Void> callback : callbacks) {
                callback.get(1, TimeUnit.SECONDS);
            }
            for (CompletableFuture<Void> close : closes) {
                close.get(1, TimeUnit.SECONDS);
            }
            for (WorkerPoolController<ExitCallbackWorker> pool : pools) {
                pool.closeAsync().get(1, TimeUnit.SECONDS);
                assertPartition(pool, 0, 0, 0, 0, 0);
            }
            awaitAvailablePermits(workerPermits, workerCapacity);
            for (ExitCallbackWorker worker : workers) {
                assertEquals(1, worker.closeCalls.get());
            }
        }
    }

    @Test
    void aggregateWorkerPermitRejectsAnotherPoolBeforeFactoryAndRecoversAfterRetirement() throws Exception {
        BoundedTaskLimiter workerPermits = new BoundedTaskLimiter(1);
        AtomicInteger factoryInvocations = new AtomicInteger();
        Options options = new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false);
        WorkerPoolController<TestWorker> accepted = controllerWithPermits(
                () -> new TestWorker(factoryInvocations.incrementAndGet()), worker -> {}, options, workerPermits);
        try {
            assertEquals(1, factoryInvocations.get());
            assertEquals(0, workerPermits.availablePermits());

            PoolFailure rejected = assertThrows(
                    PoolFailure.class,
                    () -> controllerWithPermits(
                            () -> new TestWorker(factoryInvocations.incrementAndGet()),
                            worker -> {},
                            options,
                            workerPermits));

            assertEquals(FailureKind.STARTUP_FAILED, rejected.kind);
            assertEquals(1, factoryInvocations.get(), "worker permit rejection must precede the worker factory");
            assertEquals(0, workerPermits.availablePermits());
        } finally {
            accepted.closeAsync().get(1, TimeUnit.SECONDS);
        }
        awaitAvailablePermits(workerPermits, 1);

        WorkerPoolController<TestWorker> recovered = controllerWithPermits(
                () -> new TestWorker(factoryInvocations.incrementAndGet()), worker -> {}, options, workerPermits);
        try {
            assertEquals(2, factoryInvocations.get());
            assertEquals(0, workerPermits.availablePermits());
        } finally {
            recovered.closeAsync().get(1, TimeUnit.SECONDS);
        }
        awaitAvailablePermits(workerPermits, 1);
    }

    @Test
    void maximumAcceptedWarmupUsesAllWorkerCapacity() throws Exception {
        int workerCapacity = BoundedTaskLimits.POOL_WORKERS.capacity();
        BoundedTaskLimiter workerPermits = new BoundedTaskLimiter(workerCapacity);
        AtomicInteger created = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controllerWithPermits(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {},
                new Options(
                        workerCapacity,
                        workerCapacity,
                        0,
                        Duration.ofSeconds(5),
                        Integer.MAX_VALUE,
                        Duration.ZERO,
                        false),
                workerPermits);
        try {
            assertEquals(workerCapacity, created.get());
            assertEquals(0, workerPermits.availablePermits());
            assertPartition(pool, workerCapacity, workerCapacity, 0, 0, 0);
        } finally {
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        awaitAvailablePermits(workerPermits, workerCapacity);
    }

    @Test
    void defaultControllersShareTheProcessWideWorkerLimit() throws Exception {
        int permitsBefore = BoundedTaskLimits.POOL_WORKERS.availablePermits();
        assertTrue(permitsBefore >= 2);
        Options options = new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false);
        WorkerPoolController<TestWorker> first = null;
        WorkerPoolController<TestWorker> second = null;
        try {
            first = controller(() -> new TestWorker(1), worker -> {}, options);
            second = controller(() -> new TestWorker(2), worker -> {}, options);

            assertEquals(permitsBefore - 2, BoundedTaskLimits.POOL_WORKERS.availablePermits());
        } finally {
            WorkerPoolController<TestWorker> firstToClose = first;
            WorkerPoolController<TestWorker> secondToClose = second;
            assertAll(
                    () -> {
                        if (firstToClose != null) {
                            firstToClose.closeAsync().get(1, TimeUnit.SECONDS);
                        }
                    },
                    () -> {
                        if (secondToClose != null) {
                            secondToClose.closeAsync().get(1, TimeUnit.SECONDS);
                        }
                    });
        }
        awaitAvailablePermits(BoundedTaskLimits.POOL_WORKERS, permitsBefore);
    }

    @Test
    void completedCloseFailureReleasesWorkerPermitForAnotherPool() throws Exception {
        BoundedTaskLimiter workerPermits = new BoundedTaskLimiter(1);
        IllegalStateException closeFailure = new IllegalStateException("physical close failed");
        WorkerPoolController<TestWorker> failed = controllerWithPermits(
                () -> new TestWorker(1),
                worker -> {
                    throw closeFailure;
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                workerPermits);

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> failed.closeAsync().get(1, TimeUnit.SECONDS));

        assertSame(closeFailure, observed.getCause());
        assertEquals(1, workerPermits.availablePermits());
        assertPartition(failed, 0, 0, 0, 0, 0);
        assertEquals(1, failed.metrics().retired());
        assertEquals(1, failed.metrics().failedWorkerCloses());

        WorkerPoolController<TestWorker> recovered = controllerWithPermits(
                () -> new TestWorker(2),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                workerPermits);
        recovered.closeAsync().get(1, TimeUnit.SECONDS);

        awaitAvailablePermits(workerPermits, 1);
    }

    @Test
    void retirementInitiatesWholeBatchAndCompletesReadyWorkersIndependently() throws Exception {
        CompletableFuture<WorkerRetirement.Outcome> firstOutcome = new CompletableFuture<>();
        CompletableFuture<WorkerRetirement.Outcome> secondOutcome =
                CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
        CountDownLatch firstInitiated = new CountDownLatch(1);
        CountDownLatch secondInitiated = new CountDownLatch(1);
        AtomicInteger workerIds = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(workerIds.incrementAndGet()),
                worker -> {
                    if (worker.id() == 1) {
                        firstInitiated.countDown();
                        return firstOutcome;
                    }
                    secondInitiated.countDown();
                    return secondOutcome;
                },
                new Options(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "test worker",
                "test-two-phase-");
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
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ofNanos(1), false));
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
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

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
                new Options(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

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
                new Options(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

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
                new Options(3, 3, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

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
        WorkerPoolController<CloseAwareWorker> pool = new WorkerPoolController<>(
                () -> session,
                worker -> WorkerCloseSupport.closeOutcome(worker, worker.terminal, worker.physicalCleanup),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "close-aware worker",
                "test-close-aware-");
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
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
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

    @Test
    void drainedCallbackAllowsAnotherThreadToAcquirePoolLock() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        AtomicReference<PoolMetrics.Snapshot> callbackMetrics = new AtomicReference<>();
        ExecutorService metricsExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> callback = pool.closeAsync().thenRun(() -> {
            try {
                callbackMetrics.set(metricsExecutor.submit(pool::metrics).get(500, TimeUnit.MILLISECONDS));
            } catch (Exception failure) {
                throw new AssertionError("metrics thread could not acquire the pool lock", failure);
            }
        });

        try {
            pool.closeAsync();
            callback.get(1, TimeUnit.SECONDS);
            assertEquals(0, callbackMetrics.get().size());
        } finally {
            metricsExecutor.shutdownNow();
            assertTrue(metricsExecutor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }
}

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

import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

final class WorkerPoolControllerTest {

    @Test
    void delayedFailureReportsCannotContaminateAReusedRetirementOwnerAndRemainExactOnce() throws Exception {
        ReusableRetirementDispatcher dispatcher = new ReusableRetirementDispatcher();
        List<Thread> reportedSources = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Throwable> reportedFailures = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch firstReport = new CountDownLatch(1);
        CountDownLatch reports = new CountDownLatch(2);
        ClassLoader hostileLoader = new ClassLoader(null) {};
        WorkerPoolController.LateFailureReporter hostileReporter = (source, failure) -> {
            reportedSources.add(source);
            reportedFailures.add(failure);
            source.setName("hostile-report-contamination");
            source.setContextClassLoader(hostileLoader);
            source.interrupt();
            firstReport.countDown();
            reports.countDown();
        };
        AssertionError firstFailure = new AssertionError("first delayed retirement failure");
        AssertionError secondFailure = new AssertionError("second delayed retirement failure");
        try {
            failRetirementOutcome(dispatcher, hostileReporter, firstFailure);
            assertTrue(firstReport.await(1, TimeUnit.SECONDS));
            assertEquals(dispatcher.initialName(), dispatcher.owner().getName());
            assertSame(dispatcher.initialClassLoader(), dispatcher.owner().getContextClassLoader());

            failRetirementOutcome(dispatcher, hostileReporter, secondFailure);
            assertTrue(reports.await(1, TimeUnit.SECONDS));
            PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);

            assertEquals(dispatcher.initialName(), dispatcher.owner().getName());
            assertSame(dispatcher.initialClassLoader(), dispatcher.owner().getContextClassLoader());
            assertFalse(dispatcher.owner().isInterrupted());
            assertEquals(2, reportedSources.size());
            assertTrue(reportedSources.stream().noneMatch(source -> source == dispatcher.owner()));
            assertEquals(1, countIdentity(reportedFailures, firstFailure));
            assertEquals(1, countIdentity(reportedFailures, secondFailure));
        } finally {
            dispatcher.close();
        }
    }

    private static void failRetirementOutcome(
            ReusableRetirementDispatcher dispatcher,
            WorkerPoolController.LateFailureReporter lateFailureReporter,
            Throwable failure)
            throws Exception {
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(1),
                closeAction(worker -> {}),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "test worker",
                "test-",
                task -> Threading.start("test-replenish-", task),
                dispatcher::dispatch,
                lateFailureReporter,
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(
                        () -> {}, ignored -> {}, ignored -> throwUnchecked(failure), () -> {}));

        ExecutionException terminal =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));
        assertSame(failure, terminal.getCause());
    }

    private static int countIdentity(List<Throwable> failures, Throwable expected) {
        return Math.toIntExact(
                failures.stream().filter(failure -> failure == expected).count());
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
    void multiplePoolsQueueMandatoryRetirementsBehindBusyOwnerWithoutLosingCleanup() throws Exception {
        PoolRetirementDispatcher dispatcher = new PoolRetirementDispatcher(
                new BoundedTaskRunner.Limiter(1), Threading::start, "test-external-saturation-");
        CloseAwareWorker firstSession = new CloseAwareWorker();
        CloseAwareWorker secondSession = new CloseAwareWorker();
        WorkerPoolController<CloseAwareWorker> firstPool = new WorkerPoolController<>(
                () -> firstSession,
                (worker, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        worker, worker.terminal, worker.physicalCleanup, admission, dispatcher::dispatch),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "close-aware worker",
                "test-external-saturation-",
                System::nanoTime,
                dispatcher::dispatch);
        WorkerPoolController<CloseAwareWorker> secondPool = new WorkerPoolController<>(
                () -> secondSession,
                (worker, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        worker, worker.terminal, worker.physicalCleanup, admission, dispatcher::dispatch),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "close-aware worker",
                "test-external-saturation-",
                System::nanoTime,
                dispatcher::dispatch);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        PoolRetirementDispatcher.Ownership blocker = dispatcher.dispatch(() -> {
            ownerStarted.countDown();
            awaitIgnoringInterrupt(releaseOwner);
        });
        try {
            assertTrue(ownerStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> firstClose = firstPool.closeAsync();
            CompletableFuture<Void> secondClose = secondPool.closeAsync();

            assertFalse(firstClose.isDone());
            assertFalse(secondClose.isDone());
            assertPartition(firstPool, 1, 0, 0, 0, 1);
            assertPartition(secondPool, 1, 0, 0, 0, 1);
            assertEquals(0, firstSession.physicalCloseCalls.get());
            assertEquals(0, secondSession.physicalCloseCalls.get());

            releaseOwner.countDown();
            blocker.completion().get(1, TimeUnit.SECONDS);
            firstClose.get(1, TimeUnit.SECONDS);
            secondClose.get(1, TimeUnit.SECONDS);
            firstPool.slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            secondPool.slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);

            assertPartition(firstPool, 0, 0, 0, 0, 0);
            assertPartition(secondPool, 0, 0, 0, 0, 0);
            assertEquals(1, firstPool.metrics().retired());
            assertEquals(1, secondPool.metrics().retired());
            assertEquals(0, firstPool.metrics().failedWorkerCloses());
            assertEquals(0, secondPool.metrics().failedWorkerCloses());
            assertEquals(1, firstSession.physicalCloseCalls.get());
            assertEquals(1, secondSession.physicalCloseCalls.get());
        } finally {
            releaseOwner.countDown();
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void synchronousCloseCallbackRunsAfterCleanupAndCannotBlockRetirementOwner() throws Exception {
        PoolRetirementDispatcher dispatcher = new PoolRetirementDispatcher(
                new BoundedTaskRunner.Limiter(1), Threading::start, "test-close-callback-retirement-");
        CloseAwareWorker session = new CloseAwareWorker();
        CountDownLatch closeInitiationEntered = new CountDownLatch(1);
        CountDownLatch releaseCloseInitiation = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        WorkerPoolController<CloseAwareWorker> pool = new WorkerPoolController<>(
                () -> session,
                (worker, admission) -> {
                    closeInitiationEntered.countDown();
                    awaitIgnoringInterrupt(releaseCloseInitiation);
                    return WorkerCloseSupport.initiateCloseAndObserve(
                            worker, worker.terminal, worker.physicalCleanup, admission, dispatcher::dispatch);
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "close-aware worker",
                "test-close-callback-",
                System::nanoTime,
                dispatcher::dispatch);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CompletableFuture<Void> close = pool.closeAsync();
        try {
            assertTrue(closeInitiationEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> callback = close.handle((ignored, failure) -> {
                callbackThread.set(Thread.currentThread());
                callbackEntered.countDown();
                awaitIgnoringInterrupt(releaseCallback);
                return null;
            });

            releaseCloseInitiation.countDown();

            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            close.get(1, TimeUnit.SECONDS);
            pool.slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);

            assertFalse(callback.isDone());
            assertTrue(callbackThread.get().getName().startsWith("procwright-pool-completion-"));
            assertPartition(pool, 0, 0, 0, 0, 0);
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertEquals(1, session.physicalCloseCalls.get());
            releaseCallback.countDown();
            callback.get(1, TimeUnit.SECONDS);
        } finally {
            releaseCloseInitiation.countDown();
            releaseCallback.countDown();
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);
            PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void eightBlockingPublicCloseCallbacksDoNotPreventNinthPoolCompletion() throws Exception {
        int blockingPools = 8;
        CountDownLatch callbacksEntered = new CountDownLatch(blockingPools);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        List<CompletableFuture<WorkerPoolController.CloseOutcome>> closeOutcomes = new ArrayList<>();
        List<WorkerPoolController<TestWorker>> pools = new ArrayList<>();
        List<CompletableFuture<Void>> callbacks = new ArrayList<>();
        CompletableFuture<Void> ninthClose = null;
        try {
            for (int index = 0; index <= blockingPools; index++) {
                int workerId = index;
                CompletableFuture<WorkerPoolController.CloseOutcome> closeOutcome = new CompletableFuture<>();
                closeOutcomes.add(closeOutcome);
                pools.add(new WorkerPoolController<>(
                        () -> new TestWorker(workerId),
                        (worker, admission) -> () -> closeOutcome,
                        new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                        Failures.INSTANCE,
                        "callback-isolation worker",
                        "test-callback-isolation-"));
            }

            for (int index = 0; index < blockingPools; index++) {
                CompletableFuture<Void> close = publicCloseView(pools.get(index));
                callbacks.add(close.thenRun(() -> {
                    callbacksEntered.countDown();
                    awaitIgnoringInterrupt(releaseCallbacks);
                }));
                closeOutcomes.get(index).complete(WorkerPoolController.CloseOutcome.success());
            }
            assertTrue(callbacksEntered.await(1, TimeUnit.SECONDS));

            ninthClose = publicCloseView(pools.get(blockingPools));
            closeOutcomes.get(blockingPools).complete(WorkerPoolController.CloseOutcome.success());

            ninthClose.get(1, TimeUnit.SECONDS);
            assertEquals(1, pools.get(blockingPools).metrics().retired());
            assertEquals(0, pools.get(blockingPools).metrics().size());
        } finally {
            releaseCallbacks.countDown();
            for (CompletableFuture<WorkerPoolController.CloseOutcome> closeOutcome : closeOutcomes) {
                closeOutcome.complete(WorkerPoolController.CloseOutcome.success());
            }
            for (CompletableFuture<Void> callback : callbacks) {
                callback.get(1, TimeUnit.SECONDS);
            }
            for (WorkerPoolController<TestWorker> pool : pools) {
                pool.closeAsync().get(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void eightBlockingWorkerExitCallbacksDoNotStarveNinthAdmittedPoolClose() throws Exception {
        int blockingPools = 8;
        int admissionCapacity = (blockingPools + 1) * 2;
        PoolRetirementDispatcher.AdmissionPool admissions =
                new PoolRetirementDispatcher.AdmissionPool(admissionCapacity);
        WorkerPoolController.RetirementAdmissionProvider admissionProvider = deadlineNanos -> {
            PoolRetirementDispatcher.Admission admission = admissions.tryAcquire();
            if (admission == null) {
                throw new java.util.concurrent.TimeoutException("test lifecycle capacity exhausted");
            }
            return admission;
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
                        (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                                session, session.onExit(), session.physicalCleanup(), admission),
                        new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                        Failures.INSTANCE,
                        "exit-callback worker",
                        "test-exit-callback-",
                        task -> Threading.start("test-exit-callback-replenish-", task),
                        PoolRetirementDispatcher::execute,
                        (thread, failure) -> {},
                        System::nanoTime,
                        null,
                        new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {}),
                        admissionProvider));
            }
            assertEquals(0, admissions.availablePermits(), "all pool and worker owners must be admitted up front");

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
            pools.get(blockingPools).slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            awaitAvailableAdmissions(admissions, 2);

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
            awaitAvailableAdmissions(admissions, admissionCapacity);
            for (ExitCallbackWorker worker : workers) {
                assertEquals(1, worker.closeCalls.get());
            }
            PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void aggregateWorkerAdmissionRejectsAnotherPoolBeforeFactoryAndRecoversAfterRetirement() throws Exception {
        PoolRetirementDispatcher.AdmissionPool completionAdmissions = new PoolRetirementDispatcher.AdmissionPool(3);
        PoolRetirementDispatcher.AdmissionPool workerAdmissions = new PoolRetirementDispatcher.AdmissionPool(1);
        AtomicInteger factoryInvocations = new AtomicInteger();
        Options options = new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false);
        WorkerPoolController<TestWorker> accepted = controllerWithSeparateAdmissions(
                () -> new TestWorker(factoryInvocations.incrementAndGet()),
                worker -> {},
                options,
                completionAdmissions,
                workerAdmissions);
        try {
            assertEquals(1, factoryInvocations.get());
            assertEquals(2, completionAdmissions.availablePermits(), "completion-owner capacity remains available");
            assertEquals(0, workerAdmissions.availablePermits());

            PoolFailure rejected = assertThrows(
                    PoolFailure.class,
                    () -> controllerWithSeparateAdmissions(
                            () -> new TestWorker(factoryInvocations.incrementAndGet()),
                            worker -> {},
                            options,
                            completionAdmissions,
                            workerAdmissions));

            assertEquals(FailureKind.STARTUP_FAILED, rejected.kind);
            assertEquals(1, factoryInvocations.get(), "admission rejection must precede the worker factory");
            awaitAvailableAdmissions(completionAdmissions, 2);
            assertEquals(2, completionAdmissions.availablePermits(), "rejected pool must release its owner admission");
            assertEquals(0, workerAdmissions.availablePermits());
        } finally {
            accepted.closeAsync().get(1, TimeUnit.SECONDS);
        }
        awaitAvailableAdmissions(workerAdmissions, 1);
        awaitAvailableAdmissions(completionAdmissions, 3);

        WorkerPoolController<TestWorker> recovered = controllerWithSeparateAdmissions(
                () -> new TestWorker(factoryInvocations.incrementAndGet()),
                worker -> {},
                options,
                completionAdmissions,
                workerAdmissions);
        try {
            assertEquals(2, factoryInvocations.get());
            assertEquals(0, workerAdmissions.availablePermits());
        } finally {
            recovered.closeAsync().get(1, TimeUnit.SECONDS);
        }
        awaitAvailableAdmissions(workerAdmissions, 1);
        awaitAvailableAdmissions(completionAdmissions, 3);
    }

    @Test
    void maximumAcceptedWarmupUsesWorkerCapacityIndependentlyFromPoolCompletionCapacity() throws Exception {
        int workerCapacity = PoolRetirementDispatcher.sharedWorkerAdmissionCapacity();
        PoolRetirementDispatcher.AdmissionPool completionAdmissions = new PoolRetirementDispatcher.AdmissionPool(1);
        PoolRetirementDispatcher.AdmissionPool workerAdmissions =
                new PoolRetirementDispatcher.AdmissionPool(workerCapacity);
        AtomicInteger created = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controllerWithSeparateAdmissions(
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
                completionAdmissions,
                workerAdmissions);
        try {
            assertEquals(workerCapacity, created.get());
            assertEquals(0, workerAdmissions.availablePermits());
            assertEquals(0, completionAdmissions.availablePermits());
            assertPartition(pool, workerCapacity, workerCapacity, 0, 0, 0);
        } finally {
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        awaitAvailableAdmissions(workerAdmissions, workerCapacity);
        awaitAvailableAdmissions(completionAdmissions, 1);
    }

    @Test
    void completedCloseFailureReleasesWorkerAdmissionForAnotherPool() throws Exception {
        PoolRetirementDispatcher.AdmissionPool completionAdmissions = new PoolRetirementDispatcher.AdmissionPool(2);
        PoolRetirementDispatcher.AdmissionPool workerAdmissions = new PoolRetirementDispatcher.AdmissionPool(1);
        IllegalStateException closeFailure = new IllegalStateException("physical close failed");
        WorkerPoolController<TestWorker> failed = controllerWithSeparateAdmissions(
                () -> new TestWorker(1),
                worker -> {
                    throw closeFailure;
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                completionAdmissions,
                workerAdmissions);

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> failed.closeAsync().get(1, TimeUnit.SECONDS));

        assertSame(closeFailure, observed.getCause());
        assertEquals(1, workerAdmissions.availablePermits());
        assertPartition(failed, 0, 0, 0, 0, 0);
        assertEquals(1, failed.metrics().retired());
        assertEquals(1, failed.metrics().failedWorkerCloses());

        WorkerPoolController<TestWorker> recovered = controllerWithSeparateAdmissions(
                () -> new TestWorker(2),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                completionAdmissions,
                workerAdmissions);
        recovered.closeAsync().get(1, TimeUnit.SECONDS);

        awaitAvailableAdmissions(workerAdmissions, 1);
        awaitAvailableAdmissions(completionAdmissions, 2);
    }

    @Test
    void testOnlyPartitionVerifierRejectsForeignIdleQueueReplacement() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ArrayDeque<WorkerPoolController.Worker<TestWorker>> idle = workerQueue(pool, "idle");
        WorkerPoolController.Worker<TestWorker> registered = idle.removeFirst();
        WorkerPoolController.Worker<TestWorker> foreign = foreignWorkerMatchingState(registered);
        idle.addFirst(foreign);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, pool::verifyPartitionForTest);
            assertEquals("idle queue contains an invalid worker", failure.getMessage());
        } finally {
            idle.remove(foreign);
            idle.addFirst(registered);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void testOnlyPartitionVerifierRejectsForeignRetirementQueueReplacement() throws Exception {
        PoolRetirementDispatcher.AdmissionPool completionAdmissions = new PoolRetirementDispatcher.AdmissionPool(1);
        PoolRetirementDispatcher.AdmissionPool workerAdmissions = new PoolRetirementDispatcher.AdmissionPool(1);
        HeldTerminalRetirementDispatcher dispatcher = new HeldTerminalRetirementDispatcher();
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(1),
                closeAction(worker -> {}),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "foreign-retirement worker",
                "test-foreign-retirement-",
                Runnable::run,
                dispatcher::dispatch,
                (thread, failure) -> {},
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {}),
                immediateAdmissions(completionAdmissions),
                immediateAdmissions(workerAdmissions));
        WorkerPoolController.Worker<TestWorker> leased = pool.acquire((worker, deadlineNanos) -> HEALTHY);
        pool.release(leased, false, PooledWorkerRetireReason.CLOSED);
        ArrayDeque<WorkerPoolController.Worker<TestWorker>> retirementQueue = workerQueue(pool, "retirementQueue");
        WorkerPoolController.Worker<TestWorker> registered = retirementQueue.removeFirst();
        WorkerPoolController.Worker<TestWorker> foreign = foreignWorkerMatchingState(registered);
        retirementQueue.addFirst(foreign);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, pool::verifyPartitionForTest);
            assertEquals("retirement queue contains an invalid worker", failure.getMessage());
        } finally {
            retirementQueue.remove(foreign);
            retirementQueue.addFirst(registered);
            dispatcher.runNext();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
        awaitAvailableAdmissions(workerAdmissions, 1);
        awaitAvailableAdmissions(completionAdmissions, 1);
    }

    @Test
    void testOnlyPartitionVerifierRejectsSlotRegistryBeyondPerPoolMaximum() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        Set<WorkerPoolController.Worker<TestWorker>> slots = workerSet(pool, "slots");
        WorkerPoolController.Worker<TestWorker> foreign = new WorkerPoolController.Worker<>();
        slots.add(foreign);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, pool::verifyPartitionForTest);
            assertEquals("pool slot registry exceeds configured capacity", failure.getMessage());
        } finally {
            slots.remove(foreign);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void retirementInitiatesWholeBatchAndCompletesReadyWorkersIndependently() throws Exception {
        CompletableFuture<WorkerPoolController.CloseOutcome> firstOutcome = new CompletableFuture<>();
        CompletableFuture<WorkerPoolController.CloseOutcome> secondOutcome =
                CompletableFuture.completedFuture(WorkerPoolController.CloseOutcome.success());
        CountDownLatch firstInitiated = new CountDownLatch(1);
        CountDownLatch secondInitiated = new CountDownLatch(1);
        AtomicInteger workerIds = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(workerIds.incrementAndGet()),
                (worker, admission) -> {
                    if (worker.id() == 1) {
                        firstInitiated.countDown();
                        return () -> firstOutcome;
                    }
                    secondInitiated.countDown();
                    return () -> secondOutcome;
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

            firstOutcome.complete(WorkerPoolController.CloseOutcome.success());
            drained.get(1, TimeUnit.SECONDS);

            assertPartition(pool, 0, 0, 0, 0, 0);
            assertEquals(2, pool.metrics().retired());
        } finally {
            firstOutcome.complete(WorkerPoolController.CloseOutcome.success());
            pool.closeAsync();
        }
    }

    @Test
    void retirementRolloverWaitsForCurrentAdmissionClaimRelease() throws Exception {
        PoolRetirementDispatcher.AdmissionPool admissions = new PoolRetirementDispatcher.AdmissionPool(3);
        PausingRetirementDispatcher dispatcher = new PausingRetirementDispatcher();
        AtomicInteger workerIds = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(workerIds.incrementAndGet()),
                closeAction(worker -> closeCalls.incrementAndGet()),
                new Options(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "rollover worker",
                "test-rollover-",
                task -> Threading.start("test-rollover-replenish-", task),
                dispatcher::dispatch,
                (thread, failure) -> {},
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {}),
                immediateAdmissions(admissions));
        WorkerPoolController.Worker<TestWorker> first = pool.acquire((worker, deadline) -> HEALTHY);
        WorkerPoolController.Worker<TestWorker> second = pool.acquire((worker, deadline) -> HEALTHY);
        CompletableFuture<Void> drain = null;
        try {
            pool.release(first, false, PooledWorkerRetireReason.WORKER_FAILED);
            assertTrue(dispatcher.awaitFirstTaskReturned());
            assertEquals(1, dispatcher.dispatches.get());
            assertEquals(1, closeCalls.get());
            assertPartition(pool, 1, 0, 1, 0, 0);

            pool.release(second, false, PooledWorkerRetireReason.WORKER_FAILED);
            drain = pool.closeAsync();

            assertEquals(1, dispatcher.dispatches.get(), "FINISHING owner must retain rollover ownership");
            assertEquals(1, closeCalls.get());
            assertPartition(pool, 1, 0, 0, 0, 1);
            assertFalse(drain.isDone());
            assertEquals(1, admissions.availablePermits());

            dispatcher.releaseFirstCompletion();
            drain.get(1, TimeUnit.SECONDS);
            pool.slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            awaitAvailableAdmissions(admissions, 3);

            assertEquals(2, dispatcher.dispatches.get());
            assertEquals(2, closeCalls.get());
            assertEquals(2, pool.metrics().retired());
            assertPartition(pool, 0, 0, 0, 0, 0);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(3, admissions.availablePermits(), "pool and worker admissions must return exactly once");
        } finally {
            dispatcher.releaseFirstCompletion();
            pool.closeAsync();
            if (drain != null) {
                drain.handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void retirementRuntimeDispatchFailureUsesBoundedSharedRecovery() throws Exception {
        assertRetirementDispatchFailure(new IllegalStateException("retirement dispatch failed"));
    }

    @Test
    void retirementErrorDispatchFailureUsesBoundedSharedRecovery() throws Exception {
        assertRetirementDispatchFailure(new AssertionError("retirement dispatch failed fatally"));
    }

    @Test
    void retirementOutcomeCallbackErrorDoesNotStrandRemainingBatch() throws Exception {
        AssertionError callbackFailure = new AssertionError("retirement outcome callback failed");
        PoolRetirementDispatcher.AdmissionPool admissions = new PoolRetirementDispatcher.AdmissionPool(3);
        AtomicInteger workerIds = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(workerIds.incrementAndGet()),
                closeAction(worker -> closeCalls.incrementAndGet()),
                new Options(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "callback-failure worker",
                "test-callback-failure-",
                task -> Threading.start("test-callback-failure-replenish-", task),
                PoolRetirementDispatcher::execute,
                (thread, failure) -> {},
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(
                        () -> {},
                        failure -> {},
                        failure -> {
                            if (callbacks.incrementAndGet() == 1) {
                                throw callbackFailure;
                            }
                        },
                        () -> {}),
                immediateAdmissions(admissions));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

        assertSame(callbackFailure, observed.getCause());
        assertEquals(2, callbacks.get());
        assertEquals(2, closeCalls.get());
        assertEquals(2, pool.metrics().retired());
        assertPartition(pool, 0, 0, 0, 0, 0);
        awaitAvailableAdmissions(admissions, 3);
    }

    @Test
    void defaultRetirementUsesSharedBoundedDispatcher() throws Exception {
        AtomicReference<Thread> retirementThread = new AtomicReference<>();
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(1),
                (worker, admission) -> {
                    retirementThread.set(Thread.currentThread());
                    return () -> CompletableFuture.completedFuture(WorkerPoolController.CloseOutcome.success());
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "test worker",
                "controller-local-prefix-");

        pool.closeAsync().get(1, TimeUnit.SECONDS);

        assertTrue(retirementThread.get().getName().startsWith("procwright-terminal-retirement-"));
        assertFalse(retirementThread.get().getName().startsWith("controller-local-prefix-"));
    }

    @Test
    void minIdleIsEstablishedAndMaintainedWhileWorkerIsLeased() throws Exception {
        AtomicInteger created = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {},
                new Options(2, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true));
        try {
            assertTrue(pool.awaitMetrics(metrics -> metrics.idle() == 1, Duration.ofSeconds(1)));

            WorkerPoolController.Worker<TestWorker> leased = pool.acquire((worker, deadline) -> HEALTHY);

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
    void acquireMetricFailureCannotStrandLeaseBeforeHandoff() throws Exception {
        AssertionError metricFailure = new AssertionError("acquire metric clock failed");
        AtomicInteger clockCalls = new AtomicInteger();
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> physicalCloses.incrementAndGet(),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                Runnable::run,
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
    void postPermitClaimFaultReleasesPermitAndReservation() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
        AssertionError claimFailure = new AssertionError("startup claim handoff failed");
        WorkerPoolController.FaultInjector faultInjector = new WorkerPoolController.FaultInjector();
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(factoryCalls.incrementAndGet()),
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                faultInjectionHooks(faultInjector));
        try {
            faultInjector.failNext(WorkerPoolController.FaultPoint.AFTER_STARTUP_CLAIM, claimFailure);

            AssertionError observed =
                    assertThrows(AssertionError.class, () -> pool.acquire((worker, deadline) -> HEALTHY));

            assertSame(claimFailure, observed);
            assertSame(claimFailure, faultInjector.triggeredFailure());
            assertEquals(0, observed.getSuppressed().length);
            assertEquals(0, factoryCalls.get());
            assertEquals(0, pool.metrics().failedStartups());
            assertPartition(pool, 0, 0, 0, 0, 0);
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());

            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void processExitHealthOutcomeUsesOnlyProcessExitedRetirementReason() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> physicalCloses.incrementAndGet(),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            WorkerPoolController.Worker<TestWorker> worker =
                    pool.acquire((candidate, deadline) -> candidate.id() == 1 ? PROCESS_EXITED : HEALTHY);

            assertEquals(2, worker.session().id());
            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.PROCESS_EXITED));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.HEALTH_FAILED));
            pool.release(worker, true, PooledWorkerRetireReason.WORKER_FAILED);
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
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        try {
            WorkerPoolController.Worker<TestWorker> worker =
                    pool.acquire((candidate, deadline) -> candidate.id() == 1 ? HEALTH_FAILED : HEALTHY);

            assertEquals(2, worker.session().id());
            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.PROCESS_EXITED));
            pool.release(worker, true, PooledWorkerRetireReason.WORKER_FAILED);
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
                new Options(1, 1, 0, Duration.ofMillis(25), Integer.MAX_VALUE, Duration.ZERO, false));
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

            WorkerPoolController.Worker<TestWorker> sameWorker = pool.acquire((worker, deadline) -> HEALTHY);
            assertEquals(1, sameWorker.session().id());
            pool.release(sameWorker, true, PooledWorkerRetireReason.WORKER_FAILED);
        } finally {
            pool.closeAsync();
        }
    }

    @Test
    void acquireTimeoutHealthOutcomeReturnsWorkerWithoutHealthRetirement() {
        WorkerPoolController<TestWorker> pool = inlineController(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
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
    void fatalReplenishmentErrorAfterCommitClosesPoolWithoutExternalActivity() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                new Options(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                task -> Threading.start("test-fatal-replenish-", () -> {
                    try {
                        task.run();
                    } catch (Throwable failure) {
                        ownerFailure.set(failure);
                    } finally {
                        ownerFinished.countDown();
                    }
                }),
                Runnable::run,
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
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        } finally {
            releaseFactory.countDown();
            pool.closeAsync();
        }
    }

    @Test
    void fatalReplenishmentErrorBeforeConstructionCommitKeepsExactIdentity() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
        AssertionError fatalError = new AssertionError("pre-commit replenishment failed");
        CountDownLatch ownerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<WorkerPoolController<TestWorker>> unexpectedlyConstructed = new AtomicReference<>();
        WorkerPoolController.TestHooks hooks =
                new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {
                    awaitIgnoringInterrupt(ownerFinished);
                });
        try {
            AssertionError observed = assertThrows(
                    AssertionError.class,
                    () -> unexpectedlyConstructed.set(controller(
                            () -> {
                                throw fatalError;
                            },
                            worker -> {},
                            new Options(1, 0, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                            task -> Threading.start("test-precommit-replenish-", () -> {
                                try {
                                    task.run();
                                } catch (Throwable failure) {
                                    ownerFailure.set(failure);
                                } finally {
                                    ownerFinished.countDown();
                                }
                            }),
                            Runnable::run,
                            (thread, failure) -> {},
                            System::nanoTime,
                            null,
                            hooks)));

            assertSame(fatalError, observed);
            assertSame(fatalError, ownerFailure.get());
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        } finally {
            WorkerPoolController<TestWorker> pool = unexpectedlyConstructed.get();
            if (pool != null) {
                pool.closeAsync();
            }
        }
    }

    @Test
    void queuedLateErrorReportCannotRetainAbandonedStartupSlot() throws Exception {
        PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
        CountDownLatch releaseReports = new CountDownLatch(1);
        CountDownLatch reportsStarted = new CountDownLatch(8);
        List<PoolRetirementDispatcher.Ownership> saturatedReports = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            saturatedReports.add(PoolRetirementDispatcher.report(() -> {
                reportsStarted.countDown();
                awaitIgnoringInterrupt(releaseReports);
            }));
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
                new Options(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                task -> Threading.start("test-retire-", task),
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
            pool.closeAsync();
            assertFalse(pool.closeAsync().isDone());

            releaseFactory.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            assertEquals(1L, reporterEntered.getCount(), "late report must remain queued behind active owners");
            assertEquals(1, pool.metrics().failedStartups());
            assertPartition(pool, 0, 0, 0, 0, 0);

            releaseReports.countDown();
            assertTrue(reporterEntered.await(1, TimeUnit.SECONDS));
            assertSame(lateError, reported.get());
        } finally {
            releaseFactory.countDown();
            releaseReporter.countDown();
            releaseReports.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            for (PoolRetirementDispatcher.Ownership ownership : saturatedReports) {
                ownership.completion().get(1, TimeUnit.SECONDS);
            }
            PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
            pool.closeAsync();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void acquireTimeoutBoundsWorkerStartupAndRecordsFailedWait() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                new Options(1, 0, 0, Duration.ofMillis(50), Integer.MAX_VALUE, Duration.ZERO, false));
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

            pool.closeAsync();
            assertFalse(pool.closeAsync().isDone());
            allowStartupToFinish.countDown();

            pool.closeAsync().get(1, TimeUnit.SECONDS);
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
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void closeBeforeFactorySuccessSignalRetiresLateWorkerWithoutPublishingIt() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch factoryTerminalSignal = new CountDownLatch(1);
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
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                startupTerminalHooks(factoryTerminalSignal::countDown, () -> {}));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 1, 0);

            pool.closeAsync();

            assertFalse(acquire.isDone());
            assertFalse(pool.closeAsync().isDone(), "the running startup still owns the pool slot");
            assertPartition(pool, 1, 0, 0, 1, 0);
            releaseFactory.countDown();
            assertTrue(factoryTerminalSignal.await(1, TimeUnit.SECONDS));

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure poolFailure = (PoolFailure) failure.getCause();
            assertEquals(FailureKind.CLOSED, poolFailure.kind);
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertFalse(pool.closeAsync().isDone(), "the physical worker close is still in progress");
            assertPartition(pool, 1, 0, 0, 0, 1);
            assertEquals(0, pool.metrics().idle());
            assertEquals(0, pool.metrics().leased());

            releaseRetirement.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);

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
    void factorySuccessSignalBeforeClosePublishesLeaseAndRetiresItOnRelease() throws Exception {
        CountDownLatch factoryTerminalSignal = new CountDownLatch(1);
        CountDownLatch releaseFactoryPublication = new CountDownLatch(1);
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
                new Options(1, 0, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false),
                startupTerminalHooks(
                        () -> {
                            factoryTerminalSignal.countDown();
                            awaitIgnoringInterrupt(releaseFactoryPublication);
                        },
                        () -> {}));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        WorkerPoolController.Worker<TestWorker> worker = null;
        boolean released = false;
        try {
            Future<WorkerPoolController.Worker<TestWorker>> acquire =
                    executor.submit(() -> pool.acquire((candidate, deadline) -> HEALTHY));
            assertTrue(factoryTerminalSignal.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 1, 0);

            pool.closeAsync();

            assertFalse(acquire.isDone(), "factory result publication is still paused");
            assertFalse(pool.closeAsync().isDone(), "the successful startup still owns the pool slot");
            assertPartition(pool, 1, 0, 0, 1, 0);
            releaseFactoryPublication.countDown();

            worker = acquire.get(1, TimeUnit.SECONDS);
            assertEquals(1, pool.metrics().created());
            assertEquals(0, pool.metrics().failedStartups());
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertEquals(0, physicalCloses.get());
            assertPartition(pool, 1, 0, 1, 0, 0);

            pool.release(worker, true, null);
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
            releaseFactoryPublication.countDown();
            if (worker != null && !released) {
                pool.release(worker, true, null);
            }
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
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
                new Options(1, 0, 0, Duration.ofMillis(150), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.CLOSED, ((PoolFailure) failure.getCause()).kind);
            assertFalse(pool.closeAsync().isDone(), "the abandoned factory still owns its startup slot");
            assertPartition(pool, 1, 0, 0, 1, 0);

            releaseFactory.countDown();
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertFalse(pool.closeAsync().isDone(), "the late worker is still being physically closed");
            assertPartition(pool, 1, 0, 0, 0, 1);
            assertEquals(0, pool.metrics().idle());
            assertEquals(0, pool.metrics().leased());

            releaseRetirement.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().failedStartups());
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
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    throw startupFailure;
                },
                worker -> physicalCloses.incrementAndGet(),
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync();
            releaseFactory.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure poolFailure = (PoolFailure) failure.getCause();
            assertEquals(FailureKind.CLOSED, poolFailure.kind);
            assertEquals(1, poolFailure.getSuppressed().length);
            assertSame(startupFailure, poolFailure.getSuppressed()[0]);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
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
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
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
    void factoryFailureSignalBeforeCloseRemainsStartupFailedWhileWaiterIsPaused() throws Exception {
        CountDownLatch factoryTerminalSignal = new CountDownLatch(1);
        CountDownLatch releaseFactoryPublication = new CountDownLatch(1);
        IllegalStateException startupFailure = new IllegalStateException("controlled startup failure");
        AtomicInteger physicalCloses = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    throw startupFailure;
                },
                worker -> physicalCloses.incrementAndGet(),
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                startupTerminalHooks(
                        () -> {
                            factoryTerminalSignal.countDown();
                            awaitIgnoringInterrupt(releaseFactoryPublication);
                        },
                        () -> {}));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryTerminalSignal.await(1, TimeUnit.SECONDS));

            pool.closeAsync();

            assertFalse(acquire.isDone(), "factory outcome has not yet been published to its waiter");
            assertFalse(pool.closeAsync().isDone(), "the terminal startup still owns its slot");
            assertPartition(pool, 1, 0, 0, 1, 0);
            releaseFactoryPublication.countDown();

            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure failure = (PoolFailure) observed.getCause();
            assertEquals(FailureKind.STARTUP_FAILED, failure.kind);
            assertSame(startupFailure, failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(0, physicalCloses.get());
            assertEquals(1, pool.metrics().failedStartups());
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseFactoryPublication.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutSignalBeforeCloseRemainsTimeoutAndRetiresLateWorkerAsStartupTimeout() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch timeoutSignal = new CountDownLatch(1);
        CountDownLatch releaseTimeoutProcessing = new CountDownLatch(1);
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
                new Options(1, 0, 0, Duration.ofMillis(80), Integer.MAX_VALUE, Duration.ZERO, false),
                startupTerminalHooks(() -> {}, () -> {
                    timeoutSignal.countDown();
                    awaitIgnoringInterrupt(releaseTimeoutProcessing);
                }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            assertTrue(timeoutSignal.await(1, TimeUnit.SECONDS));

            pool.closeAsync();

            assertFalse(acquire.isDone(), "timeout outcome has not yet been processed by its waiter");
            assertFalse(pool.closeAsync().isDone(), "the timed-out factory still owns its slot");
            assertPartition(pool, 1, 0, 0, 1, 0);
            releaseTimeoutProcessing.countDown();

            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            PoolFailure failure = (PoolFailure) observed.getCause();
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, failure.kind);
            assertEquals(0, failure.getSuppressed().length);

            releaseFactory.countDown();
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 0, 1);
            releaseRetirement.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);

            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().failedStartups());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_TIMEOUT));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.CLOSED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseTimeoutProcessing.countDown();
            releaseFactory.countDown();
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void startupPermitTimeoutSignalBeforeCloseRemainsAcquireTimeout() throws Exception {
        List<BoundedTaskRunner.Permit> occupied = new ArrayList<>();
        int capacity = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
        long permitDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskRunner.WORKER_STARTUPS.acquire(permitDeadline));
        }
        CountDownLatch timeoutSignal = new CountDownLatch(1);
        CountDownLatch releaseTimeoutProcessing = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                new Options(1, 0, 0, Duration.ofMillis(80), Integer.MAX_VALUE, Duration.ZERO, false),
                startupTerminalHooks(() -> {}, () -> {
                    timeoutSignal.countDown();
                    awaitIgnoringInterrupt(releaseTimeoutProcessing);
                }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(timeoutSignal.await(1, TimeUnit.SECONDS));

            pool.closeAsync();

            assertFalse(acquire.isDone(), "permit timeout outcome has not yet been processed by its waiter");
            releaseTimeoutProcessing.countDown();
            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, ((PoolFailure) observed.getCause()).kind);
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(0, factoryCalls.get());
            assertEquals(0, pool.metrics().failedStartups());
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseTimeoutProcessing.countDown();
            occupied.forEach(BoundedTaskRunner.Permit::close);
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(capacity, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void factoryCompletionDecisionBeatsConcurrentWarmupDeadline() throws Exception {
        CountDownLatch factoryTerminalSignal = new CountDownLatch(1);
        CountDownLatch releaseFactoryPublication = new CountDownLatch(1);
        CountDownLatch timeoutSignal = new CountDownLatch(1);
        AtomicInteger physicalCloses = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        WorkerPoolController<TestWorker> pool = null;
        try {
            Future<WorkerPoolController<TestWorker>> construction = executor.submit(() -> controller(
                    () -> new TestWorker(1),
                    worker -> physicalCloses.incrementAndGet(),
                    new Options(1, 1, 0, Duration.ofMillis(80), Integer.MAX_VALUE, Duration.ZERO, false),
                    startupTerminalHooks(
                            () -> {
                                factoryTerminalSignal.countDown();
                                awaitIgnoringInterrupt(releaseFactoryPublication);
                            },
                            timeoutSignal::countDown)));
            assertTrue(factoryTerminalSignal.await(1, TimeUnit.SECONDS));
            assertTrue(timeoutSignal.await(1, TimeUnit.SECONDS));
            assertFalse(construction.isDone(), "factory result publication is deliberately paused");

            releaseFactoryPublication.countDown();
            pool = construction.get(1, TimeUnit.SECONDS);

            assertEquals(1, pool.metrics().created());
            assertEquals(0, pool.metrics().failedStartups());
            assertEquals(0, pool.metrics().retired());
            assertTrue(pool.metrics().retireReasons().isEmpty());
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.STARTUP_TIMEOUT));
            assertPartition(pool, 1, 1, 0, 0, 0);
        } finally {
            releaseFactoryPublication.countDown();
            if (pool != null) {
                pool.closeAsync();
                pool.closeAsync().get(1, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(1, physicalCloses.get());
        }
    }

    @Test
    void timeoutDecisionBeatsFactoryCompletionAtDeadline() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch factoryTerminalSignal = new CountDownLatch(1);
        CountDownLatch timeoutSignal = new CountDownLatch(1);
        CountDownLatch releaseTimeoutProcessing = new CountDownLatch(1);
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
                new Options(1, 0, 0, Duration.ofMillis(80), Integer.MAX_VALUE, Duration.ZERO, false),
                startupTerminalHooks(factoryTerminalSignal::countDown, () -> {
                    timeoutSignal.countDown();
                    awaitIgnoringInterrupt(releaseTimeoutProcessing);
                }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            assertTrue(timeoutSignal.await(1, TimeUnit.SECONDS));

            releaseFactory.countDown();
            assertTrue(factoryTerminalSignal.await(1, TimeUnit.SECONDS));
            releaseTimeoutProcessing.countDown();

            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.ACQUIRE_TIMEOUT, ((PoolFailure) observed.getCause()).kind);
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 0, 1);
            releaseRetirement.countDown();
            assertTrue(pool.awaitMetrics(metrics -> metrics.size() == 0, Duration.ofSeconds(1)));

            assertEquals(1, physicalCloses.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().failedStartups());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_TIMEOUT));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.CLOSED));
            assertPartition(pool, 0, 0, 0, 0, 0);
        } finally {
            releaseTimeoutProcessing.countDown();
            releaseFactory.countDown();
            releaseRetirement.countDown();
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
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
            WorkerPoolController.Worker<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);
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
    void replenishmentSchedulingFailureDoesNotLeakLeasedWorker() throws Exception {
        IllegalStateException schedulingFailure = new IllegalStateException("thread creation failed");
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(2, 1, 1, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                task -> {
                    throw schedulingFailure;
                });
        try {
            WorkerPoolController.Worker<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);

            assertEquals(1, pool.metrics().leased());
            pool.release(worker, true, null);
            assertEquals(0, pool.metrics().leased());
            assertEquals(1, pool.metrics().idle());
        } finally {
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void initialReplenishmentSchedulingFailureRetriesWithoutLosingWarmWorkers() throws Exception {
        IllegalStateException schedulingFailure = new IllegalStateException("thread creation failed");
        AtomicInteger closedWorkers = new AtomicInteger();
        AtomicInteger factoryInvocations = new AtomicInteger();

        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(factoryInvocations.incrementAndGet()),
                worker -> closedWorkers.incrementAndGet(),
                new Options(2, 1, 2, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, true),
                task -> {
                    throw schedulingFailure;
                });

        assertTrue(pool.awaitMetrics(metrics -> metrics.idle() == 2, Duration.ofSeconds(1)));
        assertEquals(2, factoryInvocations.get());
        assertEquals(0, pool.metrics().failedStartups());
        pool.closeAsync();
        pool.closeAsync().get(1, TimeUnit.SECONDS);
        assertEquals(2, closedWorkers.get());
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
    void retiringWorkerKeepsItsSlotUntilCloseCompletes() throws Exception {
        AtomicInteger created = new AtomicInteger();
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch allowRetirement = new CountDownLatch(1);
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(created.incrementAndGet()),
                worker -> {
                    if (worker.id == 1) {
                        retirementEntered.countDown();
                        awaitIgnoringInterrupt(allowRetirement);
                    }
                },
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ofNanos(1), false));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkerPoolController.Worker<TestWorker>> first =
                    executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));

            assertEquals(1, created.get(), "a replacement must not start before the retiring process closes");
            assertEquals(1, pool.metrics().retiring());
            assertEquals(1, pool.metrics().size());
            assertFalse(first.isDone(), "the acquire must remain pending while the only slot is retiring");

            allowRetirement.countDown();
            WorkerPoolController.Worker<TestWorker> leased = first.get(1, TimeUnit.SECONDS);
            assertEquals(2, created.get());
            pool.release(leased, false, PooledWorkerRetireReason.CLOSED);
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

        assertSame(fatalFailure, observed.getCause());
        assertSuppressedExactlyOnce(fatalFailure, runtimeFailure);
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

        assertSame(fatalFailure, observed.getCause());
        assertSuppressedExactlyOnce(fatalFailure, runtimeFailure);
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

        assertSame(firstFatal, observed.getCause());
        assertSuppressedExactlyOnce(firstFatal, runtimeFailure);
        assertSuppressedExactlyOnce(firstFatal, secondFatal);
        assertEquals(3, pool.metrics().failedWorkerCloses());
    }

    @Test
    void constructorClaimsCloseFailureCompletedBeforeAtomicFailExactlyOnce() throws Exception {
        CountDownLatch lateStartupEntered = new CountDownLatch(1);
        CountDownLatch releaseLateStartup = new CountDownLatch(1);
        CountDownLatch constructorCatchEntered = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
        IllegalStateException closeFailure = new IllegalStateException("late worker close failed");
        AtomicReference<Thread> retirementThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> construction = executor.submit(() -> controller(
                    () -> {
                        int id = starts.incrementAndGet();
                        if (id == 2) {
                            lateStartupEntered.countDown();
                            awaitIgnoringInterrupt(releaseLateStartup);
                        }
                        return new TestWorker(id);
                    },
                    worker -> {
                        if (worker.id == 2) {
                            throw closeFailure;
                        }
                    },
                    new Options(2, 2, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false),
                    task -> Threading.start("test-replenish-", task),
                    task -> {
                        Thread thread = Threading.start("test-retire-", task);
                        retirementThread.set(thread);
                    },
                    (thread, failure) -> reports.incrementAndGet(),
                    System::nanoTime,
                    null,
                    new WorkerPoolController.TestHooks(
                            () -> {},
                            primary -> {
                                constructorCatchEntered.countDown();
                                awaitIgnoringInterrupt(closeFinished);
                            },
                            outcome -> {
                                if (outcome == closeFailure) {
                                    closeFinished.countDown();
                                }
                            },
                            () -> {})));

            assertTrue(lateStartupEntered.await(1, TimeUnit.SECONDS));
            assertTrue(constructorCatchEntered.await(1, TimeUnit.SECONDS));
            releaseLateStartup.countDown();

            ExecutionException thrown =
                    assertThrows(ExecutionException.class, () -> construction.get(1, TimeUnit.SECONDS));
            PoolFailure primary = (PoolFailure) thrown.getCause();
            assertEquals(FailureKind.STARTUP_FAILED, primary.kind);
            assertEquals(1, primary.getSuppressed().length);
            PoolFailure cleanup = (PoolFailure) primary.getSuppressed()[0];
            assertEquals(FailureKind.RETIREMENT_FAILED, cleanup.kind);
            assertSame(closeFailure, cleanup.getCause());
            assertEquals(0, reports.get());
        } finally {
            releaseLateStartup.countDown();
            closeFinished.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            joinThread(retirementThread, "constructor-owned retirement");
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void successfulCommitReportsPreCommitLateErrorWithDetachedThreadSnapshotExactlyOnce() throws Exception {
        CountDownLatch commitReady = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch backoffEntered = new CountDownLatch(1);
        CountDownLatch releaseBackoff = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        AtomicReference<Thread> reportedThread = new AtomicReference<>();
        CountDownLatch reportPublished = new CountDownLatch(1);
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        AtomicInteger reports = new AtomicInteger();
        AssertionError lateError = new AssertionError("pre-commit late startup error");
        ExecutorService constructorExecutor = Executors.newSingleThreadExecutor();
        WorkerPoolController<TestWorker> pool = null;
        try {
            Future<WorkerPoolController<TestWorker>> construction = constructorExecutor.submit(() -> controller(
                    () -> {
                        startupThread.set(Thread.currentThread());
                        startupEntered.countDown();
                        awaitIgnoringInterrupt(releaseStartup);
                        throw lateError;
                    },
                    worker -> {},
                    new Options(1, 0, 1, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, true),
                    task -> {
                        Thread thread = Threading.start("test-replenish-", task);
                        ownerThread.set(thread);
                    },
                    task -> Threading.start("test-retire-", task),
                    (thread, failure) -> {
                        reportedThread.compareAndSet(null, thread);
                        reportedFailure.compareAndSet(null, failure);
                        reports.incrementAndGet();
                        reportPublished.countDown();
                    },
                    System::nanoTime,
                    backoff -> {
                        if (backoff.isZero()) {
                            return true;
                        }
                        backoffEntered.countDown();
                        awaitIgnoringInterrupt(releaseBackoff);
                        return false;
                    },
                    new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {
                        commitReady.countDown();
                        awaitIgnoringInterrupt(allowCommit);
                    })));

            assertTrue(commitReady.await(1, TimeUnit.SECONDS));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            assertTrue(backoffEntered.await(1, TimeUnit.SECONDS));
            releaseStartup.countDown();
            joinThread(startupThread, "pre-commit late startup");
            assertEquals(0, reports.get());

            allowCommit.countDown();
            pool = construction.get(1, TimeUnit.SECONDS);

            assertTrue(reportPublished.await(1, TimeUnit.SECONDS));
            assertEquals(1, reports.get());
            assertFalse(startupThread.get() == reportedThread.get());
            assertEquals(startupThread.get().getName(), reportedThread.get().getName());
            assertSame(
                    startupThread.get().getContextClassLoader(),
                    reportedThread.get().getContextClassLoader());
            assertEquals(startupThread.get().getPriority(), reportedThread.get().getPriority());
            assertEquals(startupThread.get().isDaemon(), reportedThread.get().isDaemon());
            assertSame(lateError, reportedFailure.get());
        } finally {
            releaseStartup.countDown();
            allowCommit.countDown();
            releaseBackoff.countDown();
            if (pool != null) {
                pool.closeAsync();
                pool.closeAsync().get(1, TimeUnit.SECONDS);
            }
            joinThread(startupThread, "pre-commit late startup");
            joinThread(ownerThread, "pre-commit replenishment owner");
            constructorExecutor.shutdownNow();
            assertTrue(constructorExecutor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedConstructionRetainsTerminalCleanupUntilLateWorkerClosesExactlyOnce() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                            new Options(1, 1, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false))));

            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            PoolFailure primary = construction.get(1, TimeUnit.SECONDS);
            assertEquals(FailureKind.STARTUP_FAILED, primary.kind);
            releaseStartup.countDown();

            assertTrue(workerClosed.await(1, TimeUnit.SECONDS));
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            assertFalse(closeThread.get() == startupThread.get(), "late worker closed on startup caller thread");
            assertTrue(closeThread.get().getName().startsWith("procwright-terminal-retirement-"));
            assertEquals(1, closeCalls.get());
        } finally {
            releaseStartup.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            PoolRetirementDispatcher.whenSharedIdle().get(2, TimeUnit.SECONDS);
            constructorExecutor.shutdownNow();
            assertTrue(constructorExecutor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void closeBetweenReservationAndOpenReturnsTypedClosedFailure() throws Exception {
        CountDownLatch handoffEntered = new CountDownLatch(1);
        CountDownLatch releaseHandoff = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                task -> Threading.start("test-retire-", task),
                (thread, failure) -> {},
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(
                        () -> {
                            handoffEntered.countDown();
                            awaitIgnoringInterrupt(releaseHandoff);
                        },
                        failure -> {},
                        failure -> {},
                        () -> {}));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((candidate, deadline) -> HEALTHY));
            assertTrue(handoffEntered.await(1, TimeUnit.SECONDS));

            pool.closeAsync();
            releaseHandoff.countDown();

            ExecutionException thrown = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.CLOSED, ((PoolFailure) thrown.getCause()).kind);
            assertEquals(0, factoryCalls.get());
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            releaseHandoff.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
        }
    }

    @Test
    void closeCancelsStartupQueuedForGlobalPermitWithoutCallingFactory() throws Exception {
        List<BoundedTaskRunner.Permit> occupied = new ArrayList<>();
        int capacity = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
        long permitDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskRunner.WORKER_STARTUPS.acquire(permitDeadline));
        }
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(pool.awaitMetrics(metrics -> metrics.starting() == 1, Duration.ofSeconds(1)));

            pool.closeAsync();
            assertPartition(pool, 0, 0, 0, 0, 0);
            occupied.remove(occupied.size() - 1).close();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> acquire.get(1, TimeUnit.SECONDS));
            assertEquals(FailureKind.CLOSED, ((PoolFailure) failure.getCause()).kind);
            assertEquals(0, factoryCalls.get());
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            occupied.forEach(BoundedTaskRunner.Permit::close);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
        }
    }

    @Test
    void queuedStartupPermitTimeoutAfterCloseReturnsTypedClosedFailure() throws Exception {
        List<BoundedTaskRunner.Permit> occupied = new ArrayList<>();
        int capacity = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
        long permitDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        for (int index = 0; index < capacity; index++) {
            occupied.add(BoundedTaskRunner.WORKER_STARTUPS.acquire(permitDeadline));
        }
        AtomicInteger factoryCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = controller(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new TestWorker(1);
                },
                worker -> {},
                new Options(1, 0, 0, Duration.ofMillis(60), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquire = executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(pool.awaitMetrics(metrics -> metrics.starting() == 1, Duration.ofSeconds(1)));
            pool.closeAsync();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> acquire.get(500, TimeUnit.MILLISECONDS));

            assertEquals(FailureKind.CLOSED, ((PoolFailure) failure.getCause()).kind);
            assertEquals(0, factoryCalls.get());
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            occupied.forEach(BoundedTaskRunner.Permit::close);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.closeAsync();
        }
    }

    @Test
    void failedWarmupAttachesTypedCleanupFailureToPrimary() {
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
                        new Options(2, 2, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false)));

        assertEquals(FailureKind.STARTUP_FAILED, thrown.kind);
        assertSame(startupFailure, thrown.getCause());
        assertEquals(1, thrown.getSuppressed().length);
        PoolFailure cleanup = (PoolFailure) thrown.getSuppressed()[0];
        assertEquals(FailureKind.RETIREMENT_FAILED, cleanup.kind);
        assertSame(closeFailure, cleanup.getCause());
    }

    @Test
    void lateWorkerCloseFailureAfterFailedWarmupIsReportedExactlyOnce() throws Exception {
        CountDownLatch lateStartupEntered = new CountDownLatch(1);
        CountDownLatch releaseLateStartup = new CountDownLatch(1);
        AtomicReference<Thread> lateStartupThread = new AtomicReference<>();
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        CountDownLatch reportPublished = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        IllegalStateException lateCloseFailure = new IllegalStateException("late worker close failed");
        AtomicReference<Thread> retirementThread = new AtomicReference<>();

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
                            if (worker.id == 2) {
                                throw lateCloseFailure;
                            }
                        },
                        new Options(2, 2, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false),
                        task -> Threading.start("test-replenish-", task),
                        task -> {
                            Thread thread = Threading.start("test-retire-", task);
                            retirementThread.set(thread);
                        },
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
            joinThread(retirementThread, "late warmup retirement");
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void warmupTimeoutUsesStartupFailureTaxonomy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                            new Options(1, 1, 0, Duration.ofMillis(30), Integer.MAX_VALUE, Duration.ZERO, false)));

            assertEquals(FailureKind.STARTUP_FAILED, failure.kind);
        } finally {
            release.countDown();
            joinThread(startupThread, "warmup startup");
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void poolObservesFailedFirstPhysicalCloseAfterWorkerSelfCloses() throws Exception {
        IllegalStateException firstCloseFailure = new IllegalStateException("first physical close failed");
        CloseAwareWorker session = new CloseAwareWorker();
        WorkerPoolController<CloseAwareWorker> pool = new WorkerPoolController<>(
                () -> session,
                (worker, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        worker, worker.terminal, worker.physicalCleanup, admission),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "close-aware worker",
                "test-close-aware-");
        WorkerPoolController.Worker<CloseAwareWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);
        session.failPhysicalClose(firstCloseFailure);

        pool.release(worker, false, PooledWorkerRetireReason.WORKER_FAILED);

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
    void warmupInterruptionPreservesInterruptionAndUsesTypedFailure() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                        new Options(1, 1, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false));
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
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
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
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));

        AssertionError thrown = assertThrows(AssertionError.class, () -> pool.acquire((worker, deadline) -> HEALTHY));

        assertEquals(startupFailure, thrown);
        assertEquals(0, pool.metrics().size());
        assertEquals(0, pool.metrics().starting());
        assertEquals(1, pool.metrics().failedStartups());
        pool.closeAsync();
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
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkerPoolController.Worker<TestWorker>> acquiring =
                    executor.submit(() -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 1, 0);

            releaseStartup.countDown();
            WorkerPoolController.Worker<TestWorker> worker = acquiring.get(1, TimeUnit.SECONDS);
            assertPartition(pool, 1, 0, 1, 0, 0);

            pool.release(worker, true, null);
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
    void lateStartupFailureReleasesSlotAfterAcquireTimeout() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                new Options(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false));

        try {
            assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            assertPartition(pool, 1, 0, 0, 1, 0);

            releaseStartup.countDown();
            assertTrue(pool.awaitMetrics(metrics -> metrics.size() == 0, Duration.ofSeconds(1)));
            assertEquals(1, pool.metrics().failedStartups());
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            releaseStartup.countDown();
            joinThread(startupThread, "late failed startup");
            pool.closeAsync();
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void abandonedStartupReleasesPermitBeforeLateWorkerRetirementCompletes() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                new Options(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false));

        try {
            assertThrows(PoolFailure.class, () -> pool.acquire((worker, deadline) -> HEALTHY));
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore - 1, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());

            releaseStartup.countDown();
            assertTrue(retirementEntered.await(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
            assertPartition(pool, 1, 0, 0, 0, 1);

            releaseRetirement.countDown();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_TIMEOUT));
        } finally {
            releaseStartup.countDown();
            releaseRetirement.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            pool.closeAsync();
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void interruptedStartupUsesExactReasonAndRestoresGlobalPermit() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                new Options(1, 0, 0, Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, false));
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
            assertTrue(pool.awaitMetrics(metrics -> metrics.size() == 0, Duration.ofSeconds(1)));
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.STARTUP_INTERRUPTED));
            pool.closeAsync();
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        } finally {
            releaseStartup.countDown();
            caller.interrupt();
            caller.join(1_000);
            joinThread(startupThread, "interrupted late startup");
            pool.closeAsync();
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
        }
    }

    @Test
    void abandonedStartupReportsLateErrorExactlyOnce() throws Exception {
        int permitsBefore = BoundedTaskRunner.WORKER_STARTUPS.availablePermits();
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
                    new Options(1, 0, 0, Duration.ofMillis(40), Integer.MAX_VALUE, Duration.ZERO, false),
                    task -> Threading.start("test-replenish-", task),
                    task -> Threading.start("test-retire-", task),
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
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
            assertEquals(1, reports.get());
        } finally {
            releaseStartup.countDown();
            assertTrue(startupFinished.await(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, BoundedTaskRunner.WORKER_STARTUPS.availablePermits());
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
                new Options(1, 1, 0, Duration.ofMillis(50), Integer.MAX_VALUE, Duration.ofNanos(1), false));
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
        WorkerPoolController.Worker<TestWorker> worker = pool.acquire((candidate, deadline) -> HEALTHY);

        try {
            Future<?> releaseCall = releaseExecutor.submit(() -> {
                try {
                    pool.release(worker, false, PooledWorkerRetireReason.WORKER_FAILED);
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
        AtomicReference<WorkerPoolController.MetricsSnapshot> callbackMetrics = new AtomicReference<>();
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
                task -> Threading.start("test-retire-", task),
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

    @Test
    void requestDurationIncludesEncodingAndExcludesAcquireWaitDeterministically() {
        AtomicReference<Long> now = new AtomicReference<>(100L);
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                new Options(1, 0, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                task -> Threading.start("test-replenish-", task),
                task -> Threading.start("test-retire-", task),
                (thread, failure) -> {},
                now::get,
                null);

        WorkerPoolController<TestWorker>.RequestObservation observation = pool.observeRequest();
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
                task -> Threading.start("test-metrics-retire-", task),
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
            Future<WorkerPoolController.Worker<TestWorker>> acquire =
                    executor.submit(() -> pool.acquire((worker, deadline) -> {
                        healthEntered.countDown();
                        awaitIgnoringInterrupt(releaseHealth);
                        return HEALTHY;
                    }));
            assertTrue(healthEntered.await(1, TimeUnit.SECONDS));
            assertTrue(waiterObservedLease.await(1, TimeUnit.SECONDS));

            now.set(175L);
            releaseHealth.countDown();

            WorkerPoolController.Worker<TestWorker> worker = acquire.get(1, TimeUnit.SECONDS);
            assertTrue(metricsWait.get(1, TimeUnit.SECONDS));
            assertEquals(75L, pool.metrics().totalAcquireWaitNanos());
            pool.release(worker, true, null);
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

    private static void assertPartition(
            WorkerPoolController<?> pool, int size, int idle, int leased, int starting, int retiring) {
        pool.verifyPartitionForTest();
        WorkerPoolController.MetricsSnapshot metrics = pool.metrics();
        assertEquals(size, metrics.size());
        assertEquals(idle, metrics.idle());
        assertEquals(leased, metrics.leased());
        assertEquals(starting, metrics.starting());
        assertEquals(retiring, metrics.retiring());
        assertEquals(size, idle + leased + starting + retiring);
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

    private static void awaitAvailableAdmissions(PoolRetirementDispatcher.AdmissionPool admissions, int expected)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (admissions.availablePermits() != expected && System.nanoTime() < deadlineNanos) {
            Thread.sleep(1);
        }
        assertEquals(expected, admissions.availablePermits());
    }

    private static WorkerPoolController.RetirementAdmissionProvider immediateAdmissions(
            PoolRetirementDispatcher.AdmissionPool admissions) {
        return deadlineNanos -> {
            PoolRetirementDispatcher.Admission admission = admissions.tryAcquire();
            if (admission == null) {
                throw new java.util.concurrent.TimeoutException("test lifecycle capacity exhausted");
            }
            return admission;
        };
    }

    @SuppressWarnings("unchecked")
    private static <S> ArrayDeque<WorkerPoolController.Worker<S>> workerQueue(
            WorkerPoolController<S> pool, String fieldName) throws ReflectiveOperationException {
        Field field = WorkerPoolController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ArrayDeque<WorkerPoolController.Worker<S>>) field.get(pool);
    }

    @SuppressWarnings("unchecked")
    private static <S> Set<WorkerPoolController.Worker<S>> workerSet(WorkerPoolController<S> pool, String fieldName)
            throws ReflectiveOperationException {
        Field field = WorkerPoolController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Set<WorkerPoolController.Worker<S>>) field.get(pool);
    }

    private static <S> WorkerPoolController.Worker<S> foreignWorkerMatchingState(
            WorkerPoolController.Worker<S> registered) throws ReflectiveOperationException {
        Field state = WorkerPoolController.Worker.class.getDeclaredField("state");
        state.setAccessible(true);
        WorkerPoolController.Worker<S> foreign = new WorkerPoolController.Worker<>();
        state.set(foreign, state.get(registered));
        return foreign;
    }

    private static void assertRetirementDispatchFailure(Throwable dispatchFailure) throws Exception {
        PoolRetirementDispatcher.AdmissionPool admissions = new PoolRetirementDispatcher.AdmissionPool(2);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        WorkerPoolController<TestWorker> pool = new WorkerPoolController<>(
                () -> new TestWorker(1),
                closeAction(worker -> closeCalls.incrementAndGet()),
                new Options(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO, false),
                Failures.INSTANCE,
                "dispatch-failure worker",
                "test-dispatch-failure-",
                task -> Threading.start("test-dispatch-failure-replenish-", task),
                (admission, task) -> {
                    dispatches.incrementAndGet();
                    return throwUnchecked(dispatchFailure);
                },
                (thread, failure) -> {},
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {}),
                immediateAdmissions(admissions));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

        assertSame(dispatchFailure, observed.getCause());
        assertEquals(1, dispatches.get(), "injected dispatcher must be attempted exactly once");
        assertEquals(1, closeCalls.get());
        assertEquals(1, pool.metrics().retired());
        assertPartition(pool, 0, 0, 0, 0, 0);
        awaitAvailableAdmissions(admissions, 2);
        ExecutionException repeated =
                assertThrows(ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));
        assertSame(dispatchFailure, repeated.getCause());
        assertEquals(2, admissions.availablePermits());
    }

    private static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options) {
        return new WorkerPoolController<>(
                factory, closeAction(closer), options, Failures.INSTANCE, "test worker", "test-");
    }

    private static WorkerPoolController<TestWorker> controllerWithSeparateAdmissions(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            PoolRetirementDispatcher.AdmissionPool completionAdmissions,
            PoolRetirementDispatcher.AdmissionPool workerAdmissions) {
        return new WorkerPoolController<>(
                factory,
                closeAction(closer),
                options,
                Failures.INSTANCE,
                "separate-admission worker",
                "test-separate-admission-",
                Runnable::run,
                (admission, task) -> PoolRetirementDispatcher.executeWorkerClose(admission, task, Threading::start),
                (thread, failure) -> {},
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {}),
                immediateAdmissions(completionAdmissions),
                immediateAdmissions(workerAdmissions));
    }

    private static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            WorkerPoolController.TestHooks testHooks) {
        return controller(
                factory,
                closer,
                options,
                task -> Threading.start("test-replenish-", task),
                task -> Threading.start("test-retire-", task),
                (thread, failure) -> {},
                System::nanoTime,
                null,
                testHooks);
    }

    private static WorkerPoolController.TestHooks startupTerminalHooks(
            Runnable afterFactoryTerminalSignal, Runnable afterStartupTimeoutSignal) {
        return new WorkerPoolController.TestHooks(
                () -> {},
                failure -> {},
                failure -> {},
                () -> {},
                afterFactoryTerminalSignal,
                afterStartupTimeoutSignal,
                new WorkerPoolController.FaultInjector());
    }

    private static WorkerPoolController.TestHooks faultInjectionHooks(
            WorkerPoolController.FaultInjector faultInjector) {
        return new WorkerPoolController.TestHooks(
                () -> {}, failure -> {}, failure -> {}, () -> {}, () -> {}, () -> {}, faultInjector);
    }

    private static WorkerPoolController<TestWorker> inlineController(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options) {
        return inlineController(
                factory,
                closer,
                options,
                new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {}));
    }

    private static WorkerPoolController<TestWorker> inlineController(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            WorkerPoolController.TestHooks testHooks) {
        return controller(
                factory,
                closer,
                options,
                Runnable::run,
                Runnable::run,
                (thread, failure) -> {},
                System::nanoTime,
                null,
                testHooks);
    }

    private static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            java.util.function.Consumer<Runnable> replenishmentStarter,
            java.util.function.Consumer<Runnable> retirementStarter,
            WorkerPoolController.LateFailureReporter lateFailureReporter,
            WorkerPoolController.NanoClock clock,
            WorkerPoolController.BackoffWaiter backoffWaiter) {
        return new WorkerPoolController<>(
                factory,
                closeAction(closer),
                options,
                Failures.INSTANCE,
                "test worker",
                "test-",
                replenishmentStarter,
                retirementStarter,
                lateFailureReporter,
                clock,
                backoffWaiter);
    }

    private static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            java.util.function.Consumer<Runnable> replenishmentStarter,
            java.util.function.Consumer<Runnable> retirementStarter,
            WorkerPoolController.LateFailureReporter lateFailureReporter,
            WorkerPoolController.NanoClock clock,
            WorkerPoolController.BackoffWaiter backoffWaiter,
            WorkerPoolController.TestHooks testHooks) {
        return new WorkerPoolController<>(
                factory,
                closeAction(closer),
                options,
                Failures.INSTANCE,
                "test worker",
                "test-",
                replenishmentStarter,
                retirementStarter,
                lateFailureReporter,
                clock,
                backoffWaiter,
                testHooks);
    }

    private static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            java.util.function.Consumer<Runnable> replenishmentStarter) {
        return new WorkerPoolController<>(
                factory,
                closeAction(closer),
                options,
                Failures.INSTANCE,
                "test worker",
                "test-",
                replenishmentStarter,
                task -> Threading.start("test-retire-", task));
    }

    private static WorkerPoolController.WorkerCloseAction<TestWorker> closeAction(
            java.util.function.Consumer<TestWorker> closer) {
        return (worker, admission) -> {
            WorkerPoolController.CloseOutcome outcome;
            try {
                closer.accept(worker);
                outcome = WorkerPoolController.CloseOutcome.success();
            } catch (Throwable failure) {
                outcome = WorkerPoolController.CloseOutcome.failure(failure);
            }
            WorkerPoolController.CloseOutcome completed = outcome;
            return () -> CompletableFuture.completedFuture(completed);
        };
    }

    private static void joinThread(AtomicReference<Thread> reference, String operation) throws InterruptedException {
        Thread thread = reference.get();
        if (thread == null) {
            return;
        }
        thread.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(thread.isAlive(), operation + " thread did not stop");
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("unsupported test failure", failure);
    }

    private record TestWorker(int id) {}

    private static CompletableFuture<Void> publicCloseView(WorkerPoolController<?> pool) {
        return PoolCloseSupport.asyncView(pool.closeAsync(), PublicCloseFailures.INSTANCE);
    }

    private enum PublicCloseFailures implements PoolCloseSupport.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException drainTimeout(Duration timeout) {
            return new IllegalStateException("unexpected drain timeout: " + timeout);
        }

        @Override
        public RuntimeException interrupted(InterruptedException cause) {
            return new IllegalStateException("unexpected close interruption", cause);
        }

        @Override
        public RuntimeException workerFailed(Throwable cause) {
            return new IllegalStateException("unexpected worker close failure", cause);
        }
    }

    private static final class CloseAwareWorker implements AutoCloseable {

        private final CompletableFuture<Void> terminal = CompletableFuture.completedFuture(null);
        private final CompletableFuture<Void> physicalCleanup = new CompletableFuture<>();
        private final CountDownLatch physicalCloseFinished = new CountDownLatch(1);
        private final AtomicBoolean physicallyClosed = new AtomicBoolean();
        private final AtomicInteger physicalCloseCalls = new AtomicInteger();

        private void failPhysicalClose(Throwable failure) {
            if (physicallyClosed.compareAndSet(false, true)) {
                physicalCloseCalls.incrementAndGet();
                physicalCleanup.completeExceptionally(failure);
                physicalCloseFinished.countDown();
            }
        }

        @Override
        public void close() {
            if (physicallyClosed.compareAndSet(false, true)) {
                physicalCloseCalls.incrementAndGet();
                physicalCleanup.complete(null);
                physicalCloseFinished.countDown();
            }
        }
    }

    private static final class ExitCallbackWorker implements AutoCloseable {

        private final CompletableFuture<Void> exit = new CompletableFuture<>();
        private final CompletableFuture<Void> physicalCleanup = CompletableFuture.completedFuture(null);
        private final AtomicInteger closeCalls = new AtomicInteger();

        private CompletableFuture<Void> onExit() {
            return exit;
        }

        private CompletableFuture<Void> physicalCleanup() {
            return physicalCleanup;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            exit.complete(null);
        }
    }

    private static final class ReusableRetirementDispatcher implements AutoCloseable {

        private final AtomicReference<Thread> owner = new AtomicReference<>();
        private final AtomicReference<String> initialName = new AtomicReference<>();
        private final AtomicReference<ClassLoader> initialClassLoader = new AtomicReference<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = Threading.unstarted("test-reused-retirement-", task);
            owner.set(thread);
            initialName.set(thread.getName());
            initialClassLoader.set(thread.getContextClassLoader());
            return thread;
        });

        private PoolRetirementDispatcher.Ownership dispatch(
                PoolRetirementDispatcher.Admission admission, Runnable task) {
            admission.claimDispatch();
            CompletableFuture<Thread> started = new CompletableFuture<>();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            try {
                executor.execute(() -> {
                    started.complete(Thread.currentThread());
                    Throwable failure = null;
                    try {
                        task.run();
                    } catch (Throwable taskFailure) {
                        failure = taskFailure;
                    } finally {
                        admission.releaseDispatch();
                    }
                    if (failure == null) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException | Error failure) {
                admission.releaseDispatch();
                throw failure;
            }
            return new PoolRetirementDispatcher.Ownership(started, completion);
        }

        private Thread owner() {
            return Objects.requireNonNull(owner.get(), "retirement owner");
        }

        private String initialName() {
            return Objects.requireNonNull(initialName.get(), "initial retirement owner name");
        }

        private ClassLoader initialClassLoader() {
            return Objects.requireNonNull(initialClassLoader.get(), "initial retirement owner class loader");
        }

        @Override
        public void close() {
            executor.shutdownNow();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                        return;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static final class PausingRetirementDispatcher {

        private final AtomicInteger dispatches = new AtomicInteger();
        private final CountDownLatch firstTaskReturned = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCompletion = new CountDownLatch(1);

        private PoolRetirementDispatcher.Ownership dispatch(
                PoolRetirementDispatcher.Admission admission, Runnable task) {
            admission.claimDispatch();
            CompletableFuture<Thread> started = new CompletableFuture<>();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            int dispatch = dispatches.incrementAndGet();
            try {
                Threading.start("test-pausing-retirement-", () -> {
                    started.complete(Thread.currentThread());
                    Throwable failure = null;
                    try {
                        task.run();
                    } catch (Throwable taskFailure) {
                        failure = taskFailure;
                    }
                    if (dispatch == 1) {
                        firstTaskReturned.countDown();
                        awaitIgnoringInterrupt(releaseFirstCompletion);
                    }
                    admission.releaseDispatch();
                    if (failure == null) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException | Error failure) {
                admission.releaseDispatch();
                throw failure;
            }
            return new PoolRetirementDispatcher.Ownership(started, completion);
        }

        private boolean awaitFirstTaskReturned() throws InterruptedException {
            return firstTaskReturned.await(1, TimeUnit.SECONDS);
        }

        private void releaseFirstCompletion() {
            releaseFirstCompletion.countDown();
        }
    }

    private static final class HeldTerminalRetirementDispatcher {

        private final AtomicReference<Runnable> heldTask = new AtomicReference<>();

        private PoolRetirementDispatcher.Ownership dispatch(
                PoolRetirementDispatcher.Admission admission, Runnable task) {
            admission.claimDispatch();
            CompletableFuture<Thread> started = new CompletableFuture<>();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Runnable observed = () -> {
                started.complete(Thread.currentThread());
                Throwable failure = null;
                try {
                    task.run();
                } catch (Throwable taskFailure) {
                    failure = taskFailure;
                } finally {
                    admission.releaseDispatch();
                }
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                }
            };
            if (!heldTask.compareAndSet(null, observed)) {
                admission.releaseDispatch();
                throw new IllegalStateException("retirement dispatcher already holds a task");
            }
            return new PoolRetirementDispatcher.Ownership(started, completion);
        }

        private void runNext() {
            Runnable task = heldTask.getAndSet(null);
            if (task == null) {
                throw new IllegalStateException("retirement dispatcher holds no task");
            }
            task.run();
        }
    }

    private record Options(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment)
            implements WorkerPoolController.PoolOptions {}

    private enum FailureKind {
        CLOSED,
        ACQUIRE_TIMEOUT,
        INTERRUPTED,
        STARTUP_FAILED,
        RETIREMENT_FAILED
    }

    @SuppressWarnings("serial")
    private static final class PoolFailure extends RuntimeException {

        private final FailureKind kind;

        private PoolFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    private enum Failures implements WorkerPoolController.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException closed(String message) {
            return new PoolFailure(FailureKind.CLOSED, message, null);
        }

        @Override
        public RuntimeException acquireTimeout(String message) {
            return new PoolFailure(FailureKind.ACQUIRE_TIMEOUT, message, null);
        }

        @Override
        public RuntimeException acquireInterrupted(String message, InterruptedException cause) {
            return new PoolFailure(FailureKind.INTERRUPTED, message, cause);
        }

        @Override
        public RuntimeException startupFailed(String message, Throwable cause) {
            return new PoolFailure(FailureKind.STARTUP_FAILED, message, cause);
        }

        @Override
        public RuntimeException retirementFailed(String message, Throwable cause) {
            return new PoolFailure(FailureKind.RETIREMENT_FAILED, message, cause);
        }
    }
}

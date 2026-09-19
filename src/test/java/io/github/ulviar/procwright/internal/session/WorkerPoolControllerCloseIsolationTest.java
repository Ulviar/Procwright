/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
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

final class WorkerPoolControllerCloseIsolationTest extends WorkerPoolControllerTestSupport {

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
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO));
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
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO));
        WorkerPoolController<TestWorker> second = controller(
                () -> new TestWorker(2),
                worker -> {},
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO));
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
    void eightBlockingWorkerExitCallbacksDoNotStarveNinthPoolClose() throws Exception {
        int blockingPools = 8;
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
                pools.add(WorkerPoolController.fromSettings(
                        () -> worker,
                        session -> WorkerCloseSupport.closeOutcome(session, session.onExit()),
                        settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO),
                        Failures.INSTANCE,
                        "exit-callback worker",
                        "test-exit-callback-",
                        new WorkerPoolController.Dependencies(
                                threadedScheduler("test-exit-callback-replenish-"), report -> {}, System::nanoTime)));
            }

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
            for (ExitCallbackWorker worker : workers) {
                assertEquals(1, worker.closeCalls.get());
            }
        }
    }

    @Test
    void completedCloseFailureDoesNotPoisonLaterPoolConstruction() throws Exception {
        IllegalStateException closeFailure = new IllegalStateException("physical close failed");
        WorkerPoolSettings<?> options = settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO);
        WorkerPoolController<TestWorker> failed = controller(
                () -> new TestWorker(1),
                worker -> {
                    throw closeFailure;
                },
                options);

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> failed.closeAsync().get(1, TimeUnit.SECONDS));

        assertSame(closeFailure, observed.getCause());
        assertPartition(failed, 0, 0, 0, 0, 0);
        assertEquals(1, failed.metrics().retired());
        assertEquals(1, failed.metrics().failedWorkerCloses());

        WorkerPoolController<TestWorker> recovered = controller(() -> new TestWorker(2), worker -> {}, options);
        assertPartition(recovered, 1, 1, 0, 0, 0);
        recovered.closeAsync().get(1, TimeUnit.SECONDS);
        assertPartition(recovered, 0, 0, 0, 0, 0);
    }

    @Test
    void drainedCallbackAllowsAnotherThreadToAcquirePoolLock() throws Exception {
        WorkerPoolController<TestWorker> pool = controller(
                () -> new TestWorker(1),
                worker -> {},
                settings(1, 1, 0, Duration.ofSeconds(1), Integer.MAX_VALUE, Duration.ZERO));
        AtomicReference<PooledSessionMetrics> callbackMetrics = new AtomicReference<>();
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

    private static final class ExitCallbackWorker implements AutoCloseable {

        final CompletableFuture<Void> exit = new CompletableFuture<>();
        final CompletableFuture<Void> physicalCleanup = CompletableFuture.completedFuture(null);
        final AtomicInteger closeCalls = new AtomicInteger();

        CompletableFuture<Void> onExit() {
            return exit;
        }

        CompletableFuture<Void> physicalCleanup() {
            return physicalCleanup;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            exit.complete(null);
        }
    }
}

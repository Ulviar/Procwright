/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.BlockingReadFailingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TestProcess;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TrackingInputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TrackingOutputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.assertNoDispatcherLeak;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.openLineWorker;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.openProtocolWorker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledWorkerRetirementCoordinationTest {

    @Test
    void linePoolRetiresWorkerWithoutWaitingForPhysicalOutputClose() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TestProcess process = new TestProcess(new TrackingOutputStream(), stdout, new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        DefaultLineSession worker = openLineWorker(process, dispatcher);
        assertTrue(stdout.awaitReadStarted(), "line stdout pump did not enter the blocked read");
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> worker,
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults().withWarmupSize(1));
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertTrue(stdout.awaitCloseInvoked(), "line stdout physical close was not dispatched");
            close.get(1, TimeUnit.SECONDS);
            assertTrue(worker.onExit().isDone());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().retiring());
            assertEquals(0, pool.metrics().size());

            stdout.releaseRead();
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void linePoolStartsEveryWorkerTerminationBeforeAwaitingSlowClose() throws Exception {
        CountDownLatch releaseFirstDestroy = new CountDownLatch(1);
        BlockingDestroyProcess firstProcess = new BlockingDestroyProcess(
                new TrackingOutputStream(), new TrackingInputStream(), new TrackingInputStream(), releaseFirstDestroy);
        TestProcess secondProcess =
                new TestProcess(new TrackingOutputStream(), new TrackingInputStream(), new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 4);
        List<LineSession> workers = List.of(
                openLineWorker(
                        firstProcess,
                        dispatcher,
                        ShutdownPolicy.interruptThenKill(Duration.ofSeconds(30), Duration.ZERO)),
                openLineWorker(secondProcess, dispatcher));
        AtomicInteger workerIndex = new AtomicInteger();
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> workers.get(workerIndex.getAndIncrement()),
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults()
                        .withMaxSize(2)
                        .withWarmupSize(2)
                        .withCloseTimeout(Duration.ofSeconds(1)));
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertTrue(firstProcess.awaitDestroyInvoked(), "first worker did not start termination");
            assertTrue(
                    secondProcess.awaitDestroyInvoked(), "second worker termination was serialized behind the first");
            assertFalse(close.isDone(), "blocked first worker must remain RETIRING");

            releaseFirstDestroy.countDown();
            close.get(1, TimeUnit.SECONDS);
            assertEquals(2, pool.metrics().retired());
            assertEquals(0, pool.metrics().retiring());
            assertNoDispatcherLeak(dispatcher);
        } finally {
            releaseFirstDestroy.countDown();
            firstProcess.complete(143);
            secondProcess.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void linePoolDoesNotPublishRetirementBeforeDelayedTerminalObservation() throws Exception {
        CompletableFuture<Void> allowTerminalObservation = new CompletableFuture<>();
        CountDownLatch closeStarted = new CountDownLatch(1);
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stdout = new TrackingInputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2);
        DefaultLineSession worker = openLineWorker(process, closeDispatcher);
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> worker,
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults().withWarmupSize(1),
                System::nanoTime,
                session -> WorkerCloseSupport.closeOutcome(
                        () -> {
                            closeStarted.countDown();
                            session.close();
                        },
                        allowTerminalObservation.thenCompose(ignored -> session.onExit())));
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            worker.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            assertFalse(close.isDone());
            assertEquals(0, pool.metrics().retired());
            assertEquals(1, pool.metrics().retiring());

            allowTerminalObservation.complete(null);
            close.get(1, TimeUnit.SECONDS);
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().retiring());
            assertNoDispatcherLeak(closeDispatcher);
        } finally {
            allowTerminalObservation.complete(null);
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void delayedLineTerminalFailureSettlesRetirementAndClosesWorkerExactlyOnce() throws Exception {
        AssertionError terminalFailure = new AssertionError("delayed line terminal failed fatally");
        CompletableFuture<Void> delayedTerminal = new CompletableFuture<>();
        CompletableFuture<Void> closeTask = new CompletableFuture<>();
        AtomicInteger closeCalls = new AtomicInteger();
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stdout = new TrackingInputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2);
        DefaultLineSession worker = openLineWorker(process, closeDispatcher);
        WorkerPoolController<DefaultLineSession> pool = WorkerPoolController.fromSettings(
                () -> worker,
                session -> WorkerCloseSupport.closeOutcome(
                        () -> {
                            closeCalls.incrementAndGet();
                            try {
                                session.close();
                                closeTask.complete(null);
                            } catch (Throwable failure) {
                                closeTask.completeExceptionally(failure);
                                throw failure;
                            }
                        },
                        delayedTerminal),
                WorkerPoolSettings.defaults().withWarmupSize(1),
                TestPoolFailures.INSTANCE,
                "delayed-terminal line worker",
                "test-delayed-terminal-",
                new WorkerPoolController.Dependencies(
                        (task, delay) -> {
                            Threading.start("test-delayed-terminal-replenish-", task);
                            return PoolReplenisher.Cancellation.NONE;
                        },
                        report -> {},
                        System::nanoTime));
        try {
            CompletableFuture<Void> drain = pool.closeAsync();

            closeTask.get(1, TimeUnit.SECONDS);
            worker.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            assertFalse(drain.isDone(), "terminal observation must keep the worker retiring");
            assertEquals(1, pool.metrics().size());
            assertEquals(1, pool.metrics().retiring());
            assertEquals(0, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertEquals(1, closeCalls.get());

            delayedTerminal.completeExceptionally(terminalFailure);

            drain.get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.metrics().size());
            assertEquals(0, pool.metrics().retiring());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.CLOSED));

            for (int attempt = 0; attempt < 10; attempt++) {
                pool.closeAsync().get(1, TimeUnit.SECONDS);
            }
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertEquals(1, closeCalls.get(), "repeated close views must share one worker close");
            assertNoDispatcherLeak(closeDispatcher);
        } finally {
            delayedTerminal.completeExceptionally(terminalFailure);
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void protocolPoolDoesNotPublishRetirementBeforeDelayedTerminalObservation() throws Exception {
        CompletableFuture<Void> allowTerminalObservation = new CompletableFuture<>();
        CountDownLatch closeStarted = new CountDownLatch(1);
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stdout = new TrackingInputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2);
        DefaultProtocolSession<String, String> worker = openProtocolWorker(process, closeDispatcher);
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> worker,
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults().withWarmupSize(1),
                session -> WorkerCloseSupport.closeOutcome(
                        () -> {
                            closeStarted.countDown();
                            session.close();
                        },
                        allowTerminalObservation.thenCompose(ignored -> session.onExit())));
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            worker.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            assertFalse(close.isDone());
            assertEquals(0, pool.metrics().retired());
            assertEquals(1, pool.metrics().retiring());

            allowTerminalObservation.complete(null);
            close.get(1, TimeUnit.SECONDS);
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().retiring());
            assertNoDispatcherLeak(closeDispatcher);
        } finally {
            allowTerminalObservation.complete(null);
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    private enum TestPoolFailures implements WorkerPoolController.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException closed(String message) {
            return new IllegalStateException(message);
        }

        @Override
        public RuntimeException acquireTimeout(String message) {
            return new IllegalStateException(message);
        }

        @Override
        public RuntimeException acquireInterrupted(String message, InterruptedException cause) {
            return new IllegalStateException(message, cause);
        }

        @Override
        public RuntimeException startupFailed(String message, Throwable cause) {
            return new IllegalStateException(message, cause);
        }

        @Override
        public RuntimeException retirementFailed(String message, Throwable cause) {
            return new IllegalStateException(message, cause);
        }
    }

    private static final class BlockingDestroyProcess extends TestProcess {

        private final CountDownLatch releaseDestroy;

        private BlockingDestroyProcess(
                OutputStream stdin, InputStream stdout, InputStream stderr, CountDownLatch releaseDestroy) {
            super(stdin, stdout, stderr);
            this.releaseDestroy = releaseDestroy;
        }

        @Override
        public void destroy() {
            recordDestroyInvoked();
            awaitUninterruptibly(releaseDestroy);
            complete(143);
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PooledWorkerPhysicalCleanupTest {

    private static final Duration CLOSE_TIMEOUT = Duration.ofMillis(40);

    @Test
    void linePoolStartsEveryWorkerTerminationBeforeAwaitingSlowClose() throws Exception {
        CountDownLatch releaseFirstDestroy = new CountDownLatch(1);
        BlockingDestroyProcess firstProcess = new BlockingDestroyProcess(
                new TrackingOutputStream(), new TrackingInputStream(), new TrackingInputStream(), releaseFirstDestroy);
        TestProcess secondProcess =
                new TestProcess(new TrackingOutputStream(), new TrackingInputStream(), new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 4, 6);
        List<LineSession> workers = List.of(
                new DefaultLineSession(
                        openSession(
                                firstProcess,
                                dispatcher,
                                ShutdownPolicy.interruptThenKill(Duration.ofSeconds(30), Duration.ZERO)),
                        LineSessionSettings.defaults()),
                new DefaultLineSession(openSession(secondProcess, dispatcher), LineSessionSettings.defaults()));
        AtomicInteger workerIndex = new AtomicInteger();
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> workers.get(workerIndex.getAndIncrement()),
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults(ignored -> {}, ignored -> true)
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
            assertNoDispatcherLeak(dispatcher, 6);
        } finally {
            releaseFirstDestroy.countDown();
            firstProcess.complete(143);
            secondProcess.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void linePoolQueuesMandatoryCloseBehindBusyOwnerAndReleasesSlot() throws Exception {
        PoolRetirementDispatcher dispatcher = saturatedNestedDispatcher("test-line-close-saturation-");
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        PoolRetirementDispatcher.Ownership blocker = dispatcher.dispatch(() -> {
            ownerStarted.countDown();
            awaitUninterruptibly(releaseOwner);
        });
        assertTrue(ownerStarted.await(1, TimeUnit.SECONDS));
        CountDownLatch releaseDestroy = new CountDownLatch(1);
        TrackingOutputStream stdin = new TrackingOutputStream();
        CloseUnblocksInputStream stdout = new CloseUnblocksInputStream();
        CloseUnblocksInputStream stderr = new CloseUnblocksInputStream();
        BlockingDestroyProcess process = new BlockingDestroyProcess(stdin, stdout, stderr, releaseDestroy);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, 3);
        DefaultLineSession worker =
                new DefaultLineSession(openSession(process, closeDispatcher), LineSessionSettings.defaults());
        assertTrue(stdout.awaitReadStarted());
        assertTrue(stderr.awaitReadStarted());
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> worker,
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1)
                        .withCloseTimeout(Duration.ofSeconds(1)),
                System::nanoTime,
                dispatcher::dispatch);
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertFalse(close.isDone());
            assertEquals(1, pool.metrics().retiring());
            assertEquals(0, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertFalse(process.destroyInvoked());

            releaseOwner.countDown();
            blocker.completion().get(1, TimeUnit.SECONDS);
            assertTrue(process.awaitDestroyInvoked(), "queued worker close did not initiate termination");

            releaseDestroy.countDown();
            close.get(1, TimeUnit.SECONDS);
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);

            assertFalse(process.isAlive());
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            worker.onExit().get(1, TimeUnit.SECONDS);
            worker.physicalOutputCleanup().get(1, TimeUnit.SECONDS);
            pool.slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.metrics().retiring());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().size());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertNoDispatcherLeak(closeDispatcher);
        } finally {
            releaseOwner.countDown();
            releaseDestroy.countDown();
            process.complete(143);
            dispatcher.whenIdle().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void protocolPoolQueuesMandatoryCloseBehindBusyOwnerAndReleasesSlot() throws Exception {
        PoolRetirementDispatcher dispatcher = saturatedNestedDispatcher("test-protocol-close-saturation-");
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        PoolRetirementDispatcher.Ownership blocker = dispatcher.dispatch(() -> {
            ownerStarted.countDown();
            awaitUninterruptibly(releaseOwner);
        });
        assertTrue(ownerStarted.await(1, TimeUnit.SECONDS));
        CountDownLatch releaseDestroy = new CountDownLatch(1);
        TrackingOutputStream stdin = new TrackingOutputStream();
        CloseUnblocksInputStream stdout = new CloseUnblocksInputStream();
        CloseUnblocksInputStream stderr = new CloseUnblocksInputStream();
        BlockingDestroyProcess process = new BlockingDestroyProcess(stdin, stdout, stderr, releaseDestroy);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, 3);
        DefaultProtocolSession<String, String> worker = new DefaultProtocolSession<>(
                openSession(process, closeDispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        assertTrue(stdout.awaitReadStarted());
        assertTrue(stderr.awaitReadStarted());
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> worker,
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1)
                        .withCloseTimeout(Duration.ofSeconds(1)),
                dispatcher::dispatch);
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertFalse(close.isDone());
            assertEquals(1, pool.metrics().retiring());
            assertEquals(0, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertFalse(process.destroyInvoked());

            releaseOwner.countDown();
            blocker.completion().get(1, TimeUnit.SECONDS);
            assertTrue(process.awaitDestroyInvoked(), "queued worker close did not initiate termination");

            releaseDestroy.countDown();
            close.get(1, TimeUnit.SECONDS);
            dispatcher.whenIdle().get(1, TimeUnit.SECONDS);

            assertFalse(process.isAlive());
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            worker.onExit().get(1, TimeUnit.SECONDS);
            worker.physicalOutputCleanup().get(1, TimeUnit.SECONDS);
            pool.slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.metrics().retiring());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().size());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertNoDispatcherLeak(closeDispatcher);
        } finally {
            releaseOwner.countDown();
            releaseDestroy.countDown();
            process.complete(143);
            dispatcher.whenIdle().get(2, TimeUnit.SECONDS);
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
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, 3);
        DefaultLineSession worker =
                new DefaultLineSession(openSession(process, closeDispatcher), LineSessionSettings.defaults());
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> worker,
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1),
                System::nanoTime,
                PoolRetirementDispatcher::execute,
                (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        () -> {
                            closeStarted.countDown();
                            session.close();
                        },
                        allowTerminalObservation.thenCompose(ignored -> session.onExit()),
                        session.physicalOutputCleanup(),
                        admission));
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            worker.onExit().get(1, TimeUnit.SECONDS);
            worker.physicalOutputCleanup().get(1, TimeUnit.SECONDS);
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
    void delayedLineTerminalErrorReleasesResolvedSlotAndAdmissionsExactlyOnce() throws Exception {
        AssertionError terminalFailure = new AssertionError("delayed line terminal failed fatally");
        CompletableFuture<Void> delayedTerminal = new CompletableFuture<>();
        CompletableFuture<Void> closeTask = new CompletableFuture<>();
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stdout = new TrackingInputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, 3);
        DefaultLineSession worker =
                new DefaultLineSession(openSession(process, closeDispatcher), LineSessionSettings.defaults());
        PoolRetirementDispatcher.AdmissionPool admissions = new PoolRetirementDispatcher.AdmissionPool(2);
        WorkerPoolController.RetirementAdmissionProvider admissionProvider = deadlineNanos -> {
            PoolRetirementDispatcher.Admission admission = admissions.tryAcquire();
            if (admission == null) {
                throw new TimeoutException("test lifecycle capacity exhausted");
            }
            return admission;
        };
        WorkerPoolController<DefaultLineSession> pool = new WorkerPoolController<>(
                () -> worker,
                (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        () -> {
                            try {
                                session.close();
                                closeTask.complete(null);
                            } catch (Throwable failure) {
                                closeTask.completeExceptionally(failure);
                                throw failure;
                            }
                        },
                        delayedTerminal,
                        session.physicalOutputCleanup(),
                        admission),
                SingleWorkerPoolOptions.INSTANCE,
                TestPoolFailures.INSTANCE,
                "delayed-terminal line worker",
                "test-delayed-terminal-",
                task -> Threading.start("test-delayed-terminal-replenish-", task),
                PoolRetirementDispatcher::execute,
                (thread, failure) -> {},
                System::nanoTime,
                null,
                new WorkerPoolController.TestHooks(() -> {}, failure -> {}, failure -> {}, () -> {}),
                admissionProvider);
        try {
            assertEquals(0, admissions.availablePermits());
            CompletableFuture<Void> drain = pool.closeAsync();

            closeTask.get(1, TimeUnit.SECONDS);
            worker.onExit().get(1, TimeUnit.SECONDS);
            worker.physicalOutputCleanup().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            assertFalse(drain.isDone(), "terminal observation must keep the worker retiring");
            assertFalse(pool.slotReleaseCompletion().isDone());
            assertEquals(1, pool.metrics().size());
            assertEquals(1, pool.metrics().retiring());
            assertEquals(0, pool.metrics().retired());
            assertEquals(0, pool.metrics().failedWorkerCloses());
            assertEquals(0, admissions.availablePermits());

            delayedTerminal.completeExceptionally(terminalFailure);

            ExecutionException observed = assertThrows(ExecutionException.class, () -> drain.get(1, TimeUnit.SECONDS));
            assertSame(terminalFailure, observed.getCause());
            assertEquals(0, pool.metrics().size());
            assertEquals(0, pool.metrics().retiring());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().failedWorkerCloses());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.CLOSED));
            pool.slotReleaseCompletion().get(1, TimeUnit.SECONDS);
            awaitAvailableAdmissions(admissions, 2);

            for (int attempt = 0; attempt < 10; attempt++) {
                ExecutionException repeated = assertThrows(
                        ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));
                assertSame(terminalFailure, repeated.getCause());
            }
            assertEquals(1, pool.metrics().failedWorkerCloses());
            assertEquals(2, admissions.availablePermits(), "resolved failure must release both lifecycle admissions");
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
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, 3);
        DefaultProtocolSession<String, String> worker = new DefaultProtocolSession<>(
                openSession(process, closeDispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> worker,
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1),
                PoolRetirementDispatcher::execute,
                (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        () -> {
                            closeStarted.countDown();
                            session.close();
                        },
                        allowTerminalObservation.thenCompose(ignored -> session.onExit()),
                        session.physicalOutputCleanup(),
                        admission));
        try {
            CompletableFuture<Void> close = pool.closeAsync();

            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            worker.onExit().get(1, TimeUnit.SECONDS);
            worker.physicalOutputCleanup().get(1, TimeUnit.SECONDS);
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
    void failedProtocolWarmupReturnsAfterCloseDeadlineWhilePhysicalCleanupRemainsOwned() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        ProtocolSession<String, String> firstWorker = new DefaultProtocolSession<>(
                openSession(process, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        assertTrue(stdout.awaitReadStarted(), "protocol stdout pump did not enter the blocked read");
        AtomicInteger starts = new AtomicInteger();
        IllegalStateException startupFailure = new IllegalStateException("second worker startup failed");

        try {
            PooledProtocolSessionException failure = assertThrows(
                    PooledProtocolSessionException.class,
                    () -> new DefaultPooledProtocolSession<>(
                            () -> {
                                if (starts.incrementAndGet() == 1) {
                                    return firstWorker;
                                }
                                throw startupFailure;
                            },
                            WorkerPoolSettings.<ProtocolSession<String, String>>defaults(ignored -> {}, ignored -> true)
                                    .withMaxSize(2)
                                    .withWarmupSize(2)
                                    .withCloseTimeout(CLOSE_TIMEOUT)));

            assertEquals(PooledProtocolSessionException.Reason.STARTUP_FAILED, failure.reason());
            assertSame(startupFailure, failure.getCause());
            assertEquals(1, failure.getSuppressed().length);
            PooledProtocolSessionException cleanup = (PooledProtocolSessionException) failure.getSuppressed()[0];
            assertEquals(PooledProtocolSessionException.Reason.WORKER_FAILED, cleanup.reason());
            assertTrue(cleanup.getCause() instanceof TimeoutException);
            assertTrue(stdout.awaitCloseInvoked(), "failed construction did not start worker cleanup");
            assertFalse(stdout.closeFinished(), "constructor timeout must not abandon physical cleanup");

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            firstWorker.onExit().handle((ignored, exitFailure) -> null).get(1, TimeUnit.SECONDS);
            PoolRetirementDispatcher.whenSharedIdle().get(1, TimeUnit.SECONDS);
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
            PoolRetirementDispatcher.whenSharedIdle().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void linePoolWaitsForPhysicalOutputCleanupAndMapsLateRuntimeFailure() throws Exception {
        IllegalStateException closeFailure = new IllegalStateException("line stdout close failed");
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(closeFailure);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        TestProcess process = new TestProcess(new TrackingOutputStream(), stdout, new TrackingInputStream());
        DefaultLineSession worker =
                new DefaultLineSession(openSession(process, dispatcher), LineSessionSettings.defaults());
        assertTrue(stdout.awaitReadStarted(), "line stdout pump did not enter the blocked read");
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> worker,
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1)
                        .withCloseTimeout(CLOSE_TIMEOUT));
        try {
            CompletableFuture<Void> eventual = pool.closeAsync();
            assertTrue(stdout.awaitCloseInvoked(), "line stdout physical close was not dispatched");

            PooledLineSessionException timeout = assertThrows(PooledLineSessionException.class, pool::close);

            assertEquals(PooledLineSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
            assertFalse(eventual.isDone());
            assertTrue(dispatcher.outstandingCount() > 0);

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> eventual.get(1, TimeUnit.SECONDS));
            PooledLineSessionException workerFailure = (PooledLineSessionException) observed.getCause();
            assertEquals(PooledLineSessionException.Reason.WORKER_FAILED, workerFailure.reason());
            assertSame(closeFailure, workerFailure.getCause());
            PooledLineSessionException repeated = assertThrows(PooledLineSessionException.class, pool::close);
            assertEquals(PooledLineSessionException.Reason.WORKER_FAILED, repeated.reason());
            assertSame(closeFailure, repeated.getCause());
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void protocolPoolWaitsForPhysicalOutputCleanupAndPreservesLateError() throws Exception {
        AssertionError closeFailure = new AssertionError("protocol stdout close failed");
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(closeFailure);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        TestProcess process = new TestProcess(new TrackingOutputStream(), stdout, new TrackingInputStream());
        DefaultProtocolSession<String, String> worker = new DefaultProtocolSession<>(
                openSession(process, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        assertTrue(stdout.awaitReadStarted(), "protocol stdout pump did not enter the blocked read");
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> worker,
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1)
                        .withCloseTimeout(CLOSE_TIMEOUT));
        try {
            CompletableFuture<Void> eventual = pool.closeAsync();
            assertTrue(stdout.awaitCloseInvoked(), "protocol stdout physical close was not dispatched");

            PooledProtocolSessionException timeout = assertThrows(PooledProtocolSessionException.class, pool::close);

            assertEquals(PooledProtocolSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
            assertFalse(eventual.isDone());
            assertTrue(dispatcher.outstandingCount() > 0);

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> eventual.get(1, TimeUnit.SECONDS));
            assertSame(closeFailure, observed.getCause());
            assertSame(closeFailure, assertThrows(AssertionError.class, pool::close));
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void linePoolPromotesLaterPhysicalErrorAndSuppressesPriorRuntimeExactlyOnce() throws Exception {
        IllegalStateException runtimeFailure = new IllegalStateException("first line worker close failed");
        AssertionError fatalFailure = new AssertionError("second line worker close failed fatally");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 4, 6);
        TestProcess firstProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(runtimeFailure),
                new TrackingInputStream());
        TestProcess secondProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(fatalFailure),
                new TrackingInputStream());
        List<LineSession> workers = List.of(
                new DefaultLineSession(openSession(firstProcess, dispatcher), LineSessionSettings.defaults()),
                new DefaultLineSession(openSession(secondProcess, dispatcher), LineSessionSettings.defaults()));
        AtomicInteger workerIndex = new AtomicInteger();
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> workers.get(workerIndex.getAndIncrement()),
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults(ignored -> {}, ignored -> true)
                        .withMaxSize(2)
                        .withWarmupSize(2));
        try {
            ExecutionException observed = assertThrows(
                    ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

            assertSame(fatalFailure, observed.getCause());
            assertSuppressedExactlyOnce(fatalFailure, runtimeFailure);
            assertSame(fatalFailure, assertThrows(AssertionError.class, pool::close));
            assertNoDispatcherLeak(dispatcher, 6);
        } finally {
            firstProcess.complete(143);
            secondProcess.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void protocolPoolKeepsFirstPhysicalErrorAndSuppressesLaterRuntimeExactlyOnce() throws Exception {
        AssertionError fatalFailure = new AssertionError("first protocol worker close failed fatally");
        IllegalStateException runtimeFailure = new IllegalStateException("second protocol worker close failed");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 4, 6);
        TestProcess firstProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(fatalFailure),
                new TrackingInputStream());
        TestProcess secondProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(runtimeFailure),
                new TrackingInputStream());
        List<ProtocolSession<String, String>> workers = List.of(
                new DefaultProtocolSession<>(
                        openSession(firstProcess, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults()),
                new DefaultProtocolSession<>(
                        openSession(secondProcess, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults()));
        AtomicInteger workerIndex = new AtomicInteger();
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> workers.get(workerIndex.getAndIncrement()),
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults(ignored -> {}, ignored -> true)
                        .withMaxSize(2)
                        .withWarmupSize(2));
        try {
            ExecutionException observed = assertThrows(
                    ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

            assertSame(fatalFailure, observed.getCause());
            assertSuppressedExactlyOnce(fatalFailure, runtimeFailure);
            assertSame(fatalFailure, assertThrows(AssertionError.class, pool::close));
            assertNoDispatcherLeak(dispatcher, 6);
        } finally {
            firstProcess.complete(143);
            secondProcess.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    private static DefaultSession openSession(Process process, BoundedCloseDispatcher dispatcher) {
        return openSession(process, dispatcher, ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO));
    }

    private static DefaultSession openSession(
            Process process, BoundedCloseDispatcher dispatcher, ShutdownPolicy shutdownPolicy) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                shutdownPolicy,
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "pool-output-cleanup-test", CommandEcho.empty()),
                () -> {},
                dispatcher,
                io.github.ulviar.procwright.internal.Threading::start);
    }

    private static ProtocolAdapter<String, String> noOpAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {}

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
    }

    private static PoolRetirementDispatcher saturatedNestedDispatcher(String threadPrefix) {
        return new PoolRetirementDispatcher(new BoundedTaskRunner.Limiter(1), Threading::start, threadPrefix);
    }

    private static void assertNoDispatcherLeak(BoundedCloseDispatcher dispatcher) {
        assertNoDispatcherLeak(dispatcher, 3);
    }

    private static void assertNoDispatcherLeak(BoundedCloseDispatcher dispatcher, int capacity) {
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
        dispatcher.reserve(capacity).release();
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

    private enum SingleWorkerPoolOptions implements WorkerPoolController.PoolOptions {
        INSTANCE;

        @Override
        public int maxSize() {
            return 1;
        }

        @Override
        public int warmupSize() {
            return 1;
        }

        @Override
        public int minIdle() {
            return 0;
        }

        @Override
        public Duration acquireTimeout() {
            return Duration.ofSeconds(1);
        }

        @Override
        public int maxRequestsPerWorker() {
            return Integer.MAX_VALUE;
        }

        @Override
        public Duration maxWorkerAge() {
            return Duration.ZERO;
        }

        @Override
        public boolean backgroundReplenishment() {
            return false;
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

    private static final class BlockingReadFailingCloseInputStream extends InputStream {

        private final Object operationLock = new Object();
        private final Throwable closeFailure;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch readFinished = new CountDownLatch(1);
        private final CountDownLatch closeInvoked = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);

        private BlockingReadFailingCloseInputStream(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            synchronized (operationLock) {
                readStarted.countDown();
                try {
                    awaitUninterruptibly(releaseRead);
                    return -1;
                } finally {
                    readFinished.countDown();
                }
            }
        }

        @Override
        public void close() throws IOException {
            closeInvoked.countDown();
            try {
                synchronized (operationLock) {
                    if (closeFailure != null) {
                        throwUnchecked(closeFailure);
                    }
                }
            } finally {
                closeFinished.countDown();
            }
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitReadFinished() throws InterruptedException {
            return readFinished.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseInvoked() throws InterruptedException {
            return closeInvoked.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseFinished(Duration timeout) throws InterruptedException {
            return closeFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private boolean closeFinished() {
            return closeFinished.getCount() == 0;
        }

        private void releaseRead() {
            releaseRead.countDown();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeFinished.countDown();
        }

        private boolean awaitCloseFinished(Duration timeout) throws InterruptedException {
            return closeFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static final class CloseUnblocksInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(closed);
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseFinished(Duration timeout) throws InterruptedException {
            return closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static final class ImmediateFailingCloseInputStream extends InputStream {

        private final Throwable closeFailure;

        private ImmediateFailingCloseInputStream(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            throwUnchecked(closeFailure);
        }
    }

    private static final class TrackingOutputStream extends OutputStream {

        private final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeFinished.countDown();
        }

        private boolean awaitCloseFinished(Duration timeout) throws InterruptedException {
            return closeFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static class TestProcess extends Process {

        private final OutputStream stdin;
        private final InputStream stdout;
        private final InputStream stderr;
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicReference<Integer> exitCode = new AtomicReference<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountDownLatch destroyInvoked = new CountDownLatch(1);

        private TestProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode.get();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            Integer value = exitCode.get();
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {
            recordDestroyInvoked();
            complete(143);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        protected final void recordDestroyInvoked() {
            destroyInvoked.countDown();
        }

        final boolean awaitDestroyInvoked() throws InterruptedException {
            return destroyInvoked.await(1, TimeUnit.SECONDS);
        }

        final boolean destroyInvoked() {
            return destroyInvoked.getCount() == 0;
        }

        protected void complete(int exitCode) {
            if (this.exitCode.compareAndSet(null, exitCode)) {
                alive.set(false);
                exited.countDown();
            }
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

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void throwUnchecked(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("unsupported test failure", failure);
    }
}

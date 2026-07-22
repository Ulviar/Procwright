/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DefaultSessionOutputCleanupTest {

    @Test
    void publicExitPublicationFollowsBothPhysicalOutputClosesRepeatedly() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream(null);
            BlockingPhysicalCloseInputStream stderr = new BlockingPhysicalCloseInputStream(null);
            MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
            DefaultSession session = openSession(process, new BoundedCloseDispatcher(2, 1, 3));
            CompletableFuture<?> publicExit = session.onExit();
            try {
                process.complete(0);
                assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));
                assertTrue(stderr.closeEntered.await(1, TimeUnit.SECONDS));
                assertFalse(publicExit.isDone(), "public exit preceded physical output cleanup at attempt " + attempt);

                stdout.releaseClose.countDown();
                assertFalse(publicExit.isDone(), "one physical output close cannot publish exit at attempt " + attempt);

                stderr.releaseClose.countDown();
                publicExit.get(1, TimeUnit.SECONDS);

                assertTrue(session.physicalOutputCleanup().isDone());
                assertEquals(1, stdout.closeCalls());
                assertEquals(1, stderr.closeCalls());
            } finally {
                stdout.releaseClose.countDown();
                stderr.releaseClose.countDown();
                process.complete(143);
                session.close();
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> poolRetirementPreservesEitherOutputCloseFailure() {
        return Stream.of(OutputSource.values())
                .map(source -> DynamicTest.dynamicTest(
                        source + " pool retirement failure", () -> verifyPoolRetirementFailure(source)));
    }

    @Test
    void poolRetirementSuppressesDuplicatePhysicalFailureIdentity() throws Exception {
        AssertionError sharedFailure = new AssertionError("shared output close failed");
        verifyPoolFailureAggregation(sharedFailure, sharedFailure, sharedFailure);

        assertEquals(0, countIdentity(sharedFailure.getSuppressed(), sharedFailure));
    }

    @Test
    void poolRetirementDoesNotExpandCyclicPhysicalFailureGraph() throws Exception {
        IllegalStateException stdoutFailure = new IllegalStateException("stdout close failed");
        IllegalArgumentException stderrFailure = new IllegalArgumentException("stderr close failed");
        stdoutFailure.addSuppressed(stderrFailure);
        stderrFailure.addSuppressed(stdoutFailure);

        verifyPoolFailureAggregation(stdoutFailure, stderrFailure, stdoutFailure);

        assertEquals(1, countIdentity(stdoutFailure.getSuppressed(), stderrFailure));
        assertEquals(1, countIdentity(stderrFailure.getSuppressed(), stdoutFailure));
    }

    @Test
    void physicalOutputCleanupIsCancellationIsolatedAndWaitsForBothPhysicalCloses() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TrackingInputStream stderr = new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, 3));
        Thread reader = startRawRead(session, OutputSource.STDOUT, stdout);
        try {
            CompletableFuture<Void> cancelledView = session.physicalOutputCleanup();
            CompletableFuture<Void> completedView = session.physicalOutputCleanup();

            assertTrue(cancelledView.cancel(true));
            assertTrue(completedView.complete(null));
            process.complete(0);
            assertFalse(session.onExit().isDone());
            assertFalse(session.physicalOutputCleanup().isDone());

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            session.physicalOutputCleanup().get(1, TimeUnit.SECONDS);
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());

            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            stdout.releaseRead();
            process.complete(143);
            session.close();
            reader.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    void physicalOutputCleanupAggregatesBothFailuresWithFatalErrorPriority() throws Exception {
        IllegalStateException stdoutFailure = new IllegalStateException("stdout close failed");
        AssertionError stderrFailure = new AssertionError("stderr close failed");
        ImmediateFailingCloseInputStream stdout = new ImmediateFailingCloseInputStream(stdoutFailure);
        ImmediateFailingCloseInputStream stderr = new ImmediateFailingCloseInputStream(stderrFailure);
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, 3));
        try {
            process.complete(0);
            session.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);

            ExecutionException cleanupFailure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> session.physicalOutputCleanup().get(1, TimeUnit.SECONDS));

            assertSame(stderrFailure, cleanupFailure.getCause());
            assertEquals(1, countIdentity(stderrFailure.getSuppressed(), stdoutFailure));
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            session.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> inlinePublicOutputCloseFailureBecomesTheTerminalSessionFailureByIdentity() {
        return Stream.of(OutputSource.values()).flatMap(source -> Stream.of(
                        new IOException(source + " close failed"),
                        new IllegalStateException(source + " close failed"),
                        new AssertionError(source + " close failed"))
                .map(expected -> DynamicTest.dynamicTest(
                        source + " / " + expected.getClass().getSimpleName(),
                        () -> verifyInlinePublicCloseFailure(source, expected))));
    }

    @Test
    void blockedInlineOutputCloseFailureOverridesAlreadySelectedNaturalProcessSuccess() throws Exception {
        AssertionError expected = new AssertionError("blocked stdout close failed");
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream(expected);
        TrackingInputStream stderr = new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, 3));
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> closeResult = closer.submit(() -> {
                try {
                    session.stdout().close();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            process.complete(0);
            assertTrue(eventually(session::exitCompleted));
            assertFalse(session.onExit().isDone());

            stdout.releaseClose.countDown();
            assertSame(expected, closeResult.get(1, TimeUnit.SECONDS));
            ExecutionException terminal = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

            assertSame(expected, terminal.getCause());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            stdout.releaseClose.countDown();
            process.complete(143);
            session.close();
            closer.shutdownNow();
            assertTrue(closer.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void physicalOutputCleanupWaitsForAcceptedFallbackSettlementAndItsCloseFailure() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("stdout close starter failed");
        IOException closeFailure = new IOException("stdout fallback close failed");
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream(closeFailure);
        TrackingInputStream stderr = new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3, (name, task) -> {
            if (name.contains("stdout")) {
                throw startFailure;
            }
            return io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
        DefaultSession session = openSession(process, dispatcher);
        try {
            process.complete(0);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            assertFalse(session.onExit().isDone());
            assertFalse(
                    session.physicalOutputCleanup().isDone(),
                    "accepted fallback ownership must retain physical output cleanup");

            stdout.releaseClose.countDown();
            ExecutionException terminal = assertThrows(ExecutionException.class, () -> session.physicalOutputCleanup()
                    .get(1, TimeUnit.SECONDS));
            ExecutionException publicFailure = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));
            assertSame(startFailure, terminal.getCause());
            assertSame(startFailure, publicFailure.getCause());
            assertEquals(java.util.List.of(closeFailure), java.util.List.of(startFailure.getSuppressed()));
            assertEquals(1, stdout.closeCalls());
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            stdout.releaseClose.countDown();
            process.complete(143);
            session.close();
        }
    }

    @Test
    void idleTimeoutCompletesPhysicalOutputCleanupAfterBothStreamsCloseExactlyOnce() throws Exception {
        TrackingInputStream stdout = new TrackingInputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = DefaultSession.openTransactionally(
                process,
                Duration.ofMillis(20),
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "output-cleanup-test", CommandEcho.empty()),
                () -> {},
                new BoundedCloseDispatcher(1, 2, 3),
                io.github.ulviar.procwright.internal.Threading::start);
        try {
            assertTrue(session.onExit().get(1, TimeUnit.SECONDS).timedOut());
            session.physicalOutputCleanup().get(1, TimeUnit.SECONDS);

            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            process.complete(143);
            session.close();
        }
    }

    @Test
    void everyDistinctLateOutputCloseFailureIsReportedExactlyOnce() throws Exception {
        AssertionError stdoutFailure = new AssertionError("stdout close failed");
        AssertionError stderrFailure = new AssertionError("stderr close failed");
        ImmediateFailingCloseInputStream stdout = new ImmediateFailingCloseInputStream(stdoutFailure);
        ImmediateFailingCloseInputStream stderr = new ImmediateFailingCloseInputStream(stderrFailure);
        TrackingOutputStream stdin = new TrackingOutputStream();
        MatrixProcess process = new MatrixProcess(stdin, stdout, stderr);
        CopyOnWriteFailureHandler reports = new CopyOnWriteFailureHandler(2);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, 3, reports::start));
        try {
            process.complete(0);
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertTrue(reports.await());
            assertTrue(reports.awaitWorkers());

            assertEquals(1, reports.count(stdoutFailure));
            assertEquals(1, reports.count(stderrFailure));
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            session.close();
        }
    }

    @Test
    void terminalStdinFailureOwnsBothLaterOutputCloseFailures() throws Exception {
        AssertionError stdinFailure = new AssertionError("stdin close failed");
        AssertionError stdoutFailure = new AssertionError("stdout close failed");
        AssertionError stderrFailure = new AssertionError("stderr close failed");
        ImmediateFailingCloseOutputStream stdin = new ImmediateFailingCloseOutputStream(stdinFailure);
        ImmediateFailingCloseInputStream stdout = new ImmediateFailingCloseInputStream(stdoutFailure);
        ImmediateFailingCloseInputStream stderr = new ImmediateFailingCloseInputStream(stderrFailure);
        MatrixProcess process = new MatrixProcess(stdin, stdout, stderr);
        CopyOnWriteFailureHandler reports = new CopyOnWriteFailureHandler(0);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, 3, reports::start));
        try {
            session.closeStdin();
            ExecutionException terminal = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));
            assertSame(stdinFailure, terminal.getCause());
            assertTrue(stdout.awaitClosed());
            assertTrue(stderr.awaitClosed());
            assertTrue(reports.awaitWorkers());

            assertEquals(1, countIdentity(stdinFailure.getSuppressed(), stdoutFailure));
            assertEquals(1, countIdentity(stdinFailure.getSuppressed(), stderrFailure));
            assertEquals(0, reports.size());
            assertEquals(1, stdin.closeCalls());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            session.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> blockedRawOutputCloseIsBoundedAndReportsEveryFailureKindForEveryTerminalPath() {
        return Stream.of(OutputSource.values())
                .flatMap(source -> Stream.of(TerminalPath.values()).flatMap(path -> Stream.of(FailureKind.values())
                        .map(kind -> DynamicTest.dynamicTest(
                                source + " / " + path + " / " + kind,
                                () -> verifyBlockedClose(source, path, kind.create(source, path))))));
    }

    private static void verifyBlockedClose(OutputSource source, TerminalPath path, Throwable closeFailure)
            throws Exception {
        BlockingReadFailingCloseInputStream blockedOutput = new BlockingReadFailingCloseInputStream(closeFailure);
        TrackingInputStream otherOutput = new TrackingInputStream();
        TrackingOutputStream stdin = new TrackingOutputStream();
        MatrixProcess process = new MatrixProcess(
                stdin,
                source == OutputSource.STDOUT ? blockedOutput : otherOutput,
                source == OutputSource.STDERR ? blockedOutput : otherOutput);
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        AtomicInteger reportCount = new AtomicInteger();
        CountDownLatch reported = new CountDownLatch(1);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3, (name, task) -> {
            Thread worker = new Thread(task, name);
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((ignored, failure) -> {
                reportedFailure.compareAndSet(null, failure);
                reportCount.incrementAndGet();
                reported.countDown();
            });
            worker.start();
            return worker;
        });
        DefaultSession session = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "output-cleanup-test", CommandEcho.empty()),
                () -> {},
                dispatcher,
                io.github.ulviar.procwright.internal.Threading::start);
        CompletableFuture<Void> physicalOutputCleanup = session.physicalOutputCleanup();
        Thread rawReader = null;
        Thread readinessCaller = null;
        AtomicReference<Throwable> readinessFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        try {
            if (path == TerminalPath.NATURAL_EXIT || path == TerminalPath.EXPLICIT_CLOSE) {
                rawReader = startRawRead(session, source, blockedOutput);
            } else {
                readinessCaller = startReadinessCheck(session, source, path, readinessFailure, interruptRestored);
                assertTrue(blockedOutput.awaitReadStarted(), "readiness probe did not enter the selected output");
            }

            switch (path) {
                case NATURAL_EXIT -> process.complete(0);
                case EXPLICIT_CLOSE -> session.close();
                case READINESS_TIMEOUT -> {
                    readinessCaller.join(TimeUnit.SECONDS.toMillis(1));
                    assertFalse(readinessCaller.isAlive(), "readiness timeout did not return");
                    CommandExecutionException failure = (CommandExecutionException) readinessFailure.get();
                    assertEquals(CommandExecutionException.Reason.READINESS_TIMEOUT, failure.reason());
                }
                case READINESS_INTERRUPT -> {
                    readinessCaller.interrupt();
                    readinessCaller.join(TimeUnit.SECONDS.toMillis(1));
                    assertFalse(readinessCaller.isAlive(), "interrupted readiness check did not return");
                    CommandExecutionException failure = (CommandExecutionException) readinessFailure.get();
                    assertEquals(CommandExecutionException.Reason.READINESS_FAILED, failure.reason());
                    assertTrue(failure.getCause() instanceof InterruptedException);
                    assertTrue(interruptRestored.get(), "readiness caller interrupt status was not restored");
                }
            }

            assertFalse(session.onExit().isDone());
            assertTrue(blockedOutput.awaitCloseInvoked(), "output close was not dispatched");
            assertEquals(0, blockedOutput.closeCalls(), "physical close must still wait outside lifecycle code");
            assertFalse(physicalOutputCleanup.isDone(), "physical cleanup ignored the blocked close");

            blockedOutput.releaseRead();
            assertTrue(blockedOutput.awaitReadFinished());
            assertTrue(reported.await(1, TimeUnit.SECONDS), "late close failure was not reported");
            ExecutionException physicalFailure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> physicalOutputCleanup.get(1, TimeUnit.SECONDS));
            assertTrue(session.onExit().get(1, TimeUnit.SECONDS).exitCode().isPresent());

            assertSame(closeFailure, reportedFailure.get());
            assertSame(closeFailure, physicalFailure.getCause());
            assertEquals(1, reportCount.get());
            assertEquals(1, blockedOutput.closeCalls());
            assertTrue(stdin.awaitClosed());
            assertTrue(otherOutput.awaitClosed());
            assertEquals(1, stdin.closeCalls());
            assertEquals(1, otherOutput.closeCalls());
            assertEquals(1, process.stdinGetterCalls());
            assertEquals(1, process.stdoutGetterCalls());
            assertEquals(1, process.stderrGetterCalls());

            session.close();
            assertEquals(1, blockedOutput.closeCalls());
            assertEquals(1, stdin.closeCalls());
            assertEquals(1, otherOutput.closeCalls());
        } finally {
            blockedOutput.releaseRead();
            process.complete(143);
            session.close();
            if (rawReader != null) {
                rawReader.join(TimeUnit.SECONDS.toMillis(1));
            }
            if (readinessCaller != null) {
                readinessCaller.interrupt();
                readinessCaller.join(TimeUnit.SECONDS.toMillis(1));
            }
        }
    }

    private static DefaultSession openSession(Process process, BoundedCloseDispatcher dispatcher) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "output-cleanup-test", CommandEcho.empty()),
                () -> {},
                dispatcher,
                io.github.ulviar.procwright.internal.Threading::start);
    }

    private static void verifyInlinePublicCloseFailure(OutputSource source, Throwable expected) throws Exception {
        InputStream stdout = source == OutputSource.STDOUT
                ? new ImmediateFailingCloseInputStream(expected)
                : new TrackingInputStream();
        InputStream stderr = source == OutputSource.STDERR
                ? new ImmediateFailingCloseInputStream(expected)
                : new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, 3));
        try {
            Throwable closeFailure;
            try {
                source.stream(session).close();
                closeFailure = null;
            } catch (Throwable failure) {
                closeFailure = failure;
            }
            assertSame(expected, closeFailure);
            process.complete(0);

            ExecutionException terminal = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

            assertSame(expected, terminal.getCause());
            assertTrue(session.physicalOutputCleanup().isDone());
        } finally {
            process.complete(143);
            session.close();
        }
    }

    private static void verifyPoolRetirementFailure(OutputSource source) throws Exception {
        AssertionError closeFailure = new AssertionError(source + " close failed");
        InputStream stdout = source == OutputSource.STDOUT
                ? new ImmediateFailingCloseInputStream(closeFailure)
                : new TrackingInputStream();
        InputStream stderr = source == OutputSource.STDERR
                ? new ImmediateFailingCloseInputStream(closeFailure)
                : new TrackingInputStream();

        verifyPoolFailureAggregation(stdout, stderr, closeFailure);
    }

    private static void verifyPoolFailureAggregation(
            Throwable stdoutFailure, Throwable stderrFailure, Throwable expectedPrimary) throws Exception {
        verifyPoolFailureAggregation(
                new ImmediateFailingCloseInputStream(stdoutFailure),
                new ImmediateFailingCloseInputStream(stderrFailure),
                expectedPrimary);
    }

    private static void verifyPoolFailureAggregation(InputStream stdout, InputStream stderr, Throwable expectedPrimary)
            throws Exception {
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, 3));
        WorkerPoolController<DefaultSession> pool = new WorkerPoolController<>(
                () -> session,
                (worker, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        worker, worker.onExit(), worker.physicalOutputCleanup(), admission),
                PoolTestOptions.INSTANCE,
                PoolTestFailures.INSTANCE,
                "default session",
                "output-cleanup-pool-");
        try {
            ExecutionException observed = assertThrows(
                    ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

            assertSame(expectedPrimary, observed.getCause());
            assertEquals(1, pool.metrics().failedWorkerCloses());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().retiring());
            assertEquals(0, pool.metrics().size());
        } finally {
            process.complete(143);
            session.close();
            pool.closeAsync();
        }
    }

    private static int countIdentity(Throwable[] failures, Throwable expected) {
        return Math.toIntExact(java.util.Arrays.stream(failures)
                .filter(failure -> failure == expected)
                .count());
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static Thread startRawRead(
            DefaultSession session, OutputSource source, BlockingReadFailingCloseInputStream blockedOutput)
            throws InterruptedException {
        Thread reader = new Thread(() -> readOne(source.stream(session)), "blocked-raw-output-reader");
        reader.setDaemon(true);
        reader.start();
        assertTrue(blockedOutput.awaitReadStarted(), "raw reader did not enter the selected output");
        return reader;
    }

    private static Thread startReadinessCheck(
            DefaultSession session,
            OutputSource source,
            TerminalPath path,
            AtomicReference<Throwable> failure,
            AtomicBoolean interruptRestored) {
        Duration timeout = path == TerminalPath.READINESS_TIMEOUT ? Duration.ofMillis(50) : Duration.ofSeconds(5);
        Thread caller = new Thread(
                () -> {
                    try {
                        ReadinessSupport.check(
                                session, target -> readOne(source.stream(target)), timeout, session::close);
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                        interruptRestored.set(Thread.currentThread().isInterrupted());
                    }
                },
                "blocked-output-readiness-caller");
        caller.setDaemon(true);
        caller.start();
        return caller;
    }

    private static void readOne(InputStream stream) {
        try {
            stream.read();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private enum OutputSource {
        STDOUT {
            @Override
            InputStream stream(DefaultSession session) {
                return session.stdout();
            }
        },
        STDERR {
            @Override
            InputStream stream(DefaultSession session) {
                return session.stderr();
            }
        };

        abstract InputStream stream(DefaultSession session);
    }

    private enum TerminalPath {
        NATURAL_EXIT,
        EXPLICIT_CLOSE,
        READINESS_TIMEOUT,
        READINESS_INTERRUPT
    }

    private enum FailureKind {
        IO {
            @Override
            Throwable create(OutputSource source, TerminalPath path) {
                return new IOException(source + " close failed after " + path);
            }
        },
        RUNTIME {
            @Override
            Throwable create(OutputSource source, TerminalPath path) {
                return new IllegalStateException(source + " close failed after " + path);
            }
        },
        ERROR {
            @Override
            Throwable create(OutputSource source, TerminalPath path) {
                return new AssertionError(source + " close failed after " + path);
            }
        };

        abstract Throwable create(OutputSource source, TerminalPath path);
    }

    private static final class BlockingReadFailingCloseInputStream extends InputStream {

        private final Object operationLock = new Object();
        private final Throwable closeFailure;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch readFinished = new CountDownLatch(1);
        private final CountDownLatch closeInvoked = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

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
            synchronized (operationLock) {
                closeCalls.incrementAndGet();
                if (closeFailure != null) {
                    throwUnchecked(closeFailure);
                }
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

        private void releaseRead() {
            releaseRead.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class BlockingPhysicalCloseInputStream extends InputStream {

        private final Throwable closeFailure;
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        private BlockingPhysicalCloseInputStream(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            throwUnchecked(closeFailure);
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class TrackingOutputStream extends OutputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class ImmediateFailingCloseOutputStream extends OutputStream {

        private final Error failure;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private ImmediateFailingCloseOutputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            throw failure;
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class ImmediateFailingCloseInputStream extends InputStream {

        private final Throwable failure;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        private ImmediateFailingCloseInputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closed.countDown();
            throwUnchecked(failure);
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class CopyOnWriteFailureHandler {

        private final java.util.concurrent.CopyOnWriteArrayList<Throwable> failures =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CountDownLatch expected;
        private final CountDownLatch workersFinished = new CountDownLatch(3);

        private CopyOnWriteFailureHandler(int expectedFailures) {
            expected = new CountDownLatch(expectedFailures);
        }

        private Thread start(String name, Runnable task) {
            Thread worker = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            workersFinished.countDown();
                        }
                    },
                    name);
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((ignored, failure) -> {
                failures.add(failure);
                expected.countDown();
            });
            worker.start();
            return worker;
        }

        private boolean await() throws InterruptedException {
            return expected.await(1, TimeUnit.SECONDS);
        }

        private int count(Throwable expectedFailure) {
            return Math.toIntExact(failures.stream()
                    .filter(failure -> failure == expectedFailure)
                    .count());
        }

        private boolean awaitWorkers() throws InterruptedException {
            return workersFinished.await(1, TimeUnit.SECONDS);
        }

        private int size() {
            return failures.size();
        }
    }

    private static final class MatrixProcess extends Process {

        private final OutputStream stdin;
        private final InputStream stdout;
        private final InputStream stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger stdinGetterCalls = new AtomicInteger();
        private final AtomicInteger stdoutGetterCalls = new AtomicInteger();
        private final AtomicInteger stderrGetterCalls = new AtomicInteger();

        private MatrixProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            stdinGetterCalls.incrementAndGet();
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            stdoutGetterCalls.incrementAndGet();
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrGetterCalls.incrementAndGet();
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException ignored) {
                return false;
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {
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

        private void complete(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        private int stdinGetterCalls() {
            return stdinGetterCalls.get();
        }

        private int stdoutGetterCalls() {
            return stdoutGetterCalls.get();
        }

        private int stderrGetterCalls() {
            return stderrGetterCalls.get();
        }
    }

    private enum PoolTestOptions implements WorkerPoolController.PoolOptions {
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

    private enum PoolTestFailures implements WorkerPoolController.FailureFactory {
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

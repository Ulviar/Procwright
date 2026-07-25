/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DefaultSessionPhysicalOutputCleanupTest extends DefaultSessionOutputCleanupTestSupport {

    @Test
    void publicExitPublicationFollowsBothPhysicalOutputClosesRepeatedly() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream(null);
            BlockingPhysicalCloseInputStream stderr = new BlockingPhysicalCloseInputStream(null);
            MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
            DefaultSession session = openSession(process, new BoundedCloseDispatcher(2, 1));
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

    @Test
    void physicalOutputCleanupIsCancellationIsolatedAndWaitsForBothPhysicalCloses() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TrackingInputStream stderr = new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2));
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
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2));
        try {
            process.complete(0);
            session.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);

            ExecutionException cleanupFailure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> session.physicalOutputCleanup().get(1, TimeUnit.SECONDS));

            assertInstanceOf(Error.class, cleanupFailure.getCause());
            assertSame(stderrFailure, cleanupFailure.getCause().getCause());
            assertEquals(
                    java.util.List.of(stdoutFailure),
                    java.util.List.of(cleanupFailure.getCause().getSuppressed()));
            assertEquals(0, stderrFailure.getSuppressed().length);
            assertEquals(0, stdoutFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            session.close();
        }
    }

    @Test
    void physicalOutputCleanupPrefersRuntimeFailureOverCheckedFailure() throws Exception {
        assertPhysicalOutputFailurePriority(
                new IOException("stdout close failed"), new IllegalStateException("stderr close failed"), false);
    }

    @Test
    void physicalOutputCleanupPreservesFirstFailureWithinOnePriorityCategory() throws Exception {
        assertPhysicalOutputFailurePriority(
                new IllegalStateException("stdout close failed"),
                new IllegalArgumentException("stderr close failed"),
                true);
    }

    @Test
    void physicalOutputCleanupWaitsForAcceptedFallbackSettlementAndItsCloseFailure() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("stdout close starter failed");
        IOException closeFailure = new IOException("stdout fallback close failed");
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream(closeFailure);
        TrackingInputStream stderr = new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, (name, task) -> {
            if (name.contains("stdout")) {
                throw startFailure;
            }
            io.github.ulviar.procwright.internal.Threading.start(name, task);
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
            Throwable physicalCleanupFailure = terminal.getCause();
            assertSame(startFailure, physicalCleanupFailure.getCause());
            assertEquals(java.util.List.of(closeFailure), java.util.List.of(physicalCleanupFailure.getSuppressed()));
            assertSame(startFailure, publicFailure.getCause().getCause());
            assertEquals(
                    java.util.List.of(closeFailure),
                    java.util.List.of(publicFailure.getCause().getSuppressed()));
            assertEquals(0, startFailure.getSuppressed().length);
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
                new BoundedCloseDispatcher(1, 2),
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, (name, task) -> {
            Thread worker = new Thread(task, name);
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((ignored, failure) -> {
                reportedFailure.compareAndSet(null, failure);
                reportCount.incrementAndGet();
                reported.countDown();
            });
            worker.start();
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

    private static void assertPhysicalOutputFailurePriority(
            Throwable stdoutFailure, Throwable stderrFailure, boolean stdoutWins) throws Exception {
        ImmediateFailingCloseInputStream stdout = new ImmediateFailingCloseInputStream(stdoutFailure);
        ImmediateFailingCloseInputStream stderr = new ImmediateFailingCloseInputStream(stderrFailure);
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2));
        try {
            process.complete(0);
            session.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);

            ExecutionException cleanupFailure =
                    assertThrows(ExecutionException.class, () -> session.physicalOutputCleanup()
                            .get(1, TimeUnit.SECONDS));

            Throwable expectedPrimary = stdoutWins ? stdoutFailure : stderrFailure;
            Throwable expectedSecondary = stdoutWins ? stderrFailure : stdoutFailure;
            assertSame(expectedPrimary, cleanupFailure.getCause().getCause());
            assertEquals(
                    java.util.List.of(expectedSecondary),
                    java.util.List.of(cleanupFailure.getCause().getSuppressed()));
        } finally {
            session.close();
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

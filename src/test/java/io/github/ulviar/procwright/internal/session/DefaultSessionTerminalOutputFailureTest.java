/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DefaultSessionTerminalOutputFailureTest extends DefaultSessionOutputCleanupTestSupport {

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
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2));
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
            assertTrue(eventually(session::terminationPublished));
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
    void everyDistinctLateOutputCloseFailureIsReportedExactlyOnce() throws Exception {
        AssertionError stdoutFailure = new AssertionError("stdout close failed");
        AssertionError stderrFailure = new AssertionError("stderr close failed");
        ImmediateFailingCloseInputStream stdout = new ImmediateFailingCloseInputStream(stdoutFailure);
        ImmediateFailingCloseInputStream stderr = new ImmediateFailingCloseInputStream(stderrFailure);
        TrackingOutputStream stdin = new TrackingOutputStream();
        MatrixProcess process = new MatrixProcess(stdin, stdout, stderr);
        CopyOnWriteFailureHandler reports = new CopyOnWriteFailureHandler(1);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, reports::start));
        try {
            process.complete(0);
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertTrue(reports.await());
            assertTrue(reports.awaitWorkers());

            Throwable reported = reports.onlyFailure();
            assertSame(stdoutFailure, reported.getCause());
            assertEquals(java.util.List.of(stderrFailure), java.util.List.of(reported.getSuppressed()));
            assertEquals(0, stdoutFailure.getSuppressed().length);
            assertEquals(0, stderrFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            session.close();
        }
    }

    @RepeatedTest(25)
    void terminalStdinFailureOwnsBothLaterOutputCloseFailures() throws Exception {
        AssertionError stdinFailure = new AssertionError("stdin close failed");
        AssertionError stdoutFailure = new AssertionError("stdout close failed");
        AssertionError stderrFailure = new AssertionError("stderr close failed");
        ImmediateFailingCloseOutputStream stdin = new ImmediateFailingCloseOutputStream(stdinFailure);
        ImmediateFailingCloseInputStream stdout = new ImmediateFailingCloseInputStream(stdoutFailure);
        ImmediateFailingCloseInputStream stderr = new ImmediateFailingCloseInputStream(stderrFailure);
        MatrixProcess process = new MatrixProcess(stdin, stdout, stderr);
        CopyOnWriteFailureHandler reports = new CopyOnWriteFailureHandler(0);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2, reports::start));
        try {
            ExecutionException terminal;
            try (var monitor = hold(stdinFailure)) {
                monitor.verifyHeld();
                session.closeStdin();
                terminal = org.junit.jupiter.api.Assertions.assertThrows(
                        ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));
            }
            assertSame(stdinFailure, terminal.getCause().getCause());
            assertTrue(stdout.awaitClosed());
            assertTrue(stderr.awaitClosed());
            assertTrue(reports.awaitWorkers());

            assertEquals(
                    java.util.List.of(stdoutFailure, stderrFailure),
                    java.util.List.of(terminal.getCause().getSuppressed()));
            assertEquals(0, stdinFailure.getSuppressed().length);
            assertEquals(0, stdoutFailure.getSuppressed().length);
            assertEquals(0, stderrFailure.getSuppressed().length);
            assertEquals(0, reports.size());
            assertEquals(1, stdin.closeCalls());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            session.close();
        }
    }

    private static void verifyInlinePublicCloseFailure(OutputSource source, Throwable expected) throws Exception {
        InputStream stdout = source == OutputSource.STDOUT
                ? new ImmediateFailingCloseInputStream(expected)
                : new TrackingInputStream();
        InputStream stderr = source == OutputSource.STDERR
                ? new ImmediateFailingCloseInputStream(expected)
                : new TrackingInputStream();
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2));
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

    private static final class BlockingPhysicalCloseInputStream extends InputStream {

        private final Throwable closeFailure;
        private final java.util.concurrent.CountDownLatch closeEntered = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseClose = new java.util.concurrent.CountDownLatch(1);
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
        private final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
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
        private final java.util.concurrent.CountDownLatch expected;
        private final java.util.concurrent.CountDownLatch workersFinished = new java.util.concurrent.CountDownLatch(3);

        private CopyOnWriteFailureHandler(int expectedFailures) {
            expected = new java.util.concurrent.CountDownLatch(expectedFailures);
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

        private Throwable onlyFailure() {
            assertEquals(1, failures.size());
            return failures.get(0);
        }

        private boolean awaitWorkers() throws InterruptedException {
            return workersFinished.await(1, TimeUnit.SECONDS);
        }

        private int size() {
            return failures.size();
        }
    }

    private static void awaitUninterruptibly(java.util.concurrent.CountDownLatch latch) {
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

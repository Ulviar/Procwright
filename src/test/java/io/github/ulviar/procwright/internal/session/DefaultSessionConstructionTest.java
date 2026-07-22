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
import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ProcessIoResources;
import io.github.ulviar.procwright.internal.Threading;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionConstructionTest {

    @Test
    void everyWatcherStartFailureRollsBackAllStableResourcesExactlyOnce() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 2; failedOrdinal++) {
            int expectedFailedOrdinal = failedOrdinal;
            for (boolean failAfterStarting : new boolean[] {false, true}) {
                for (boolean fatal : new boolean[] {false, true}) {
                    Throwable expected = fatal
                            ? new AssertionError("watcher " + failedOrdinal)
                            : new IllegalStateException("watcher " + failedOrdinal);
                    TrackingProcess process = new TrackingProcess();
                    AtomicInteger starts = new AtomicInteger();
                    List<Thread> startedThreads = new ArrayList<>();
                    DefaultSession.WatcherStarter starter = (name, task) -> {
                        int ordinal = starts.incrementAndGet();
                        if (ordinal == expectedFailedOrdinal && !failAfterStarting) {
                            throwUnchecked(expected);
                        }
                        Thread started = Threading.start(name, task);
                        startedThreads.add(started);
                        if (ordinal == expectedFailedOrdinal) {
                            throwUnchecked(expected);
                        }
                        return started;
                    };

                    Throwable actual = assertThrows(
                            expected.getClass(),
                            () -> DefaultSession.openTransactionally(
                                    process,
                                    Duration.ofSeconds(1),
                                    ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                                    StandardCharsets.UTF_8,
                                    diagnostics(),
                                    () -> {},
                                    new BoundedCloseDispatcher(3, 3, 6),
                                    starter));

                    assertSame(expected, actual);
                    assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
                    assertFalse(process.isAlive());
                    assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
                    assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
                    assertTrue(process.stderr.closed.await(1, TimeUnit.SECONDS));
                    assertEquals(1, process.stdin.closeCalls.get());
                    assertEquals(1, process.stdout.closeCalls.get());
                    assertEquals(1, process.stderr.closeCalls.get());
                    assertEquals(1, process.stdinGets.get());
                    assertEquals(1, process.stdoutGets.get());
                    assertEquals(1, process.stderrGets.get());
                    for (Thread started : startedThreads) {
                        started.join(TimeUnit.SECONDS.toMillis(1));
                        assertFalse(started.isAlive(), "aborted watcher did not leave its construction gate");
                    }
                    assertEquals(0, process.waitCalls.get());
                }
            }
        }
    }

    @Test
    void nullWatcherStartResultIsAConstructionFailureForEveryOrdinal() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 2; failedOrdinal++) {
            int expectedFailedOrdinal = failedOrdinal;
            TrackingProcess process = new TrackingProcess();
            AtomicInteger starts = new AtomicInteger();
            DefaultSession.WatcherStarter starter = (name, task) ->
                    starts.incrementAndGet() == expectedFailedOrdinal ? null : Threading.start(name, task);

            assertThrows(
                    NullPointerException.class,
                    () -> DefaultSession.openTransactionally(
                            process,
                            Duration.ofSeconds(1),
                            ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                            StandardCharsets.UTF_8,
                            diagnostics(),
                            () -> {},
                            new BoundedCloseDispatcher(3, 3, 6),
                            starter));

            assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
            assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
            assertTrue(process.stderr.closed.await(1, TimeUnit.SECONDS));
            assertEquals(1, process.stdin.closeCalls.get());
            assertEquals(1, process.stdout.closeCalls.get());
            assertEquals(1, process.stderr.closeCalls.get());
            assertEquals(0, process.waitCalls.get());
        }
    }

    @Test
    void beforeCommitFailureKeepsWatchersClosedAndPreservesFatalIdentity() throws Exception {
        TrackingProcess process = new TrackingProcess();
        AssertionError expected = new AssertionError("publication failed");
        AtomicInteger watcherBodies = new AtomicInteger();
        DefaultSession.WatcherStarter starter = (name, task) -> Threading.start(name, () -> {
            watcherBodies.incrementAndGet();
            task.run();
        });

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> DefaultSession.openTransactionally(
                        process,
                        Duration.ofSeconds(1),
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        StandardCharsets.UTF_8,
                        diagnostics(),
                        () -> {
                            assertEquals(1, process.stdinGets.get());
                            assertEquals(1, process.stdoutGets.get());
                            assertEquals(1, process.stderrGets.get());
                            throw expected;
                        },
                        new BoundedCloseDispatcher(3, 3, 6),
                        starter));

        assertSame(expected, actual);
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertEquals(2, watcherBodies.get(), "both guards may run, but neither guarded watcher body may execute");
        assertEquals(0, process.waitCalls.get());
    }

    @Test
    void everyInjectedConstructionFailureRollsBackProcessResourcesAndPublicationOwners() throws Exception {
        for (DefaultSession.ConstructionPoint failedPoint : DefaultSession.ConstructionPoint.values()) {
            TrackingProcess process = new TrackingProcess();
            BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(3, 3, 6);
            BoundedLifecyclePublisher resourcePublisher = new BoundedLifecyclePublisher(3);
            BoundedLifecyclePublisher exitPublisher = new BoundedLifecyclePublisher(1);
            OutOfMemoryError expected = new OutOfMemoryError("injected at " + failedPoint);
            List<Thread> startedThreads = new ArrayList<>();
            DefaultSession.WatcherStarter starter = (name, task) -> {
                Thread started = Threading.start(name, task);
                startedThreads.add(started);
                return started;
            };

            OutOfMemoryError actual = assertThrows(
                    OutOfMemoryError.class,
                    () -> DefaultSession.openTransactionally(
                            process,
                            Duration.ofSeconds(1),
                            ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                            StandardCharsets.UTF_8,
                            diagnostics(),
                            () -> {},
                            closeDispatcher,
                            resourcePublisher,
                            exitPublisher,
                            starter,
                            point -> {
                                if (point == failedPoint) {
                                    throw expected;
                                }
                            }));

            assertSame(expected, actual, failedPoint.toString());
            assertTrue(process.destroyed.await(1, TimeUnit.SECONDS), failedPoint.toString());
            assertTrue(eventually(() -> closeDispatcher.outstandingCount() == 0
                    && resourcePublisher.ownerCount() == 0
                    && exitPublisher.ownerCount() == 0));
            assertEquals(process.stdinGets.get(), process.stdin.closeCalls.get(), failedPoint.toString());
            assertEquals(process.stdoutGets.get(), process.stdout.closeCalls.get(), failedPoint.toString());
            assertEquals(process.stderrGets.get(), process.stderr.closeCalls.get(), failedPoint.toString());
            for (Thread started : startedThreads) {
                started.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(started.isAlive(), "aborted watcher at " + failedPoint);
            }
            assertEquals(0, process.waitCalls.get(), failedPoint.toString());
        }
    }

    @Test
    void constructionLedgerContinuesAfterEveryReleaseAndCleanupFailure() throws Exception {
        AssertionError primary = new AssertionError("session construction failed");
        OutOfMemoryError publicationReleaseFailure = new OutOfMemoryError("exit publication release failed");
        IllegalStateException reservationReleaseFailure = new IllegalStateException("exit reservation release failed");
        OutOfMemoryError processCleanupFailure = new OutOfMemoryError("process cleanup failed");
        IllegalArgumentException resourceRollbackFailure =
                new IllegalArgumentException("process resources rollback failed");
        TrackingProcess process = new TrackingProcess();
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(3, 3, 6);
        BoundedLifecyclePublisher resourcePublisher = new BoundedLifecyclePublisher(3);
        BoundedLifecyclePublisher exitPublisher = new BoundedLifecyclePublisher(1);
        DefaultSession.ConstructionRollback rollback = new DefaultSession.ConstructionRollback() {
            @Override
            public void release(BoundedLifecyclePublisher.Permit publication) {
                DefaultSession.ConstructionRollback.super.release(publication);
                throw publicationReleaseFailure;
            }

            @Override
            public void release(BoundedLifecyclePublisher.Reservation reservation) {
                DefaultSession.ConstructionRollback.super.release(reservation);
                throw reservationReleaseFailure;
            }

            @Override
            public void cleanupProcess(Process target) {
                DefaultSession.ConstructionRollback.super.cleanupProcess(target);
                throw processCleanupFailure;
            }

            @Override
            public void rollback(ProcessIoResources resources, Throwable primaryFailure) {
                DefaultSession.ConstructionRollback.super.rollback(resources, primaryFailure);
                throw resourceRollbackFailure;
            }
        };

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> DefaultSession.openTransactionally(
                        process,
                        Duration.ofSeconds(1),
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        StandardCharsets.UTF_8,
                        diagnostics(),
                        () -> {},
                        closeDispatcher,
                        resourcePublisher,
                        exitPublisher,
                        Threading::start,
                        point -> {
                            if (point == DefaultSession.ConstructionPoint.AFTER_EXIT_PUBLICATION_TRANSFER) {
                                throw primary;
                            }
                        },
                        rollback));

        assertSame(primary, actual);
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
        assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
        assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
        assertTrue(process.stderr.closed.await(1, TimeUnit.SECONDS));
        assertEquals(1, process.stdin.closeCalls.get());
        assertEquals(1, process.stdout.closeCalls.get());
        assertEquals(1, process.stderr.closeCalls.get());
        assertTrue(eventually(() -> closeDispatcher.outstandingCount() == 0
                && resourcePublisher.ownerCount() == 0
                && exitPublisher.ownerCount() == 0));
        assertSuppressedInOrder(
                primary,
                publicationReleaseFailure,
                reservationReleaseFailure,
                processCleanupFailure,
                resourceRollbackFailure);
    }

    private static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "construction-test", CommandEcho.empty());
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    private static void assertSuppressedInOrder(Throwable primary, Throwable... expected) {
        Throwable[] actual = primary.getSuppressed();
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertSame(expected[index], actual[index], "suppressed failure " + index);
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

    private static final class TrackingProcess extends Process {

        private final TrackingOutputStream stdin = new TrackingOutputStream();
        private final TrackingInputStream stdout = new TrackingInputStream();
        private final TrackingInputStream stderr = new TrackingInputStream();
        private final AtomicInteger stdinGets = new AtomicInteger();
        private final AtomicInteger stdoutGets = new AtomicInteger();
        private final AtomicInteger stderrGets = new AtomicInteger();
        private final AtomicInteger waitCalls = new AtomicInteger();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountDownLatch destroyed = new CountDownLatch(1);

        @Override
        public OutputStream getOutputStream() {
            stdinGets.incrementAndGet();
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            stdoutGets.incrementAndGet();
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrGets.incrementAndGet();
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            waitCalls.incrementAndGet();
            destroyed.await();
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitCalls.incrementAndGet();
            return destroyed.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            alive.set(false);
            destroyed.countDown();
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
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class TrackingOutputStream extends OutputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }
    }
}

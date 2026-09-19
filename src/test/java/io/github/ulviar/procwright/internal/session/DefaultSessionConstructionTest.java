/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.dispatch;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.CloseTrackingInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.startCoordinator;
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
import io.github.ulviar.procwright.internal.Threading;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionConstructionTest {

    @Test
    void validationFailureAfterProcessHandoffStopsProcessBeforeStreamAcquisition() throws Exception {
        TrackingProcess process = new TrackingProcess();

        assertThrows(
                IllegalArgumentException.class,
                () -> SessionTestFixtures.open(
                        process,
                        Duration.ofNanos(-1),
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        StandardCharsets.UTF_8));

        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
        assertEquals(0, process.stdinGets.get());
        assertEquals(0, process.stdoutGets.get());
        assertEquals(0, process.stderrGets.get());
    }

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
                                    new BoundedCloseDispatcher(3, 3),
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
                            new BoundedCloseDispatcher(3, 3),
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
                        new BoundedCloseDispatcher(3, 3),
                        starter));

        assertSame(expected, actual);
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertEquals(2, watcherBodies.get(), "both guards may run, but neither guarded watcher body may execute");
        assertEquals(0, process.waitCalls.get());
    }

    @Test
    void constructionRollbackReturnsWhilePhysicalOutputCloseIsBlocked() throws Exception {
        CountDownLatch releaseClose = new CountDownLatch(1);
        TrackingInputStream blockingStdout = new TrackingInputStream(releaseClose);
        TrackingProcess process = new TrackingProcess(blockingStdout, new TrackingInputStream());
        IllegalStateException expected = new IllegalStateException("before commit");

        CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
            try {
                DefaultSession.openTransactionally(
                        process,
                        Duration.ZERO,
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        StandardCharsets.UTF_8,
                        diagnostics(),
                        () -> {
                            throw expected;
                        },
                        new BoundedCloseDispatcher(3, 3),
                        DefaultSession.WatcherStarter.threading());
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });

        try {
            assertSame(expected, outcome.get(1, TimeUnit.SECONDS));
            assertTrue(blockingStdout.closed.await(1, TimeUnit.SECONDS));
            assertEquals(1, blockingStdout.closeCalls.get());
        } finally {
            releaseClose.countDown();
        }
        assertTrue(blockingStdout.closeFinished.await(1, TimeUnit.SECONDS));
    }

    @Test
    void helperTransactionRollsBackWhenTheFactoryDoesNotClaimOutput() throws Exception {
        TrackingProcess process = new TrackingProcess();

        assertThrows(
                IllegalStateException.class,
                () -> DefaultSession.openHelperTransactionally(
                        process,
                        Duration.ZERO,
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        StandardCharsets.UTF_8,
                        diagnostics(),
                        SessionOutputMode.LINE,
                        session -> UnclaimedHandle.INSTANCE));

        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
        assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
        assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
        assertTrue(process.stderr.closed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void helperTransactionRollsBackWhenClaimedPumpsWereNotStarted() throws Exception {
        TrackingProcess process = new TrackingProcess();

        assertThrows(
                IllegalStateException.class,
                () -> DefaultSession.openHelperTransactionally(
                        process,
                        Duration.ZERO,
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        StandardCharsets.UTF_8,
                        diagnostics(),
                        SessionOutputMode.LINE,
                        session -> new UnstartedHandle(new OutputPumpCoordinator(session, SessionOutputMode.LINE))));

        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void helperTransactionRollsBackAClaimForTheWrongMode() throws Exception {
        TrackingProcess process = new TrackingProcess();

        assertThrows(
                IllegalStateException.class,
                () -> DefaultSession.openHelperTransactionally(
                        process,
                        Duration.ZERO,
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        StandardCharsets.UTF_8,
                        diagnostics(),
                        SessionOutputMode.LINE,
                        session -> {
                            new OutputPumpCoordinator(session, SessionOutputMode.PROTOCOL);
                            return UnclaimedHandle.INSTANCE;
                        }));

        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void saturatedCloseCapacityDoesNotRejectSessionConstruction() throws Exception {
        CountDownLatch acceptedClosesSettled = new CountDownLatch(3);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, (prefix, task) -> {
            Threading.start(prefix, () -> {
                try {
                    task.run();
                } finally {
                    acceptedClosesSettled.countDown();
                }
            });
        });
        CountDownLatch occupyingCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseOccupyingClose = new CountDownLatch(1);
        CountDownLatch pendingClosesFinished = new CountDownLatch(2);
        dispatch(
                closeDispatcher,
                () -> {
                    occupyingCloseStarted.countDown();
                    awaitUninterruptibly(releaseOccupyingClose);
                },
                "procwright-occupying-output-close-",
                failure -> {});
        assertTrue(occupyingCloseStarted.await(1, TimeUnit.SECONDS));
        dispatch(closeDispatcher, pendingClosesFinished::countDown, "procwright-pending-output-close-", failure -> {});
        dispatch(closeDispatcher, pendingClosesFinished::countDown, "procwright-pending-output-close-", failure -> {});

        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        try {
            OutputPumpTestFixtures.CoordinatorHarness harness = startCoordinator(
                    process,
                    closeDispatcher,
                    SessionOutputMode.LINE,
                    PumpStarter.threading(),
                    "procwright-saturated-stdout-",
                    stream -> {},
                    "procwright-saturated-stderr-",
                    stream -> {});

            harness.coordinator().closeSession();
            assertTrue(process.awaitDestroyed());
            assertEquals(0, stdout.closeCalls());
            assertEquals(0, stderr.closeCalls());

            releaseOccupyingClose.countDown();
            assertTrue(pendingClosesFinished.await(1, TimeUnit.SECONDS), "previously accepted work must drain");
            assertTrue(
                    acceptedClosesSettled.await(1, TimeUnit.SECONDS),
                    "accepted close tasks must finish and release capacity");
            assertEquals(0, closeDispatcher.outstandingCount());
        } finally {
            releaseOccupyingClose.countDown();
        }
    }

    private static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "construction-test", CommandEcho.empty());
    }

    private enum UnclaimedHandle {
        INSTANCE
    }

    private record UnstartedHandle(OutputPumpCoordinator outputPumps) {}

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    private static final class TrackingProcess extends Process {

        private final TrackingOutputStream stdin = new TrackingOutputStream();
        private final TrackingInputStream stdout;
        private final TrackingInputStream stderr;
        private final AtomicInteger stdinGets = new AtomicInteger();
        private final AtomicInteger stdoutGets = new AtomicInteger();
        private final AtomicInteger stderrGets = new AtomicInteger();
        private final AtomicInteger waitCalls = new AtomicInteger();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountDownLatch destroyed = new CountDownLatch(1);

        private TrackingProcess() {
            this(new TrackingInputStream(), new TrackingInputStream());
        }

        private TrackingProcess(TrackingInputStream stdout, TrackingInputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

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
        private final CountDownLatch closeFinished = new CountDownLatch(1);
        private final CountDownLatch releaseClose;

        private TrackingInputStream() {
            this(null);
        }

        private TrackingInputStream(CountDownLatch releaseClose) {
            this.releaseClose = releaseClose;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
            if (releaseClose != null) {
                awaitUninterruptibly(releaseClose);
            }
            closeFinished.countDown();
        }
    }
}

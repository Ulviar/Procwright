/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.BlockingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.CloseTrackingInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.FailingPumpStarter;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.awaitSettlement;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.diagnostics;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class OutputPumpStartupTransactionTest {

    @Test
    void failedOwnershipClaimLeavesInFlightPublicReadAndRawSessionUntouchedForEveryHelper() throws Exception {
        for (HelperKind helper : HelperKind.values()) {
            BlockingPublicReadInputStream stdout = new BlockingPublicReadInputStream();
            CloseTrackingInputStream stderr = new CloseTrackingInputStream();
            ControllableProcess process = new ControllableProcess(stdout, stderr);
            DefaultSession rawSession = session(process);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            AtomicInteger pumpStarts = new AtomicInteger();
            Future<Integer> publicRead = null;
            try {
                InputStream publicStdout = rawSession.stdout();
                publicRead = executor.submit(() -> publicStdout.read());
                assertTrue(stdout.awaitReadStarted(), helper + " public read must enter the raw stream");

                PumpStarter mustNotStart = (name, task) -> {
                    pumpStarts.incrementAndGet();
                    throw new AssertionError("pump must not start before output ownership is acquired");
                };
                Throwable failure = captureFailure(() -> construct(helper, rawSession, mustNotStart));

                assertTrue(
                        failure instanceof IllegalStateException,
                        () -> helper + " must preserve the ownership failure, but got " + failure);
                assertEquals(0, pumpStarts.get(), helper + " must not start a pump after a failed claim");
                assertTrue(process.isAlive(), helper + " must not close the raw session after a failed claim");
                assertFalse(rawSession.onExit().isDone(), helper + " must leave session exit incomplete");
                assertEquals(0, stdout.closeCalls(), helper + " must not close in-flight public stdout");
                assertEquals(0, stderr.closeCalls(), helper + " must not close raw stderr");

                stdout.releaseEof();
                assertEquals(-1, publicRead.get(1, TimeUnit.SECONDS));
                assertEquals(0, stdout.closeCalls(), helper + " public read must finish without forced close");
            } finally {
                stdout.releaseEof();
                try {
                    rawSession.close();
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void helperPumpStartupRollsBackBothOwnedStreamsForEveryOrdinalAndFailureKind() throws Exception {
        for (HelperKind helper : HelperKind.values()) {
            for (int failingOrdinal : List.of(1, 2)) {
                for (Throwable startupFailure : List.of(
                        new IllegalStateException(helper + " pump " + failingOrdinal + " failed"),
                        new AssertionError(helper + " pump " + failingOrdinal + " failed"))) {
                    CloseTrackingInputStream stdout = new CloseTrackingInputStream();
                    CloseTrackingInputStream stderr = new CloseTrackingInputStream();
                    ControllableProcess process = new ControllableProcess(stdout, stderr);
                    BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
                    DefaultSession rawSession = session(process, dispatcher);
                    FailingPumpStarter starter = new FailingPumpStarter(failingOrdinal, startupFailure);
                    try {
                        Throwable thrown = captureFailure(() -> construct(helper, rawSession, starter));

                        assertSame(startupFailure, thrown);
                        rawSession.onExit().get(1, TimeUnit.SECONDS);
                        assertFalse(process.isAlive());
                        assertTrue(stdout.awaitClose(), helper + " stdout close was not attempted");
                        assertTrue(stderr.awaitClose(), helper + " stderr close was not attempted");
                        assertEquals(1, stdout.closeCalls(), helper + " must close stdout exactly once");
                        assertEquals(1, stderr.closeCalls(), helper + " must close stderr exactly once");
                        assertTrue(starter.awaitStartedThreadsStopped(), helper + " must terminate a started pump");
                        assertEquals(0, stdout.reads(), "a pump must not consume output before startup commits");
                        assertEquals(0, stderr.reads(), "a pump must not consume output before startup commits");
                        assertEquals(0, dispatcher.activeCount());
                        assertEquals(0, dispatcher.pendingCount());
                        assertEquals(0, dispatcher.outstandingCount());
                    } finally {
                        rawSession.close();
                    }
                }
            }
        }
    }

    @Test
    void unreservedConstructionRollbackClosesAndSettlesBothOutputs() throws Exception {
        for (boolean closeStarterFails : new boolean[] {false, true}) {
            IllegalStateException startFailure = new IllegalStateException("output close starter failed");
            BoundedCloseDispatcher dispatcher = closeStarterFails
                    ? new BoundedCloseDispatcher(2, 2, (name, task) -> {
                        throw startFailure;
                    })
                    : new BoundedCloseDispatcher(2, 2);
            CloseTrackingInputStream stdout = new CloseTrackingInputStream();
            CloseTrackingInputStream stderr = new CloseTrackingInputStream();
            ControllableProcess process = new ControllableProcess(stdout, stderr);
            DefaultSession rawSession = session(process, dispatcher);
            OutputPumpCleanup cleanup = new OutputPumpCleanup(
                    rawSession, "unreserved-rollback", OutputPumpCoordinator.FailureAttribution.PUMP_COMPLETION);
            CountDownLatch cleanupCompleted = new CountDownLatch(1);
            AssertionError primary = new AssertionError("construction failed");
            try {
                cleanup.installHelperCleanup(rawSession.registerHelperCleanup());
                rawSession.claimOutputOwner("unreserved-rollback");
                cleanup.observeProcessCleanup();
                cleanup.publishAfterOutputCleanup(cleanupCompleted::countDown);
                cleanup.retainPrimaryPreserving(primary);
                cleanup.pumpTaskFinished();
                cleanup.pumpTaskFinished();

                cleanup.closeSessionPreserving(primary);
                assertFalse(cleanup.hasCloseReservation());
                cleanup.dispatchUnreservedOutputClosePreserving(primary);

                assertTrue(stdout.awaitClose());
                assertTrue(stderr.awaitClose());
                assertTrue(cleanupCompleted.await(1, TimeUnit.SECONDS));
                awaitSettlement(rawSession.onExit());
                assertEquals(1, stdout.closeCalls());
                assertEquals(1, stderr.closeCalls());
                assertEquals(0, primary.getSuppressed().length);
                assertEquals(0, dispatcher.outstandingCount());
            } finally {
                rawSession.close();
            }
        }
    }

    @Test
    void startThenThrowCompletesEachPumpSlotExactlyOnceForEveryOrdinalAndFailureKind() throws Exception {
        for (int failingOrdinal : List.of(1, 2)) {
            for (Throwable startupFailure : List.of(
                    new IllegalStateException("pump " + failingOrdinal + " start-then-throw"),
                    new AssertionError("pump " + failingOrdinal + " start-then-throw"))) {
                CloseTrackingInputStream stdout = new CloseTrackingInputStream();
                CloseTrackingInputStream stderr = new CloseTrackingInputStream();
                ControllableProcess process = new ControllableProcess(stdout, stderr);
                DefaultSession rawSession = session(process);
                StartThenThrowPumpStarter starter = new StartThenThrowPumpStarter(failingOrdinal, startupFailure);
                try {
                    Throwable thrown = captureFailure(() -> new DefaultStreamSession(
                            rawSession,
                            streamPlan(),
                            diagnostics(),
                            StreamSessionTestDependencies.withPumpStarter(starter)));

                    assertSame(startupFailure, thrown);
                    rawSession.onExit().get(1, TimeUnit.SECONDS);
                    assertTrue(starter.awaitStartedThreadsStopped());
                    assertTrue(stdout.awaitClose());
                    assertTrue(stderr.awaitClose());
                    assertEquals(1, stdout.closeCalls());
                    assertEquals(1, stderr.closeCalls());
                    assertEquals(0, stdout.reads());
                    assertEquals(0, stderr.reads());
                    assertTrue(
                            starter.uncaughtFailures().isEmpty(),
                            () -> "secondary failures: " + starter.uncaughtFailures());
                } finally {
                    rawSession.close();
                }
            }
        }
    }

    @Test
    void processExitBetweenPumpStartsClosesBothStreamsAfterStartupCommit() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, dispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "startup-exit-race");
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger taskRuns = new AtomicInteger();
        CountDownLatch processExitObserved = new CountDownLatch(1);
        rawSession.observeExit((ignored, failure) -> processExitObserved.countDown());
        PumpStarter starter = (name, task) -> {
            int ordinal = starts.incrementAndGet();
            Thread thread = Threading.start(name, task);
            if (ordinal == 1) {
                process.destroy();
                awaitUninterruptibly(processExitObserved);
                assertEquals(0, taskRuns.get(), "the first pump ran before startup committed");
            } else {
                assertEquals(0, taskRuns.get(), "a pump ran before the second start returned");
            }
            return thread;
        };
        try {
            coordinator.start(
                    starter,
                    "procwright-startup-exit-race-stdout-",
                    stream -> {
                        taskRuns.incrementAndGet();
                        closePumpStream(stream);
                    },
                    "procwright-startup-exit-race-stderr-",
                    stream -> {
                        taskRuns.incrementAndGet();
                        closePumpStream(stream);
                    },
                    () -> {});

            rawSession.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(2, starts.get());
            assertEquals(2, taskRuns.get());
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
            assertEquals(0, dispatcher.activeCount());
            assertEquals(0, dispatcher.pendingCount());
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            coordinator.closeSession();
            rawSession.close();
        }
    }

    @Test
    void startupRollbackStopsProcessBeforeBlockingOutputClosesAndDoesNotWaitForThem() throws Exception {
        for (HelperKind helper : HelperKind.values()) {
            for (int failingOrdinal : List.of(1, 2)) {
                IllegalStateException startupFailure =
                        new IllegalStateException(helper + " pump " + failingOrdinal + " failed");
                AtomicBoolean processAlive = new AtomicBoolean(true);
                BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
                BlockingCloseInputStream stderr = new BlockingCloseInputStream(processAlive);
                ControllableProcess process = new ControllableProcess(stdout, stderr, processAlive);
                DefaultSession rawSession = session(process);
                FailingPumpStarter starter = new FailingPumpStarter(failingOrdinal, startupFailure);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<Throwable> construction = null;
                try {
                    construction = executor.submit(() -> captureFailure(() -> construct(helper, rawSession, starter)));

                    assertTrue(process.awaitDestroyed(), helper + " must stop the process before output close");
                    assertSame(startupFailure, construction.get(1, TimeUnit.SECONDS));
                    assertTrue(stdout.awaitCloseStarted(), helper + " stdout close was not dispatched");
                    assertTrue(stderr.awaitCloseStarted(), helper + " stderr close was not dispatched");
                    assertTrue(stdout.destroyedBeforeClose(), helper + " stdout closed before process cleanup");
                    assertTrue(stderr.destroyedBeforeClose(), helper + " stderr closed before process cleanup");
                    assertFalse(stdout.closeCompleted());
                    assertFalse(stderr.closeCompleted());
                    assertTrue(starter.awaitStartedThreadsStopped());
                } finally {
                    stdout.releaseClose();
                    stderr.releaseClose();
                    if (construction != null) {
                        construction.get(1, TimeUnit.SECONDS);
                    }
                    try {
                        rawSession.close();
                    } finally {
                        executor.shutdownNow();
                        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                    }
                }

                assertTrue(stdout.awaitCloseCompleted());
                assertTrue(stderr.awaitCloseCompleted());
                assertEquals(1, stdout.closeCalls());
                assertEquals(1, stderr.closeCalls());
            }
        }
    }

    private static void construct(HelperKind helper, DefaultSession session, PumpStarter starter) {
        ZeroReadBackoff backoff = ZeroReadBackoff.exponential();
        switch (helper) {
            case EXPECT -> new DefaultExpect(session, ExpectSettings.defaults(), backoff, starter);
            case LINE ->
                new DefaultLineSession(
                        session,
                        LineSessionSettings.defaults(),
                        LineSessionTestDependencies.withBackoffAndPumpStarter(backoff, starter));
            case PROTOCOL ->
                new DefaultProtocolSession<>(
                        session,
                        noOpAdapter(),
                        ProtocolSessionSettings.defaults(),
                        ProtocolSessionTestDependencies.withBackoffAndPumpStarter(backoff, starter));
            case STREAM ->
                new DefaultStreamSession(
                        session,
                        streamPlan(),
                        diagnostics(),
                        StreamSessionTestDependencies.withBackoffAndPumpStarter(backoff, starter));
        }
    }

    private static ProtocolAdapter<String, String> noOpAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
    }

    private static StreamExecutionPlan streamPlan() {
        LaunchPlan launchPlan = new LaunchPlan(
                List.of("stub"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.DISABLED);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                PtyProvider.unavailable(),
                TerminalSize.defaults());
        return new StreamExecutionPlan(sessionPlan, Duration.ZERO, 64, chunk -> {}, DiagnosticsSettings.disabled());
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void closePumpStream(InputStream stream) {
        try {
            stream.close();
        } catch (IOException failure) {
            throw new IllegalStateException("test pump close failed", failure);
        }
    }

    private enum HelperKind {
        EXPECT,
        LINE,
        PROTOCOL,
        STREAM
    }

    private static final class StartThenThrowPumpStarter implements PumpStarter {

        final int failingOrdinal;
        final Throwable failure;
        final AtomicInteger starts = new AtomicInteger();
        final List<Thread> startedThreads = new ArrayList<>();
        final List<Throwable> uncaughtFailures = new CopyOnWriteArrayList<>();

        StartThenThrowPumpStarter(int failingOrdinal, Throwable failure) {
            this.failingOrdinal = failingOrdinal;
            this.failure = failure;
        }

        @Override
        public Thread start(String namePrefix, Runnable task) {
            int ordinal = starts.incrementAndGet();
            Thread thread = new Thread(task, namePrefix + ordinal);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, uncaught) -> uncaughtFailures.add(uncaught));
            thread.start();
            startedThreads.add(thread);
            if (ordinal == failingOrdinal) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            return thread;
        }

        boolean awaitStartedThreadsStopped() throws InterruptedException {
            for (Thread thread : startedThreads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }

        List<Throwable> uncaughtFailures() {
            return List.copyOf(uncaughtFailures);
        }
    }

    private static final class BlockingPublicReadInputStream extends InputStream {

        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for test input", exception);
            }
            if (closed.get()) {
                throw new IOException("Stream closed");
            }
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            return read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.set(true);
            release.countDown();
        }

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        void releaseEof() {
            release.countDown();
        }

        int closeCalls() {
            return closes.get();
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.BlockingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.CloseTrackingInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.FailingPumpStarter;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.diagnostics;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.startCoordinator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.Threading;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class OutputPumpStartupTransactionTest {

    @Test
    void lineHelperRollsBackBothOwnedStreamsWhenItsSecondPumpCannotStart() throws Exception {
        IllegalStateException startupFailure = new IllegalStateException("line stderr pump failed");
        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        FailingPumpStarter starter = new FailingPumpStarter(2, startupFailure);

        Throwable thrown = captureFailure(() -> openLine(process, dispatcher, starter));

        assertSame(startupFailure, thrown);
        assertFalse(process.isAlive());
        assertTrue(stdout.awaitClose(), "stdout close was not attempted");
        assertTrue(stderr.awaitClose(), "stderr close was not attempted");
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
        assertTrue(starter.awaitStartedThreadsStopped(), "the started pump must stop");
        assertEquals(0, stdout.reads(), "a pump must not consume output before startup commits");
        assertEquals(0, stderr.reads(), "a pump must not consume output before startup commits");
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void starterThatStartsThenThrowsStillSettlesEachPumpSlotExactlyOnce() throws Exception {
        AssertionError startupFailure = new AssertionError("stderr pump start-then-throw");
        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        StartThenThrowPumpStarter starter = new StartThenThrowPumpStarter(2, startupFailure);

        Throwable thrown = captureFailure(() -> SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                SessionOutputMode.STREAM,
                session -> new DefaultStreamSession(
                        session, streamPlan(), diagnostics(), StreamSessionTestDependencies.withPumpStarter(starter))));

        assertSame(startupFailure, thrown);
        assertTrue(starter.awaitStartedThreadsStopped());
        assertTrue(stdout.awaitClose());
        assertTrue(stderr.awaitClose());
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
        assertEquals(0, stdout.reads());
        assertEquals(0, stderr.reads());
        assertTrue(starter.uncaughtFailures().isEmpty(), () -> "secondary failures: " + starter.uncaughtFailures());
    }

    @Test
    void processExitBetweenPumpStartsClosesBothStreamsAfterStartupCommit() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger taskRuns = new AtomicInteger();
        PumpStarter starter = (name, task) -> {
            int ordinal = starts.incrementAndGet();
            Thread thread = Threading.start(name, task);
            if (ordinal == 1) {
                process.destroy();
                assertEquals(0, taskRuns.get(), "the first pump ran before startup committed");
            } else {
                assertEquals(0, taskRuns.get(), "a pump ran before the second start returned");
            }
            return thread;
        };
        OutputPumpTestFixtures.CoordinatorHarness harness = startCoordinator(
                process,
                dispatcher,
                SessionOutputMode.LINE,
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
                });
        DefaultSession rawSession = harness.session();
        OutputPumpCoordinator coordinator = harness.coordinator();
        try {
            rawSession.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(2, starts.get());
            assertEquals(2, taskRuns.get());
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
            assertTrue(eventually(() -> dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0));
        } finally {
            coordinator.closeSession();
            rawSession.close();
        }
    }

    @Test
    void startupRollbackStopsProcessBeforeBlockingOutputClosesAndDoesNotWaitForThem() throws Exception {
        IllegalStateException startupFailure = new IllegalStateException("line stderr pump failed");
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        BlockingCloseInputStream stderr = new BlockingCloseInputStream(processAlive);
        ControllableProcess process = new ControllableProcess(stdout, stderr, processAlive);
        FailingPumpStarter starter = new FailingPumpStarter(2, startupFailure);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> construction = null;
        try {
            construction = executor.submit(
                    () -> captureFailure(() -> openLine(process, new BoundedCloseDispatcher(2, 2), starter)));

            assertTrue(process.awaitDestroyed(), "startup rollback must stop the process before output close");
            assertSame(startupFailure, construction.get(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitCloseStarted(), "stdout close was not dispatched");
            assertTrue(stderr.awaitCloseStarted(), "stderr close was not dispatched");
            assertTrue(stdout.destroyedBeforeClose(), "stdout closed before process cleanup");
            assertTrue(stderr.destroyedBeforeClose(), "stderr closed before process cleanup");
            assertFalse(stdout.closeCompleted());
            assertFalse(stderr.closeCompleted());
            assertTrue(starter.awaitStartedThreadsStopped());
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            if (construction != null) {
                construction.get(1, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(stdout.awaitCloseCompleted());
        assertTrue(stderr.awaitCloseCompleted());
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
    }

    private static DefaultLineSession openLine(
            Process process, BoundedCloseDispatcher dispatcher, PumpStarter starter) {
        ZeroReadBackoff backoff = ZeroReadBackoff.exponential();
        return SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                SessionOutputMode.LINE,
                session -> new DefaultLineSession(
                        session,
                        LineSessionSettings.defaults(),
                        LineSessionTestDependencies.withBackoffAndPumpStarter(backoff, starter)),
                dispatcher,
                Threading::start);
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
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LaunchMode;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class TransactionalPumpStartupTest {

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
                    DefaultSession rawSession = session(process);
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
                    } finally {
                        rawSession.close();
                    }
                }
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
                            rawSession, streamPlan(), diagnostics(), ZeroReadBackoff.exponential(), starter));

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
    void everyCoordinatorConstructionFailureRollsBackItsBarrierAndTransferredOutputOwnership() throws Exception {
        for (OutputPumpCoordinator.ConstructionPoint failedPoint : OutputPumpCoordinator.ConstructionPoint.values()) {
            CloseTrackingInputStream stdout = new CloseTrackingInputStream();
            CloseTrackingInputStream stderr = new CloseTrackingInputStream();
            ControllableProcess process = new ControllableProcess(stdout, stderr);
            DefaultSession rawSession = session(process);
            OutOfMemoryError expected = new OutOfMemoryError("injected at " + failedPoint);
            AtomicInteger pumpStarts = new AtomicInteger();
            OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "construction-probe", point -> {
                if (point == failedPoint) {
                    throw expected;
                }
            });
            try {
                OutOfMemoryError actual = assertThrows(
                        OutOfMemoryError.class,
                        () -> coordinator.start(
                                (name, task) -> {
                                    pumpStarts.incrementAndGet();
                                    throw new AssertionError("construction probe must fail before pump start");
                                },
                                "procwright-construction-probe-stdout-",
                                stream -> {},
                                "procwright-construction-probe-stderr-",
                                stream -> {},
                                () -> {}));

                assertSame(expected, actual, failedPoint.toString());
                assertEquals(0, pumpStarts.get(), failedPoint.toString());
                if (failedPoint == OutputPumpCoordinator.ConstructionPoint.BEFORE_HELPER_REGISTRATION
                        || failedPoint == OutputPumpCoordinator.ConstructionPoint.AFTER_HELPER_REGISTRATION) {
                    assertTrue(process.isAlive(), failedPoint.toString());
                    assertEquals(0, stdout.closeCalls(), failedPoint.toString());
                    assertEquals(0, stderr.closeCalls(), failedPoint.toString());
                    rawSession.close();
                }
                awaitSettlement(rawSession.physicalOutputCleanup());
                awaitSettlement(rawSession.onExit());
                assertTrue(rawSession.onExit().isDone(), failedPoint.toString());
                assertTrue(stdout.awaitClose(), failedPoint.toString());
                assertTrue(stderr.awaitClose(), failedPoint.toString());
                assertEquals(1, stdout.closeCalls(), failedPoint.toString());
                assertEquals(1, stderr.closeCalls(), failedPoint.toString());
            } finally {
                rawSession.close();
            }
        }
    }

    @Test
    void faultyHelperRegistrationRollbackCannotRetainThePublicExitBarrier() throws Exception {
        OutOfMemoryError primary = new OutOfMemoryError("failed after helper registration");
        IllegalStateException registrationFailure = new IllegalStateException("helper registration rollback failed");
        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        OutputPumpCoordinator.ConstructionRollback rollback = new OutputPumpCoordinator.ConstructionRollback() {
            @Override
            public void rollback(DefaultSession.HelperCleanupRegistration registration) {
                OutputPumpCoordinator.ConstructionRollback.super.rollback(registration);
                throw registrationFailure;
            }
        };
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(
                rawSession,
                "registration-rollback",
                point -> {
                    if (point == OutputPumpCoordinator.ConstructionPoint.AFTER_HELPER_REGISTRATION) {
                        throw primary;
                    }
                },
                rollback);
        try {
            OutOfMemoryError actual = assertThrows(
                    OutOfMemoryError.class,
                    () -> coordinator.start(
                            (name, task) -> {
                                throw new AssertionError("pump must not start");
                            },
                            "procwright-registration-rollback-stdout-",
                            stream -> {},
                            "procwright-registration-rollback-stderr-",
                            stream -> {},
                            () -> {}));

            assertSame(primary, actual);
            assertSuppressedInOrder(primary, registrationFailure);
            assertTrue(process.isAlive());
            rawSession.close();
            awaitSettlement(rawSession.physicalOutputCleanup());
            awaitSettlement(rawSession.onExit());
            assertTrue(rawSession.onExit().isDone());
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            rawSession.close();
        }
    }

    @Test
    void ownershipRollbackContinuesAfterAbortAndSessionCleanupFailures() throws Exception {
        AssertionError primary = new AssertionError("failed after output close reservation");
        OutOfMemoryError abortFailure = new OutOfMemoryError("helper abort failed");
        IllegalStateException sessionCleanupFailure = new IllegalStateException("session cleanup failed");
        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        OutputPumpCoordinator.ConstructionRollback rollback = new OutputPumpCoordinator.ConstructionRollback() {
            @Override
            public void closeSession(OutputPumpCoordinator coordinator, Throwable primaryFailure) {
                OutputPumpCoordinator.ConstructionRollback.super.closeSession(coordinator, primaryFailure);
                throw sessionCleanupFailure;
            }
        };
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(
                rawSession,
                "ownership-rollback",
                point -> {
                    if (point == OutputPumpCoordinator.ConstructionPoint.AFTER_OUTPUT_CLOSE_RESERVATION) {
                        throw primary;
                    }
                },
                rollback);
        try {
            AssertionError actual = assertThrows(
                    AssertionError.class,
                    () -> coordinator.start(
                            (name, task) -> {
                                throw new AssertionError("pump must not start");
                            },
                            "procwright-ownership-rollback-stdout-",
                            stream -> {},
                            "procwright-ownership-rollback-stderr-",
                            stream -> {},
                            () -> {
                                throw abortFailure;
                            }));

            assertSame(primary, actual);
            awaitSettlement(rawSession.physicalOutputCleanup());
            awaitSettlement(rawSession.onExit());
            assertTrue(rawSession.onExit().isDone());
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
            assertSuppressedInOrder(primary, abortFailure, sessionCleanupFailure);
        } finally {
            coordinator.closeSessionPreserving(primary);
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

    @Test
    void startupFailureRemainsPrimaryWhenBothOwnedStreamClosesFail() throws Exception {
        IllegalStateException startupFailure = new IllegalStateException("second pump failed");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        FailingPumpStarter starter = new FailingPumpStarter(2, startupFailure);
        try {
            Throwable thrown = captureFailure(() ->
                    new DefaultExpect(rawSession, ExpectSettings.defaults(), ZeroReadBackoff.exponential(), starter));

            assertSame(startupFailure, thrown);
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertSuppressedOnce(startupFailure, stdoutCloseFailure);
            assertSuppressedOnce(startupFailure, stderrCloseFailure);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            rawSession.close();
        }
    }

    @Test
    void exactSessionFailureOwnsPhysicalCloseFailuresInsteadOfThePublicFutureWrapper() throws Exception {
        IllegalStateException sessionFailure = new IllegalStateException("session close failed");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        AtomicInteger uncaughtReports = new AtomicInteger();
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, 4, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> uncaughtReports.incrementAndGet());
            thread.start();
            return thread;
        });
        DefaultSession rawSession = session(process, closeDispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "exact-session");
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-exact-session-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-exact-session-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            process.failIsAliveOnCurrentThread(sessionFailure);
            IllegalStateException thrown = assertThrows(IllegalStateException.class, coordinator::closeSession);

            assertSame(sessionFailure, thrown);
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertSuppressedOnce(sessionFailure, stdoutCloseFailure);
            assertSuppressedOnce(sessionFailure, stderrCloseFailure);
            assertEquals(2, sessionFailure.getSuppressed().length);
            assertEquals(0, uncaughtReports.get());
        } finally {
            coordinator.closeSessionPreserving(sessionFailure);
            rawSession.close();
        }
    }

    @Test
    void authoritativePrimaryRegisteredBeforePhysicalCloseOwnsEveryCloseFailureOnce() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError workerFailure = new AssertionError("worker failed after close started");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, 4);
        DefaultSession rawSession = session(process, closeDispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "shutdown-race");
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-shutdown-race-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-shutdown-race-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            coordinator.closeSessionPreserving(workerFailure);
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());

            coordinator.closeSessionPreserving(workerFailure);

            assertSuppressedOnce(workerFailure, stdoutCloseFailure);
            assertSuppressedOnce(workerFailure, stderrCloseFailure);
            assertEquals(2, workerFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            coordinator.closeSessionPreserving(workerFailure);
            rawSession.close();
        }
    }

    @Test
    void reentrantUncaughtHandlerCannotRunUnderCoordinatorMonitor() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError workerFailure = new AssertionError("worker failure selected by handler");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        AtomicReference<OutputPumpCoordinator> coordinatorReference = new AtomicReference<>();
        List<Throwable> reportedFailures = new CopyOnWriteArrayList<>();
        AtomicInteger closeStarts = new AtomicInteger();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch handlerReturned = new CountDownLatch(1);
        CountDownLatch reportsCompleted = new CountDownLatch(2);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, 4, (name, task) -> {
            int ordinal = closeStarts.getAndIncrement();
            Thread thread = new Thread(task, name + ordinal);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                reportedFailures.add(failure);
                handlerEntered.countDown();
                coordinatorReference.get().closeSessionPreserving(workerFailure);
                handlerReturned.countDown();
                reportsCompleted.countDown();
            });
            thread.start();
            return thread;
        });
        DefaultSession rawSession = session(process, closeDispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "reentrant");
        coordinatorReference.set(coordinator);
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-reentrant-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-reentrant-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            coordinator.closeSession();

            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(handlerReturned.await(1, TimeUnit.SECONDS));
            assertTrue(reportsCompleted.await(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertEquals(
                    1,
                    reportedFailures.stream()
                            .filter(failure -> failure == stdoutCloseFailure)
                            .count());
            assertEquals(
                    1,
                    reportedFailures.stream()
                            .filter(failure -> failure == stderrCloseFailure)
                            .count());
            assertEquals(0, workerFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            coordinator.closeSessionPreserving(workerFailure);
            rawSession.close();
        }
    }

    @Test
    void closeFailuresAreNotReportedBeforeAStillRunningPumpCanSelectItsPrimary() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError workerFailure = new AssertionError("late worker failure");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, 4);
        DefaultSession rawSession = session(process, closeDispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "deferred-report");
        CountDownLatch stdoutPumpEntered = new CountDownLatch(1);
        CountDownLatch releaseStdoutPump = new CountDownLatch(1);
        CountDownLatch stdoutPumpDrained = new CountDownLatch(1);
        CountDownLatch latePrimarySelected = new CountDownLatch(1);
        CountDownLatch stderrPumpFinished = new CountDownLatch(1);
        AtomicInteger failureReportCount = new AtomicInteger();
        List<Thread> pumpThreads = new ArrayList<>();
        PumpStarter reportingStarter = (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> failureReportCount.incrementAndGet());
            pumpThreads.add(thread);
            thread.start();
            return thread;
        };
        try {
            coordinator.start(
                    reportingStarter,
                    "procwright-deferred-report-stdout-pump-",
                    stream -> {
                        stdoutPumpEntered.countDown();
                        awaitUninterruptibly(releaseStdoutPump);
                        drainToEof(stream, stdoutPumpDrained);
                        coordinator.closeSessionPreserving(workerFailure);
                        latePrimarySelected.countDown();
                    },
                    "procwright-deferred-report-stderr-pump-",
                    stream -> drainToEof(stream, stderrPumpFinished),
                    () -> {});
            assertTrue(stdoutPumpEntered.await(1, TimeUnit.SECONDS));
            assertTrue(stderrPumpFinished.await(1, TimeUnit.SECONDS));

            coordinator.closeSession();
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertEquals(0, failureReportCount.get(), "cleanup failures must wait for the remaining pump outcome");

            releaseStdoutPump.countDown();
            assertTrue(latePrimarySelected.await(1, TimeUnit.SECONDS));
            assertEquals(0, stdoutPumpDrained.getCount());
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }

            assertEquals(0, failureReportCount.get());
            assertSuppressedOnce(workerFailure, stdoutCloseFailure);
            assertSuppressedOnce(workerFailure, stderrCloseFailure);
        } finally {
            releaseStdoutPump.countDown();
            coordinator.closeSessionPreserving(workerFailure);
            rawSession.close();
        }
    }

    @Test
    void rawSessionExitWaitsForPumpCompletionAndFinalFailureAggregation() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError pumpFailure = new AssertionError("pump failed after physical cleanup");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2, 4));
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "raw-exit-barrier");
        CountDownLatch pumpEntered = new CountDownLatch(1);
        CountDownLatch releasePump = new CountDownLatch(1);
        CountDownLatch hostileContinuationEntered = new CountDownLatch(1);
        CountDownLatch releaseHostileContinuation = new CountDownLatch(1);
        CompletableFuture<?> hostileContinuation = rawSession.onExit().thenRun(() -> {
            hostileContinuationEntered.countDown();
            awaitUninterruptibly(releaseHostileContinuation);
        });
        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-raw-exit-barrier-stdout-pump-",
                    stream -> {
                        pumpEntered.countDown();
                        awaitUninterruptibly(releasePump);
                        coordinator.closeSessionPreserving(pumpFailure);
                    },
                    "procwright-raw-exit-barrier-stderr-pump-",
                    stream -> drainToEof(stream, new CountDownLatch(0)),
                    () -> {});
            assertTrue(pumpEntered.await(1, TimeUnit.SECONDS));

            coordinator.closeSession();
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            awaitSettlement(rawSession.physicalOutputCleanup());
            assertTrue(rawSession.physicalOutputCleanup().isDone());
            assertFalse(rawSession.onExit().isDone());

            releasePump.countDown();
            assertTrue(hostileContinuationEntered.await(1, TimeUnit.SECONDS));
            assertTrue(coordinator.outputCleanupCompleted());
            assertTrue(rawSession.physicalOutputCleanup().isDone());
            releaseHostileContinuation.countDown();
            hostileContinuation.get(1, TimeUnit.SECONDS);
            awaitSettlement(rawSession.onExit());

            assertSuppressedOnce(pumpFailure, stdoutCloseFailure);
            assertSuppressedOnce(pumpFailure, stderrCloseFailure);
        } finally {
            releasePump.countDown();
            releaseHostileContinuation.countDown();
            coordinator.closeSessionPreserving(pumpFailure);
            rawSession.close();
        }
    }

    @Test
    void latePrimaryInstalledAfterFailureFinalizationOwnsFuturePhysicalCloseFailures() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError latePrimary = new AssertionError("late terminal primary");
        GatedThrowingCloseInputStream stdout = new GatedThrowingCloseInputStream(stdoutCloseFailure);
        GatedThrowingCloseInputStream stderr = new GatedThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2, 4));
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "late-primary");
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        List<Thread> pumpThreads = new ArrayList<>();
        PumpStarter starter = (name, task) -> {
            Thread thread = Threading.start(name, task);
            pumpThreads.add(thread);
            return thread;
        };
        try {
            coordinator.start(
                    starter,
                    "procwright-late-primary-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-late-primary-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }

            coordinator.closeSession();
            assertTrue(stdout.awaitCloseStarted());
            assertTrue(stderr.awaitCloseStarted());
            assertTrue(rawSession.exitCompleted());
            assertFalse(rawSession.onExit().isDone());
            assertTrue(awaitCloseFailureFinalization(coordinator));

            coordinator.closeSessionPreserving(latePrimary);
            stdout.releaseClose();
            stderr.releaseClose();
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertTrue(awaitOutputCleanup(coordinator));
            rawSession.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);

            assertSuppressedOnce(latePrimary, stdoutCloseFailure);
            assertSuppressedOnce(latePrimary, stderrCloseFailure);
            assertEquals(2, latePrimary.getSuppressed().length);
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            coordinator.closeSessionPreserving(latePrimary);
            rawSession.close();
        }
    }

    @Test
    void pumpEofCannotPhysicallyCloseReservedOutputBeforeProcessCleanup() throws Exception {
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, 4);
        CloseTrackingInputStream stdout = new CloseTrackingInputStream(closeDispatcher);
        CloseTrackingInputStream stderr = new CloseTrackingInputStream(closeDispatcher);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, closeDispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "EOF-race");
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        List<Thread> pumpThreads = new ArrayList<>();
        PumpStarter trackingStarter = (namePrefix, task) -> {
            Thread thread = Threading.start(namePrefix, task);
            pumpThreads.add(thread);
            return thread;
        };

        try {
            coordinator.start(
                    trackingStarter,
                    "procwright-eof-race-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-eof-race-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});

            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS), "both pumps must observe EOF");
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive(), "EOF pump thread must terminate");
            }
            assertEquals(0, stdout.closeCalls(), "pump try-with must not own physical stdout close");
            assertEquals(0, stderr.closeCalls(), "pump try-with must not own physical stderr close");
            assertTrue(process.isAlive(), "EOF alone must not stop the process");

            coordinator.closeSession();
            rawSession.onExit().get(1, TimeUnit.SECONDS);

            assertTrue(stdout.awaitClose(), "dispatcher did not physically close stdout");
            assertTrue(stderr.awaitClose(), "dispatcher did not physically close stderr");
            assertTrue(stdout.closeThreadName().startsWith("procwright-eof-race-stdout-close-"));
            assertTrue(stderr.closeThreadName().startsWith("procwright-eof-race-stderr-close-"));
            assertTrue(stdout.activeDuringClose() > 0, "stdout physical close must occupy dispatcher capacity");
            assertTrue(stderr.activeDuringClose() > 0, "stderr physical close must occupy dispatcher capacity");
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            coordinator.closeSession();
            rawSession.close();
        }
    }

    @Test
    void coordinatorDoesNotStrandAReservedCloseWhenDispatcherCapacityIsOccupied() throws Exception {
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, 3);
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr, processAlive);
        DefaultSession rawSession = session(process, closeDispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "queued-close");
        CountDownLatch pumpsFinished = new CountDownLatch(2);

        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-queued-close-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-queued-close-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            coordinator.closeSession();

            assertTrue(process.awaitDestroyed(), "process cleanup must precede output close");
            assertTrue(rawSession.exitCompleted());
            assertFalse(rawSession.onExit().isDone());
            assertTrue(stdout.awaitCloseStarted(), "stdout must own the only active close permit");
            assertEquals(0, stderr.closeCalls(), "stderr must remain queued while stdout physical close blocks");

            stdout.releaseClose();

            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitClose(), "queued stderr close must run when stdout releases capacity");
            rawSession.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            stdout.releaseClose();
            coordinator.closeSession();
            rawSession.close();
        }
    }

    @Test
    void rejectedSessionAdmissionFailsBeforeOutputAndPumpPublication() throws Exception {
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2, 3);
        CountDownLatch occupyingCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseOccupyingClose = new CountDownLatch(1);
        CountDownLatch pendingClosesFinished = new CountDownLatch(2);
        BoundedCloseDispatcher.Reservation occupiedCapacity = closeDispatcher.reserve(3);
        occupiedCapacity.dispatch(
                () -> {
                    occupyingCloseStarted.countDown();
                    awaitUninterruptibly(releaseOccupyingClose);
                },
                "procwright-occupying-output-close-",
                failure -> {});
        assertTrue(occupyingCloseStarted.await(1, TimeUnit.SECONDS));
        occupiedCapacity.dispatch(pendingClosesFinished::countDown, "procwright-pending-output-close-", failure -> {});
        occupiedCapacity.dispatch(pendingClosesFinished::countDown, "procwright-pending-output-close-", failure -> {});

        CloseTrackingInputStream stdout = new CloseTrackingInputStream();
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        try {
            assertThrows(RejectedExecutionException.class, () -> session(process, closeDispatcher));

            assertTrue(process.awaitDestroyed());
            assertEquals(0, stdout.closeCalls());
            assertEquals(0, stderr.closeCalls());

            releaseOccupyingClose.countDown();
            assertTrue(pendingClosesFinished.await(1, TimeUnit.SECONDS), "previously accepted work must drain");
            assertEquals(0, closeDispatcher.outstandingCount());
        } finally {
            releaseOccupyingClose.countDown();
        }
    }

    private static void drainToEof(InputStream stream, CountDownLatch finished) {
        try (stream) {
            if (stream.read() != -1) {
                throw new AssertionError("test output must be at EOF");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not drain test output", exception);
        } finally {
            finished.countDown();
        }
    }

    private static void awaitWithin(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError(failureMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failureMessage, exception);
        }
    }

    private static void construct(HelperKind helper, DefaultSession session, PumpStarter starter) {
        ZeroReadBackoff backoff = ZeroReadBackoff.exponential();
        switch (helper) {
            case EXPECT -> new DefaultExpect(session, ExpectSettings.defaults(), backoff, starter);
            case LINE -> new DefaultLineSession(session, LineSessionSettings.defaults(), backoff, starter);
            case PROTOCOL ->
                new DefaultProtocolSession<>(
                        session, noOpAdapter(), ProtocolSessionSettings.defaults(), backoff, starter);
            case STREAM -> new DefaultStreamSession(session, streamPlan(), diagnostics(), backoff, starter);
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
                LaunchMode.DIRECT,
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

    private static DefaultSession session(Process process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    private static DefaultSession session(Process process, BoundedCloseDispatcher closeDispatcher) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                () -> {},
                closeDispatcher,
                Threading::start);
    }

    private static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "pump-startup-test", CommandEcho.empty());
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void assertSuppressedOnce(Throwable primary, Throwable expected) {
        int matches = 0;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected) {
                matches++;
            }
        }
        assertEquals(1, matches);
    }

    private static void assertSuppressedInOrder(Throwable primary, Throwable... expected) {
        Throwable[] actual = primary.getSuppressed();
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertSame(expected[index], actual[index], "suppressed failure " + index);
        }
    }

    private static void awaitSettlement(CompletableFuture<?> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        future.handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
    }

    private static boolean awaitCloseFailureFinalization(OutputPumpCoordinator coordinator) throws Exception {
        java.lang.reflect.Field lockField = OutputPumpCoordinator.class.getDeclaredField("outputCloseLock");
        java.lang.reflect.Field finalizedField = OutputPumpCoordinator.class.getDeclaredField("closeFailuresFinalized");
        lockField.setAccessible(true);
        finalizedField.setAccessible(true);
        Object lock = lockField.get(coordinator);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            synchronized (lock) {
                if (finalizedField.getBoolean(coordinator)) {
                    return true;
                }
            }
            Thread.onSpinWait();
        } while (System.nanoTime() - deadline < 0);
        return false;
    }

    private static boolean awaitOutputCleanup(OutputPumpCoordinator coordinator) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            if (coordinator.outputCleanupCompleted()) {
                return true;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() - deadline < 0);
        return false;
    }

    private enum HelperKind {
        EXPECT,
        LINE,
        PROTOCOL,
        STREAM
    }

    private static final class FailingPumpStarter implements PumpStarter {

        private final int failingOrdinal;
        private final Throwable failure;
        private final AtomicInteger starts = new AtomicInteger();
        private final List<Thread> startedThreads = new ArrayList<>();

        private FailingPumpStarter(int failingOrdinal, Throwable failure) {
            this.failingOrdinal = failingOrdinal;
            this.failure = failure;
        }

        @Override
        public Thread start(String namePrefix, Runnable task) {
            if (starts.incrementAndGet() == failingOrdinal) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            Thread thread = Threading.start(namePrefix, task);
            startedThreads.add(thread);
            return thread;
        }

        private boolean awaitStartedThreadsStopped() throws InterruptedException {
            for (Thread thread : startedThreads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class StartThenThrowPumpStarter implements PumpStarter {

        private final int failingOrdinal;
        private final Throwable failure;
        private final AtomicInteger starts = new AtomicInteger();
        private final List<Thread> startedThreads = new ArrayList<>();
        private final List<Throwable> uncaughtFailures = new CopyOnWriteArrayList<>();

        private StartThenThrowPumpStarter(int failingOrdinal, Throwable failure) {
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

        private boolean awaitStartedThreadsStopped() throws InterruptedException {
            for (Thread thread : startedThreads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }

        private List<Throwable> uncaughtFailures() {
            return List.copyOf(uncaughtFailures);
        }
    }

    private static final class CloseTrackingInputStream extends InputStream {

        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final BoundedCloseDispatcher observedDispatcher;
        private final AtomicInteger activeDuringClose = new AtomicInteger(-1);
        private volatile String closeThreadName;

        private CloseTrackingInputStream() {
            this(null);
        }

        private CloseTrackingInputStream(BoundedCloseDispatcher observedDispatcher) {
            this.observedDispatcher = observedDispatcher;
        }

        @Override
        public int read() {
            reads.incrementAndGet();
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            reads.incrementAndGet();
            return -1;
        }

        @Override
        public void close() {
            closeThreadName = Thread.currentThread().getName();
            if (observedDispatcher != null) {
                activeDuringClose.set(observedDispatcher.activeCount());
            }
            closes.incrementAndGet();
            closed.countDown();
        }

        private int reads() {
            return reads.get();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private String closeThreadName() {
            return closeThreadName;
        }

        private int activeDuringClose() {
            return activeDuringClose.get();
        }
    }

    private static final class BlockingPublicReadInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();

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

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseEof() {
            release.countDown();
        }

        private int closeCalls() {
            return closes.get();
        }
    }

    private static final class ThrowingCloseInputStream extends InputStream {

        private final Error closeFailure;
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Thread closeThread;

        private ThrowingCloseInputStream(Error closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeThread = Thread.currentThread();
            closes.incrementAndGet();
            closed.countDown();
            throw closeFailure;
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitCloseCompleted() throws InterruptedException {
            if (!closed.await(1, TimeUnit.SECONDS)) {
                return false;
            }
            Thread thread = closeThread;
            if (thread == null) {
                return false;
            }
            thread.join(TimeUnit.SECONDS.toMillis(1));
            return !thread.isAlive();
        }
    }

    private static final class GatedThrowingCloseInputStream extends InputStream {

        private final Error closeFailure;
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);

        private GatedThrowingCloseInputStream(Error closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
            throw closeFailure;
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            closeRelease.countDown();
        }
    }

    private static final class BlockingCloseInputStream extends InputStream {

        private final AtomicBoolean processAlive;
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);
        private final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();

        private BlockingCloseInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            destroyedBeforeClose.set(!processAlive.get());
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            closeRelease.countDown();
        }

        private boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        private boolean closeCompleted() {
            return closeCompleted.getCount() == 0;
        }

        private int closeCalls() {
            return closes.get();
        }
    }

    private static final class ControllableProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive;
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final InputStream stdout;
        private final InputStream stderr;
        private volatile Thread isAliveFailureThread;
        private volatile RuntimeException isAliveFailure;

        private ControllableProcess(InputStream stdout, InputStream stderr) {
            this(stdout, stderr, new AtomicBoolean(true));
        }

        private ControllableProcess(InputStream stdout, InputStream stderr, AtomicBoolean alive) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.alive = alive;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
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
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException exception) {
                return false;
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer exitCode = exit.getNow(null);
            if (exitCode == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            alive.set(false);
            exit.complete(143);
            destroyed.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            if (Thread.currentThread() == isAliveFailureThread) {
                throw isAliveFailure;
            }
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private boolean awaitDestroyed() throws InterruptedException {
            return destroyed.await(1, TimeUnit.SECONDS);
        }

        private void failIsAliveOnCurrentThread(RuntimeException failure) {
            isAliveFailure = failure;
            isAliveFailureThread = Thread.currentThread();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

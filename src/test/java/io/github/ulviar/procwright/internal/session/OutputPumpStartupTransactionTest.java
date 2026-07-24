/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.Threading;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OutputPumpStartupTransactionTest extends OutputPumpStartupTestSupport {

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
                    BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
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
    void processExitBetweenPumpStartsClosesBothStreamsAfterStartupCommit() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
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
            awaitSettlement(rawSession.onExit());
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
            awaitSettlement(rawSession.onExit());

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
        CountDownLatch handlersEntered = new CountDownLatch(2);
        CountDownLatch handlersMayReenter = new CountDownLatch(1);
        CountDownLatch handlersReturned = new CountDownLatch(2);
        CountDownLatch reportsCompleted = new CountDownLatch(2);
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, 4, (name, task) -> {
            int ordinal = closeStarts.getAndIncrement();
            Thread thread = new Thread(task, name + ordinal);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                reportedFailures.add(failure);
                handlersEntered.countDown();
                awaitUninterruptibly(handlersMayReenter);
                coordinatorReference.get().closeSessionPreserving(workerFailure);
                handlersReturned.countDown();
                reportsCompleted.countDown();
            });
            thread.start();
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

            boolean bothHandlersEntered = handlersEntered.await(1, TimeUnit.SECONDS);
            handlersMayReenter.countDown();
            assertTrue(
                    bothHandlersEntered, "both uncaught handlers must run without acquiring the coordinator monitor");
            assertTrue(handlersReturned.await(1, TimeUnit.SECONDS));
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
            handlersMayReenter.countDown();
            coordinator.closeSessionPreserving(workerFailure);
            rawSession.close();
        }
    }
}

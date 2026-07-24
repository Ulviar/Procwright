/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.Threading;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class OutputPumpCleanupCoordinationTest extends OutputPumpCleanupTestSupport {

    @Test
    void scenarioTerminalCleanupDoesNotWaitForAttributionWhenPhysicalClosesSucceed() throws Exception {
        InputStream stdout = InputStream.nullInputStream();
        InputStream stderr = InputStream.nullInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2, 4));
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(
                rawSession, "successful-scenario", OutputPumpCoordinator.FailureAttribution.SCENARIO_TERMINAL);
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        CountDownLatch outputCleanupCompleted = new CountDownLatch(1);
        coordinator.publishAfterOutputCleanup(outputCleanupCompleted::countDown);
        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-successful-scenario-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-successful-scenario-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            process.exitNaturally(0);

            assertTrue(outputCleanupCompleted.await(1, TimeUnit.SECONDS));
            rawSession.onExit().get(1, TimeUnit.SECONDS);
        } finally {
            coordinator.closeSession();
            rawSession.close();
        }
    }

    @Test
    void scenarioTerminalCleanupWithCloseFailuresStillWaitsForAttribution() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError terminalPrimary = new AssertionError("terminal primary");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2, 4));
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(
                rawSession, "failed-scenario", OutputPumpCoordinator.FailureAttribution.SCENARIO_TERMINAL);
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        CountDownLatch outputCleanupCompleted = new CountDownLatch(1);
        coordinator.publishAfterOutputCleanup(outputCleanupCompleted::countDown);
        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-failed-scenario-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-failed-scenario-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            process.exitNaturally(0);

            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertEquals(1, outputCleanupCompleted.getCount());

            coordinator.sealFailureAttribution(terminalPrimary);

            assertTrue(outputCleanupCompleted.await(1, TimeUnit.SECONDS));
            assertSuppressedOnce(terminalPrimary, stdoutCloseFailure);
            assertSuppressedOnce(terminalPrimary, stderrCloseFailure);
        } finally {
            coordinator.closeSessionPreserving(terminalPrimary);
            rawSession.close();
        }
    }

    @Test
    void scenarioTerminalAttributionOwnsCloseFailuresThatCompleteLater() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError terminalPrimary = new AssertionError("terminal primary");
        GatedThrowingCloseInputStream stdout = new GatedThrowingCloseInputStream(stdoutCloseFailure);
        GatedThrowingCloseInputStream stderr = new GatedThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2, 4));
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(
                rawSession, "late-close-failure", OutputPumpCoordinator.FailureAttribution.SCENARIO_TERMINAL);
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        CountDownLatch outputCleanupCompleted = new CountDownLatch(1);
        coordinator.publishAfterOutputCleanup(outputCleanupCompleted::countDown);
        try {
            coordinator.start(
                    PumpStarter.threading(),
                    "procwright-late-close-failure-stdout-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    "procwright-late-close-failure-stderr-pump-",
                    stream -> drainToEof(stream, pumpsFinished),
                    () -> {});
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            process.exitNaturally(0);
            assertTrue(stdout.awaitCloseStarted());
            assertTrue(stderr.awaitCloseStarted());

            coordinator.sealFailureAttribution(terminalPrimary);
            assertEquals(1, outputCleanupCompleted.getCount());
            stdout.releaseClose();
            stderr.releaseClose();

            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertTrue(outputCleanupCompleted.await(1, TimeUnit.SECONDS));
            assertSuppressedOnce(terminalPrimary, stdoutCloseFailure);
            assertSuppressedOnce(terminalPrimary, stderrCloseFailure);
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            coordinator.closeSessionPreserving(terminalPrimary);
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
        AtomicInteger failureReportCount = new AtomicInteger();
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, 4, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> failureReportCount.incrementAndGet());
            thread.start();
        });
        DefaultSession rawSession = session(process, closeDispatcher);
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "deferred-report");
        CountDownLatch outputCleanupCompleted = new CountDownLatch(1);
        coordinator.publishAfterOutputCleanup(outputCleanupCompleted::countDown);
        CountDownLatch stdoutPumpEntered = new CountDownLatch(1);
        CountDownLatch releaseStdoutPump = new CountDownLatch(1);
        CountDownLatch stdoutPumpDrained = new CountDownLatch(1);
        CountDownLatch latePrimarySelected = new CountDownLatch(1);
        CountDownLatch stderrPumpFinished = new CountDownLatch(1);
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
            awaitSettlement(rawSession.physicalOutputCleanup());
            assertTrue(rawSession.physicalOutputCleanup().isDone());
            assertEquals(1, outputCleanupCompleted.getCount());
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(0, failureReportCount.get(), "cleanup failures must wait for the remaining pump outcome");

            releaseStdoutPump.countDown();
            assertTrue(latePrimarySelected.await(1, TimeUnit.SECONDS));
            assertEquals(0, stdoutPumpDrained.getCount());
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }

            assertTrue(outputCleanupCompleted.await(1, TimeUnit.SECONDS));
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(0, failureReportCount.get());
            assertSuppressedOnce(workerFailure, stdoutCloseFailure);
            assertSuppressedOnce(workerFailure, stderrCloseFailure);
            assertEquals(2, workerFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
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
        CountDownLatch outputCleanupCompleted = new CountDownLatch(1);
        coordinator.publishAfterOutputCleanup(outputCleanupCompleted::countDown);
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
            assertEquals(0, outputCleanupCompleted.getCount());
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
    void latePrimaryInstalledAfterProcessExitOwnsFuturePhysicalCloseFailures() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError latePrimary = new AssertionError("late terminal primary");
        GatedThrowingCloseInputStream stdout = new GatedThrowingCloseInputStream(stdoutCloseFailure);
        GatedThrowingCloseInputStream stderr = new GatedThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2, 4));
        OutputPumpCoordinator coordinator = new OutputPumpCoordinator(rawSession, "late-primary");
        CountDownLatch outputCleanupCompleted = new CountDownLatch(1);
        coordinator.publishAfterOutputCleanup(outputCleanupCompleted::countDown);
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

            coordinator.closeSessionPreserving(latePrimary);
            stdout.releaseClose();
            stderr.releaseClose();
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            assertTrue(outputCleanupCompleted.await(1, TimeUnit.SECONDS));
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
        CountDownLatch acceptedClosesSettled = new CountDownLatch(3);
        BoundedCloseDispatcher.Reservation occupiedCapacity = closeDispatcher.reserve(3);
        occupiedCapacity.dispatch(
                () -> {
                    occupyingCloseStarted.countDown();
                    awaitUninterruptibly(releaseOccupyingClose);
                },
                "procwright-occupying-output-close-",
                failure -> {},
                acceptedClosesSettled::countDown);
        assertTrue(occupyingCloseStarted.await(1, TimeUnit.SECONDS));
        occupiedCapacity.dispatch(
                pendingClosesFinished::countDown,
                "procwright-pending-output-close-",
                failure -> {},
                acceptedClosesSettled::countDown);
        occupiedCapacity.dispatch(
                pendingClosesFinished::countDown,
                "procwright-pending-output-close-",
                failure -> {},
                acceptedClosesSettled::countDown);

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
            assertTrue(
                    acceptedClosesSettled.await(1, TimeUnit.SECONDS),
                    "accepted close completion callbacks must be published");
            assertEquals(0, closeDispatcher.outstandingCount());
        } finally {
            releaseOccupyingClose.countDown();
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.FailingPumpStarter;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.GatedThrowingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.ThrowingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.awaitSettlement;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.drainToEof;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.ExpectSettings;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OutputPumpFailureSettlementTest {

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
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(0, startupFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            rawSession.close();
        }
    }

    @Test
    void publicSessionFailureEnvelopeRetainsTheExactRuntimeCause() throws Exception {
        IllegalStateException sessionFailure = new IllegalStateException("session close failed");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        List<Throwable> reportedFailures = new CopyOnWriteArrayList<>();
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> reportedFailures.add(failure));
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
            CommandExecutionException thrown = assertThrows(CommandExecutionException.class, coordinator::closeSession);

            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, thrown.reason());
            assertSame(sessionFailure, thrown.getCause());
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            awaitSettlement(rawSession.onExit());
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(0, sessionFailure.getSuppressed().length);
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
        } finally {
            coordinator.closeSessionPreserving(sessionFailure);
            rawSession.close();
        }
    }

    @Test
    void authoritativePrimaryRegisteredBeforePhysicalCloseOwnsEveryCloseFailureOnce() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError fallback = new AssertionError("fallback process outcome");
        AssertionError workerFailure = new AssertionError("worker failed after close started");
        ThrowingCloseInputStream stdout = new ThrowingCloseInputStream(stdoutCloseFailure);
        ThrowingCloseInputStream stderr = new ThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        List<ReportedFailure> reports = new CopyOnWriteArrayList<>();
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(
                    (source, failure) -> reports.add(new ReportedFailure(source.getName(), failure)));
            thread.start();
        });
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

            retainFallbackFrom("authoritative-primary-fallback-source", coordinator, fallback, reports);
            coordinator.closeSessionPreserving(workerFailure);
            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitCloseCompleted());
            awaitSettlement(rawSession.onExit());

            coordinator.closeSessionPreserving(workerFailure);

            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertReportedOnceFromSource(reports, "authoritative-primary-fallback-source", fallback);
            assertReportedOnceFromSourcePrefix(reports, "procwright-shutdown-race-stdout-close-", stdoutCloseFailure);
            assertReportedOnceFromSourcePrefix(reports, "procwright-shutdown-race-stderr-close-", stderrCloseFailure);
            assertEquals(0, reportCount(reports, workerFailure));
            assertEquals(3, reports.size());
            assertEquals(0, workerFailure.getSuppressed().length);
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
        CountDownLatch handlersEntered = new CountDownLatch(2);
        CountDownLatch handlersReturned = new CountDownLatch(2);
        Thread.UncaughtExceptionHandler handler = (ignored, failure) -> {
            if (failure == stdoutCloseFailure || failure == stderrCloseFailure) {
                reportedFailures.add(failure);
                handlersEntered.countDown();
                coordinatorReference.get().closeSessionPreserving(workerFailure);
                handlersReturned.countDown();
            }
        };
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(handler);
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

            assertTrue(
                    handlersEntered.await(1, TimeUnit.SECONDS),
                    "both uncaught handlers must run without acquiring the coordinator monitor");
            assertTrue(handlersReturned.await(1, TimeUnit.SECONDS));
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
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
    void scenarioTerminalCleanupDoesNotWaitForAttributionWhenPhysicalClosesSucceed() throws Exception {
        InputStream stdout = InputStream.nullInputStream();
        InputStream stderr = InputStream.nullInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2));
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
        DefaultSession rawSession = session(process, new BoundedCloseDispatcher(2, 2));
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
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(0, terminalPrimary.getSuppressed().length);
        } finally {
            coordinator.closeSessionPreserving(terminalPrimary);
            rawSession.close();
        }
    }

    @Test
    void scenarioTerminalAttributionOwnsCloseFailuresThatCompleteLater() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        AssertionError fallback = new AssertionError("fallback process outcome");
        AssertionError terminalPrimary = new AssertionError("terminal primary");
        GatedThrowingCloseInputStream stdout = new GatedThrowingCloseInputStream(stdoutCloseFailure);
        GatedThrowingCloseInputStream stderr = new GatedThrowingCloseInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        List<ReportedFailure> reports = new CopyOnWriteArrayList<>();
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(
                    (source, failure) -> reports.add(new ReportedFailure(source.getName(), failure)));
            thread.start();
        });
        DefaultSession rawSession = session(process, closeDispatcher);
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

            retainFallbackFrom("scenario-terminal-fallback-source", coordinator, fallback, reports);
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
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertReportedOnceFromSource(reports, "scenario-terminal-fallback-source", fallback);
            assertReportedOnceFromSourcePrefix(
                    reports, "procwright-late-close-failure-stdout-close-", stdoutCloseFailure);
            assertReportedOnceFromSourcePrefix(
                    reports, "procwright-late-close-failure-stderr-close-", stderrCloseFailure);
            assertEquals(0, reportCount(reports, terminalPrimary));
            assertEquals(3, reports.size());
            assertEquals(0, terminalPrimary.getSuppressed().length);
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            coordinator.closeSessionPreserving(terminalPrimary);
            rawSession.close();
        }
    }

    private static void retainFallbackFrom(
            String sourceName,
            OutputPumpCoordinator coordinator,
            Throwable fallback,
            List<ReportedFailure> reports)
            throws InterruptedException {
        Thread source = new Thread(() -> coordinator.retainFailure(fallback), sourceName);
        source.setUncaughtExceptionHandler(
                (reportedSource, failure) -> reports.add(new ReportedFailure(reportedSource.getName(), failure)));
        source.start();
        source.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(source.isAlive(), "fallback registration must complete");
    }

    private static void assertReportedOnceFromSource(
            List<ReportedFailure> reports, String sourceName, Throwable expectedFailure) {
        List<ReportedFailure> matching = reports.stream()
                .filter(report -> report.sourceName().equals(sourceName))
                .filter(report -> report.failure() == expectedFailure)
                .toList();
        assertEquals(1, matching.size());
        assertSame(expectedFailure, matching.get(0).failure());
    }

    private static void assertReportedOnceFromSourcePrefix(
            List<ReportedFailure> reports, String sourceNamePrefix, Throwable expectedFailure) {
        List<ReportedFailure> matching = reports.stream()
                .filter(report -> report.sourceName().startsWith(sourceNamePrefix))
                .filter(report -> report.failure() == expectedFailure)
                .toList();
        assertEquals(1, matching.size());
        assertSame(expectedFailure, matching.get(0).failure());
    }

    private static long reportCount(List<ReportedFailure> reports, Throwable expectedFailure) {
        return reports.stream().filter(report -> report.failure() == expectedFailure).count();
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private record ReportedFailure(String sourceName, Throwable failure) {}
}

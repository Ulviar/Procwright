/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class ProcessKernelFailureOutputAndSupervisionCleanupTest
        extends ProcessKernelFailureOutputAndSupervisionCleanupTestSupport {

    @Test
    void elapsedDurationUsesInjectedMonotonicTimeAndClampsBackwardReadings() {
        AtomicLong nanoTime = new AtomicLong(100);
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofSeconds(1),
                () -> nanoTime.getAndSet(50));

        CommandResult result = kernel.run(executionPlan(
                DiagnosticsSettings.disabled(), StdinPolicy.closed(), OutputMode.SEPARATE, Duration.ofSeconds(1)));

        assertEquals(Duration.ZERO, result.elapsed());
    }

    @Test
    void failureCleanupForceStopsRootWhenLivenessCannotBeObserved() {
        LivenessRestrictedProcess process = new LivenessRestrictedProcess();
        IllegalStateException primaryFailure = new IllegalStateException("primary failure");

        ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

        assertEquals(1, process.forceDestroyCalls());
        assertTrue(primaryFailure.getSuppressed().length == 0);
    }

    @Test
    void cleanupContinuesWhenDestroyThrowsThePrimaryFailureItself() throws Exception {
        AssertionError primaryFailure = new AssertionError("primary failure");
        SelfFailingCleanupProcess process = new SelfFailingCleanupProcess(primaryFailure);

        ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

        assertTrue(eventually(() -> process.stdoutClosed() && process.stderrClosed()));
        assertEquals(0, primaryFailure.getSuppressed().length);
    }

    @Test
    void failureCleanupDispatchesExactlyOnePhysicalStdinClose() throws Exception {
        assertTrue(eventually(() -> BoundedCloseDispatcher.shared().outstandingCount() == 0));
        int baselineOutstanding = BoundedCloseDispatcher.shared().outstandingCount();
        BlockingCloseOutputStream stdin = new BlockingCloseOutputStream();
        CleanupProcess process = new CleanupProcess(stdin);
        AssertionError primaryFailure = new AssertionError("primary failure");
        try {
            ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

            assertTrue(stdin.awaitClose());
            assertEquals(1, stdin.closeCalls());
            assertTrue(BoundedCloseDispatcher.shared().outstandingCount() >= baselineOutstanding + 1);
            assertFalse(process.isAlive());
            assertEquals(0, primaryFailure.getSuppressed().length);
        } finally {
            stdin.release();
        }
        assertTrue(eventually(() -> BoundedCloseDispatcher.shared().outstandingCount() == baselineOutstanding));
    }

    @Test
    void asynchronousStdinCloseFailureIsSuppressedOnTheOperationPrimaryExactlyOnce() throws Exception {
        AssertionError closeFailure = new AssertionError("stdin close failed");
        CloseCountingOutputStream stdin = new CloseCountingOutputStream(closeFailure);
        CleanupProcess process = new CleanupProcess(stdin);
        AssertionError primaryFailure = new AssertionError("primary failure");

        ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

        assertTrue(stdin.awaitClose());
        assertEquals(1, stdin.closeCalls());
        assertTrue(eventually(() -> primaryFailure.getSuppressed().length == 1));
        assertSame(closeFailure, primaryFailure.getSuppressed()[0]);
    }

    @Test
    void launchErrorRetainsIdentityAndEmitsOneSafeProcessFailure() throws Exception {
        AssertionError launchFailure = new AssertionError("launch failed");
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch failureDelivered = new CountDownLatch(1);
        ExecutionPlan plan = executionPlan(
                StandardCharsets.UTF_8, DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        failureDelivered.countDown();
                    }
                }));
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            throw launchFailure;
        });

        AssertionError thrown = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(launchFailure, thrown);
        assertTrue(failureDelivered.await(1, TimeUnit.SECONDS));
        List<DiagnosticEvent> failures = events.stream()
                .filter(event -> event.type() == DiagnosticEventType.PROCESS_FAILED)
                .toList();
        assertEquals(1, failures.size());
        assertEquals(
                AssertionError.class.getName(), failures.get(0).attributes().get("error"));
    }

    @Test
    void postStartErrorRetainsIdentityWhenDiagnosticListenerAlsoFails() throws Exception {
        AssertionError operationFailure = new AssertionError("post-start failed");
        CountDownLatch listenerCalled = new CountDownLatch(1);
        CleanupProcess process = new CleanupProcess(new CloseCountingOutputStream(null));
        ExecutionPlan plan = executionPlan(
                StandardCharsets.UTF_8, DiagnosticsSettings.disabled().withListener(event -> {
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        listenerCalled.countDown();
                        throw new AssertionError("listener failed");
                    }
                }));
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {
                    throw operationFailure;
                },
                (launchPlan, stdio) -> process);

        AssertionError thrown = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(operationFailure, thrown);
        assertTrue(listenerCalled.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void decodeFailureElapsedIsSampledAfterBlockedSupervisionCleanup() throws Exception {
        AtomicBoolean cleanupFinished = new AtomicBoolean();
        AtomicBoolean failureObservedAfterCleanup = new AtomicBoolean();
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        BlockingCleanupInputStream stdout = new BlockingCleanupInputStream(cleanupFinished);
        TerminalProcess process = new TerminalProcess(stdout, new TrackingInputStream(), new TrackingOutputStream());
        AtomicInteger nanoReads = new AtomicInteger();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofSeconds(1),
                () -> nanoReads.getAndIncrement() == 0 ? 100L : cleanupFinished.get() ? 500L : 200L);
        ExecutionPlan plan = executionPlan(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        failureObservedAfterCleanup.set(cleanupFinished.get());
                    }
                }),
                StdinPolicy.closed(),
                OutputMode.SEPARATE,
                Duration.ofSeconds(1));
        FutureTask<Throwable> execution = new FutureTask<>(() -> captureFailure(() -> kernel.run(plan)));
        Thread runner = new Thread(execution, "procwright-decode-cleanup-elapsed-test");
        runner.setDaemon(true);
        runner.start();
        try {
            assertTrue(stdout.awaitClose());
            assertFalse(execution.isDone(), "decode failure must wait for physical supervision cleanup");
        } finally {
            stdout.releaseClose();
        }

        CommandExecutionException failure = (CommandExecutionException) execution.get(1, TimeUnit.SECONDS);
        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, failure.reason());
        assertEquals(Duration.ofNanos(400), failure.result().orElseThrow().elapsed());
        assertTrue(cleanupFinished.get());
        assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
        assertTrue(failureObservedAfterCleanup.get());
        assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
    }

    @Test
    void asynchronousStdoutAndStderrErrorsRetainIdentityAndOwnCloseFailures() throws Exception {
        for (boolean stdoutFails : new boolean[] {true, false}) {
            AssertionError readFailure = new AssertionError(stdoutFails ? "stdout read" : "stderr read");
            AssertionError closeFailure = new AssertionError(stdoutFails ? "stdout close" : "stderr close");
            FailingReadInputStream failing = new FailingReadInputStream(readFailure, closeFailure);
            TrackingInputStream other = new TrackingInputStream();
            TerminalProcess process = stdoutFails
                    ? new TerminalProcess(failing, other, new TrackingOutputStream())
                    : new TerminalProcess(other, failing, new TrackingOutputStream());
            List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
            ExecutionPlan plan = executionPlan(
                    DiagnosticsSettings.disabled().withListener(events::add),
                    StdinPolicy.closed(),
                    OutputMode.SEPARATE,
                    Duration.ofSeconds(1));
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);

            AssertionError actual = assertThrows(AssertionError.class, () -> kernel.run(plan));

            assertSame(readFailure, actual);
            assertTrue(java.util.Arrays.asList(actual.getSuppressed()).contains(closeFailure));
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(
                    AssertionError.class.getName(),
                    events.stream()
                            .filter(event -> event.type() == DiagnosticEventType.PROCESS_FAILED)
                            .findFirst()
                            .orElseThrow()
                            .attributes()
                            .get("error"));
            assertEquals(1, failing.closeCalls());
            assertEquals(1, other.closeCalls());
            assertEquals(1, process.stdin.closeCalls());
        }
    }

    @Test
    void successfulCaptureClosesEveryStableStreamExactlyOnceInSeparateAndMergedModes() {
        for (OutputMode outputMode : OutputMode.values()) {
            TrackingOutputStream stdin = new TrackingOutputStream();
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            TerminalProcess process = new TerminalProcess(stdout, stderr, stdin);
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);

            kernel.run(executionPlan(
                    DiagnosticsSettings.disabled(), StdinPolicy.closed(), outputMode, Duration.ofSeconds(1)));

            assertEquals(1, stdin.closeCalls());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
            assertEquals(1, process.stdinGets.get());
            assertEquals(1, process.stdoutGets.get());
            assertEquals(1, process.stderrGets.get());
        }
    }

    @Test
    void exhaustedCloseCapacityFailsBeforeOneShotProcessPublicationOrStreamObservation() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        BoundedCloseDispatcher.Reservation occupied = dispatcher.reserve(3);
        TerminalProcess process = new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel =
                new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(1));
        try {
            assertThrows(
                    RejectedExecutionException.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            StdinPolicy.closed(),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertFalse(process.isAlive());
            assertEquals(0, process.stdinGets.get());
            assertEquals(0, process.stdoutGets.get());
            assertEquals(0, process.stderrGets.get());
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_STARTED));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        } finally {
            occupied.release();
        }
    }

    @Test
    void timedOutRunConsumesItsPreReservedClosesAtFullDispatcherCapacity() {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        TerminalProcess process = new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
        ProcessKernel kernel =
                new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(1));

        assertTrue(kernel.run(executionPlan(
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
                        OutputMode.SEPARATE,
                        Duration.ofMillis(10)))
                .timedOut());

        assertEquals(0, dispatcher.outstandingCount());
        assertEquals(1, process.stdin.closeCalls());
        assertEquals(1, process.stdout.closeCalls());
        assertEquals(1, process.stderr.closeCalls());
    }

    @Test
    void acceptedFallbackCloseKeepsOneShotCompletionPendingUntilPhysicalSettlement() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("stdin close starter failed");
        BlockingCloseOutputStream stdin = new BlockingCloseOutputStream();
        TerminalProcess process = new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3, (name, task) -> {
            if (name.contains("stdin")) {
                throw startFailure;
            }
            return Threading.start(name, task);
        });
        ProcessKernel kernel =
                new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(2));
        FutureTask<CommandResult> run = new FutureTask<>(() -> kernel.run(executionPlan(StandardCharsets.UTF_8)));
        Thread caller = new Thread(run, "procwright-kernel-blocked-fallback-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(stdin.awaitClose());
            assertFalse(
                    run.isDone(), "ProcessKernel.awaitClose must retain terminal publication until fallback settles");
        } finally {
            stdin.release();
        }

        java.util.concurrent.ExecutionException terminal =
                assertThrows(java.util.concurrent.ExecutionException.class, () -> run.get(1, TimeUnit.SECONDS));
        assertSame(startFailure, terminal.getCause());
        assertEquals(1, stdin.closeCalls());
        assertTrue(eventually(() ->
                dispatcher.activeCount() == 0 && dispatcher.pendingCount() == 0 && dispatcher.outstandingCount() == 0));
    }
}

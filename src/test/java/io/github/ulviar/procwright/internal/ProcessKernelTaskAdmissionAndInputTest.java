/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProcessKernelTaskAdmissionAndInputTest extends ProcessKernelTaskAdmissionAndInputTestSupport {

    @Test
    void earlyStdinFailureWinsBeforeLongDeadlineAndStopsTheLiveProcess() throws Exception {
        for (Throwable expected :
                List.of(new IllegalStateException("stdin writer failed"), new AssertionError("stdin writer failed"))) {
            ImmediateFailingOutputStream stdin = new ImmediateFailingOutputStream(expected);
            TerminalProcess process =
                    new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true);
            CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);
            FutureTask<Throwable> execution = new FutureTask<>(() -> captureFailure(() -> kernel.run(executionPlan(
                    DiagnosticsSettings.disabled().withListener(events::add),
                    StdinPolicy.input(CommandInput.bytes(new byte[8 * 1024 * 1024])),
                    OutputMode.SEPARATE,
                    Duration.ofSeconds(30)))));
            Thread worker = new Thread(execution, "one-shot-early-stdin-failure-test");
            worker.setDaemon(true);
            worker.start();

            Throwable actual = execution.get(1, TimeUnit.SECONDS);

            if (expected instanceof Error) {
                assertSame(expected, actual);
            } else {
                CommandExecutionException typed = (CommandExecutionException) actual;
                assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, typed.reason());
                assertSame(expected, typed.getCause());
            }
            assertFalse(process.isAlive());
            assertEquals(0, terminalCount(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(1, stdin.closeCalls());
        }
    }

    @Test
    void exhaustedOneShotTaskCapacityRejectsBeforeStartingAnotherProcessAndRecoversAfterActualExit() throws Exception {
        OneShotIoTaskOwner owner = new OneShotIoTaskOwner(1);
        NonCooperativeOutputStream firstStdin = new NonCooperativeOutputStream();
        TerminalProcess first =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), firstStdin, true);
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> {
                    if (starts.incrementAndGet() == 1) {
                        return first;
                    }
                    return new TerminalProcess(
                            new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());
                },
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofMillis(50),
                owner);
        ExecutionPlan plan = executionPlan(
                CapturePolicy.discard(),
                DiagnosticsSettings.disabled(),
                StdinPolicy.input(CommandInput.bytes(new byte[] {1})),
                OutputMode.SEPARATE,
                Duration.ofMillis(10));
        try {
            assertThrows(CommandExecutionException.class, () -> kernel.run(plan));
            assertEquals(0, owner.availablePermits());

            CommandExecutionException exhausted = assertThrows(CommandExecutionException.class, () -> kernel.run(plan));

            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, exhausted.reason());
            assertTrue(exhausted.getMessage().contains("bounded I/O task capacity"));
            assertEquals(1, starts.get(), "capacity rejection must precede process launch");

            firstStdin.release.countDown();
            assertTrue(eventually(() -> owner.availablePermits() == 1));
            kernel.run(plan);
            assertEquals(2, starts.get());
        } finally {
            firstStdin.release.countDown();
        }
    }

    @Test
    void asynchronousStdinErrorRetainsIdentity() throws Exception {
        AssertionError writeFailure = new AssertionError("stdin write");
        AssertionError closeFailure = new AssertionError("stdin close");
        FailingWriteOutputStream stdin = new FailingWriteOutputStream(writeFailure, closeFailure);
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ExecutionPlan plan = executionPlan(
                DiagnosticsSettings.disabled().withListener(events::add),
                StdinPolicy.input(CommandInput.text("payload", StandardCharsets.UTF_8)),
                OutputMode.SEPARATE,
                Duration.ofSeconds(1));
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);

        AssertionError actual = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(writeFailure, actual);
        assertTrue(java.util.Arrays.asList(actual.getSuppressed()).contains(closeFailure));
        assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
        assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        assertEquals(1, stdin.closeCalls());
    }

    @Test
    void executorTerminationFailureBecomesPrimaryBeforeSuccessDiagnostics() throws Exception {
        NonCooperativeOutputStream stdin = new NonCooperativeOutputStream();
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofMillis(50));
        try {
            CommandExecutionException failure = assertThrows(
                    CommandExecutionException.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            StdinPolicy.input(CommandInput.bytes(new byte[] {1})),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertTrue(failure.getMessage().contains("stopping command lifecycle tasks"));
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        } finally {
            stdin.release.countDown();
        }
    }

    @Test
    void executorTerminationFailureIsSuppressedOnceOnAnExistingFatalPrimary() throws Exception {
        AssertionError readFailure = new AssertionError("stdout failed while stdin writer remained active");
        NonCooperativeOutputStream stdin = new NonCooperativeOutputStream();
        TerminalProcess process =
                new TerminalProcess(new ReadErrorInputStream(readFailure), new TrackingInputStream(), stdin, true);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofMillis(50));
        try {
            AssertionError actual = assertThrows(
                    AssertionError.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            StdinPolicy.input(CommandInput.bytes(new byte[] {1})),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertSame(readFailure, actual);
            List<Throwable> terminationFailures = java.util.Arrays.stream(actual.getSuppressed())
                    .filter(CommandExecutionException.class::isInstance)
                    .filter(failure -> failure.getMessage().contains("stopping command lifecycle tasks"))
                    .toList();
            assertEquals(1, terminationFailures.size());
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        } finally {
            stdin.release.countDown();
        }
    }
}

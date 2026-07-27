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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProcessKernelIoFailureAndInputTest extends ProcessKernelProcessFixtureSupport {

    @Test
    void earlyStdinFailureWinsBeforeLongDeadlineAndStopsTheLiveProcess() throws Exception {
        for (Throwable expected :
                List.of(new IllegalStateException("stdin writer failed"), new AssertionError("stdin writer failed"))) {
            ImmediateFailingOutputStream stdin = new ImmediateFailingOutputStream(expected);
            TerminalProcess process =
                    new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true);
            CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
            ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);
            FutureTask<Throwable> execution = new FutureTask<>(() -> captureFailure(() -> kernel.run(executionPlan(
                    DiagnosticsSettings.disabled().withListener(events::add),
                    Optional.of(CommandInput.bytes(new byte[8 * 1024 * 1024])),
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
    void stalledOneShotIoDoesNotBlockIndependentExecution() {
        int stalledExecutionCount = 97;
        List<NonCooperativeOutputStream> stalledInputs = new ArrayList<>(stalledExecutionCount);
        for (int index = 0; index < stalledExecutionCount; index++) {
            stalledInputs.add(new NonCooperativeOutputStream());
        }
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(
                ignored -> {},
                (launchPlan, stdio) -> {
                    int index = starts.getAndIncrement();
                    if (index < stalledExecutionCount) {
                        return new NonCooperativeStdinProcess(new TrackingInputStream(), stalledInputs.get(index));
                    }
                    return new TerminalProcess(
                            new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());
                },
                Duration.ofMillis(5));
        ExecutionPlan plan = executionPlan(
                CapturePolicy.discard(),
                DiagnosticsSettings.disabled(),
                Optional.of(CommandInput.bytes(new byte[] {1})),
                OutputMode.SEPARATE,
                Duration.ofMillis(10));
        try {
            for (NonCooperativeOutputStream input : stalledInputs) {
                CommandExecutionException failure =
                        assertThrows(CommandExecutionException.class, () -> kernel.run(plan));
                assertTrue(failure.getMessage().contains("stopping command lifecycle tasks"));
                assertEquals(0, input.entered.getCount());
                assertEquals(1, input.release.getCount());
            }

            kernel.run(plan);
            assertEquals(stalledExecutionCount + 1, starts.get());
        } finally {
            stalledInputs.forEach(input -> input.release.countDown());
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
                Optional.of(CommandInput.text("payload", StandardCharsets.UTF_8)),
                OutputMode.SEPARATE,
                Duration.ofSeconds(1));
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

        Error actual = assertThrows(Error.class, () -> kernel.run(plan));

        assertSame(writeFailure, actual);
        assertEquals(0, writeFailure.getSuppressed().length);
        assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
        assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        assertTrue(eventually(() -> stdin.closeCalls() == 1));
    }

    @Test
    void earlyStdoutIoFailureBeatsTheRunDeadlineAndStopsTheLiveProcess() throws Exception {
        IOException expected = new IOException("stdout read failed");
        TerminalProcess process = new TerminalProcess(
                new IoFailingInputStream(expected), new TrackingInputStream(), new TrackingOutputStream(), true);
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);
        FutureTask<Throwable> execution = new FutureTask<>(() -> captureFailure(() -> kernel.run(executionPlan(
                DiagnosticsSettings.disabled(), Optional.empty(), OutputMode.SEPARATE, Duration.ofSeconds(30)))));
        Thread caller = new Thread(execution, "one-shot-early-output-failure-test");
        caller.setDaemon(true);
        caller.start();

        CommandExecutionException failure = (CommandExecutionException) execution.get(1, TimeUnit.SECONDS);

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
        assertSame(expected, failure.getCause().getCause());
        assertFalse(process.isAlive());
        assertTrue(eventually(() -> process.stdin.closeCalls() == 1
                && process.stdout.closeCalls() == 1
                && process.stderr.closeCalls() == 1));
    }

    @Test
    void executorTerminationFailureBecomesPrimaryBeforeSuccessDiagnostics() throws Exception {
        NonCooperativeOutputStream stdin = new NonCooperativeOutputStream();
        TerminalProcess process = new NonCooperativeStdinProcess(new TrackingInputStream(), stdin);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process, Duration.ofMillis(50));
        try {
            CommandExecutionException failure = assertThrows(
                    CommandExecutionException.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            Optional.of(CommandInput.bytes(new byte[] {1})),
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
    void executorTerminationFailureAppearsOnceInAggregateWithExistingFatalPrimary() throws Exception {
        AssertionError readFailure = new AssertionError("stdout failed while stdin writer remained active");
        NonCooperativeOutputStream stdin = new NonCooperativeOutputStream();
        TerminalProcess process = new NonCooperativeStdinProcess(new ReadErrorInputStream(readFailure), stdin);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process, Duration.ofMillis(50));
        try {
            Error actual = assertThrows(
                    Error.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            Optional.of(CommandInput.bytes(new byte[] {1})),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertSame(readFailure, FailureAggregation.primary(actual));
            List<Throwable> terminationFailures = FailureAggregation.sources(actual).stream()
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

    @Test
    void interruptionWhileAwaitingCapturedOutputIsRestoredBeforeRunReturns() throws Exception {
        BlockingReadInputStream stdout = new BlockingReadInputStream();
        TerminalProcess process = new TerminalProcess(stdout, new TrackingInputStream(), new TrackingOutputStream());
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        Thread execution = new Thread(
                () -> {
                    failure.set(captureFailure(() -> kernel.run(executionPlan(
                            CapturePolicy.bounded(8),
                            DiagnosticsSettings.disabled(),
                            Optional.empty(),
                            OutputMode.SEPARATE,
                            Duration.ofSeconds(1)))));
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                },
                "one-shot-output-await-interruption-test");
        execution.setDaemon(true);
        try {
            execution.start();
            assertTrue(stdout.readEntered.await(1, TimeUnit.SECONDS));
            assertTrue(eventually(() -> java.util.Arrays.stream(execution.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(OneShotExecution.class.getName())
                            && frame.getMethodName().equals("awaitCaptureUntilDeadline"))));

            execution.interrupt();
            execution.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(execution.isAlive());
            CommandExecutionException actual = (CommandExecutionException) failure.get();
            assertTrue(actual.getMessage().contains("capturing command output"));
            assertTrue(interruptedOnReturn.get());
            assertEquals(1, stdout.closeCalls());
        } finally {
            stdout.release.countDown();
            execution.interrupt();
            execution.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    private static final class ReadErrorInputStream extends TrackingInputStream {

        private final AssertionError failure;

        ReadErrorInputStream(AssertionError failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            throw failure;
        }
    }

    private static final class IoFailingInputStream extends TrackingInputStream {

        private final IOException failure;

        private IoFailingInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }
    }

    private static final class FailingWriteOutputStream extends TrackingOutputStream {

        private final AssertionError failure;
        private final AssertionError closeFailure;

        FailingWriteOutputStream(AssertionError failure, AssertionError closeFailure) {
            this.failure = failure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void write(int value) {
            throw failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            throw failure;
        }

        @Override
        public void close() {
            super.close();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class ImmediateFailingOutputStream extends TrackingOutputStream {

        private final Throwable failure;

        ImmediateFailingOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }
    }

    private static final class NonCooperativeOutputStream extends TrackingOutputStream {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(byte[] bytes, int offset, int length) {
            entered.countDown();
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException interruption) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class NonCooperativeStdinProcess extends TerminalProcess {

        private final NonCooperativeOutputStream nonCooperativeStdin;

        NonCooperativeStdinProcess(TrackingInputStream stdout, NonCooperativeOutputStream stdin) {
            super(stdout, new TrackingInputStream(), stdin, true);
            nonCooperativeStdin = stdin;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            nonCooperativeStdin.entered.await(timeout, unit);
            return !isAlive();
        }
    }

    private static final class BlockingReadInputStream extends TrackingInputStream {

        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int read() {
            readEntered.countDown();
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException interruption) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void close() {
            super.close();
            release.countDown();
        }
    }
}

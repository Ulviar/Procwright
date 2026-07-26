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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProcessKernelStreamOwnershipAndCleanupTest extends ProcessKernelProcessFixtureSupport {

    @Test
    void successfulCaptureClosesEveryStableStreamExactlyOnceInSeparateAndMergedModes() throws Exception {
        for (OutputMode outputMode : OutputMode.values()) {
            TrackingOutputStream stdin = new TrackingOutputStream();
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            TerminalProcess process = new TerminalProcess(stdout, stderr, stdin);
            ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

            kernel.run(
                    executionPlan(DiagnosticsSettings.disabled(), Optional.empty(), outputMode, Duration.ofSeconds(1)));

            assertTrue(
                    eventually(() -> stdin.closeCalls() == 1 && stdout.closeCalls() == 1 && stderr.closeCalls() == 1));
            assertEquals(1, process.stdinGets.get());
            assertEquals(1, process.stdoutGets.get());
            assertEquals(1, process.stderrGets.get());
        }
    }

    @Test
    void streamAcquisitionFailureRollsBackTheStartedProcess() throws Exception {
        IllegalStateException expected = new IllegalStateException("stdout unavailable");
        TrackingOutputStream stdin = new TrackingOutputStream();
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true) {
                    @Override
                    public java.io.InputStream getInputStream() {
                        stdoutGets.incrementAndGet();
                        throw expected;
                    }
                };
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

        IllegalStateException actual =
                assertThrows(IllegalStateException.class, () -> kernel.run(executionPlan(StandardCharsets.UTF_8)));

        assertSame(expected, actual);
        assertFalse(process.isAlive());
        assertTrue(eventually(() -> stdin.closeCalls() == 1));
        assertEquals(1, process.stdinGets.get());
        assertEquals(1, process.stdoutGets.get());
        assertEquals(0, process.stderrGets.get());
    }

    @Test
    void timedOutRunClosesEveryOwnedStreamExactlyOnce() throws Exception {
        TerminalProcess process = new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

        assertTrue(kernel.run(executionPlan(
                        DiagnosticsSettings.disabled(), Optional.empty(), OutputMode.SEPARATE, Duration.ofMillis(10)))
                .timedOut());

        assertTrue(eventually(() -> process.stdin.closeCalls() == 1
                && process.stdout.closeCalls() == 1
                && process.stderr.closeCalls() == 1));
    }

    @Test
    void timeoutCleanupFailureUsesThePublicExecutionExceptionContract() throws Exception {
        IllegalStateException cleanupFailure = new IllegalStateException("graceful destroy failed");
        TerminalProcess process =
                new TerminalProcess(
                        new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true) {
                    @Override
                    public void destroy() {
                        super.destroy();
                        throw cleanupFailure;
                    }
                };
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> kernel.run(executionPlan(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofMillis(10))));

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
        assertSame(cleanupFailure, failure.getCause());
        assertEquals(1, terminalCount(events, DiagnosticEventType.TIMEOUT_REACHED));
        assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
        assertTrue(eventually(() -> process.stdin.closeCalls() == 1
                && process.stdout.closeCalls() == 1
                && process.stderr.closeCalls() == 1));
    }

    @Test
    void blockingPhysicalCloseDoesNotDelayReadyCommandResult() throws Exception {
        BlockingCloseOutputStream stdin = new BlockingCloseOutputStream();
        TerminalProcess process = new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin);
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);
        FutureTask<CommandResult> run = new FutureTask<>(() -> kernel.run(executionPlan(StandardCharsets.UTF_8)));
        Thread caller = new Thread(run, "procwright-kernel-blocked-physical-close-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(stdin.awaitClose());
            CommandResult result = run.get(1, TimeUnit.SECONDS);
            assertFalse(result.timedOut());
            assertEquals(0, result.exitCode().orElseThrow());
        } finally {
            stdin.release();
        }

        assertTrue(eventually(() -> stdin.closeCalls() == 1));
    }

    static final class BlockingCloseOutputStream extends TrackingOutputStream {

        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            super.close();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitClose() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }
}

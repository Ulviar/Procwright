/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.OutputMode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessKernelInterruptionCleanupTest extends ProcessKernelProcessFixtureSupport {

    @Test
    void successfulInterruptionShutdownDoesNotStartAnotherCleanup() throws Exception {
        InterruptingProcess process = new InterruptingProcess(false);

        CommandExecutionException failure = runInterrupted(process);

        assertSame(process.interruption, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(process.exitReported.get(), "configured shutdown must finish observing the process exit");
        assertEquals(0, process.scansAfterExitReported.get(), "a completed shutdown must not restart tree discovery");
        assertEquals(1, process.root.gracefulDestroyCalls());
        assertEquals(0, process.root.forceDestroyCalls());
        assertEquals(1, process.child.gracefulDestroyCalls());
        assertStoppedAndClosed(process);
    }

    @Test
    void failedInterruptionShutdownStillRunsForceFallback() throws Exception {
        InterruptingProcess process = new InterruptingProcess(true);

        CommandExecutionException failure = runInterrupted(process);

        assertTrue(causeChainContains(failure, process.interruption));
        assertEquals("Interrupted while waiting for command completion", failure.getMessage());
        assertTrue(Stream.of(failure.getSuppressed())
                .anyMatch(
                        secondary -> "Command did not exit after forceful termination".equals(secondary.getMessage())));
        assertEquals(1, process.root.gracefulDestroyCalls());
        assertEquals(2, process.root.forceDestroyCalls(), "the root exits only when the separate force fallback runs");
        assertEquals(1, process.child.gracefulDestroyCalls());
        assertStoppedAndClosed(process);
    }

    private static CommandExecutionException runInterrupted(InterruptingProcess process) {
        ProcessKernel kernel = kernel((launchPlan, stdio) -> process);
        try {
            CommandExecutionException failure = assertThrows(
                    CommandExecutionException.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled(), Optional.empty(), OutputMode.SEPARATE, Duration.ZERO)));
            assertTrue(Thread.currentThread().isInterrupted(), "caller interrupt status must be restored");
            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
            return failure;
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertStoppedAndClosed(InterruptingProcess process) throws Exception {
        assertFalse(process.root.isAlive());
        assertFalse(process.child.isAlive());
        assertTrue(eventually(() -> process.stdin.closeCalls() == 1
                && process.stdout.closeCalls() == 1
                && process.stderr.closeCalls() == 1));
    }

    private static boolean causeChainContains(Throwable failure, Throwable expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause == expected) {
                return true;
            }
        }
        return false;
    }

    private static final class InterruptingProcess extends TerminalProcess {

        private final InterruptedException interruption = new InterruptedException("caller cancelled the run");
        private final ProcessLifecycleTestFixtures.MutableProcessHandle root;
        private final ProcessLifecycleTestFixtures.MutableProcessHandle child =
                new ProcessLifecycleTestFixtures.MutableProcessHandle(124L);
        private final AtomicBoolean exitReported = new AtomicBoolean();
        private final AtomicInteger scansAfterExitReported = new AtomicInteger();

        private InterruptingProcess(boolean failConfiguredShutdown) {
            super(new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
            root = new ProcessLifecycleTestFixtures.MutableProcessHandle(pid()) {

                @Override
                public boolean destroy() {
                    if (!failConfiguredShutdown) {
                        return super.destroy();
                    }
                    recordGracefulDestroyAttempt();
                    return true;
                }

                @Override
                public boolean destroyForcibly() {
                    if (!failConfiguredShutdown) {
                        return super.destroyForcibly();
                    }
                    recordForcefulDestroyAttempt();
                    if (forceDestroyCalls() == 2) {
                        markExited();
                    }
                    return true;
                }
            };
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            throw interruption;
        }

        @Override
        public int exitValue() {
            if (root.isAlive()) {
                throw new IllegalThreadStateException("alive");
            }
            exitReported.set(true);
            return 143;
        }

        @Override
        public boolean isAlive() {
            return root.isAlive();
        }

        @Override
        public ProcessHandle toHandle() {
            return root;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (exitReported.get()) {
                scansAfterExitReported.incrementAndGet();
            }
            return child.isAlive() ? Stream.of(child) : Stream.empty();
        }
    }
}

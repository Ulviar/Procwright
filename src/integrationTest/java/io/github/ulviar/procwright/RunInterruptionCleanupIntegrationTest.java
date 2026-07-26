/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.isWindows;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.assertProcessEventuallyStops;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.bestEffortDestroyPid;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForDescendantUnchecked;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForPositivePid;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForPositivePidUnchecked;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ProcessKernel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunInterruptionCleanupIntegrationTest {

    @Test
    void callerInterruptDuringRunIsTypedFailureAndRestoresInterruptStatus() throws Exception {
        java.util.concurrent.CountDownLatch processStarted = new java.util.concurrent.CountDownLatch(1);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            childPid.set(process.pid());
            processStarted.countDown();
        }));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedAfterCatch =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                service.run().withArgs("never-exit").withTimeout(Duration.ZERO).execute();
            } catch (Throwable throwable) {
                thrown.set(throwable);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        assertTrue(processStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(10));

        assertFalse(caller.isAlive(), "interrupted caller must not stay blocked in run()");
        assertTrue(
                thrown.get() instanceof CommandExecutionException,
                () -> "expected typed execution failure, got " + thrown.get());
        assertTrue(interruptedAfterCatch.get(), "caller interrupt status must be restored after the typed failure");
        assertProcessEventuallyStops(childPid.get());
    }

    @Test
    void callerInterruptDuringBlockedStdinWriteStopsRootAndDescendant(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("interrupted-stdin-child.pid");
        java.util.concurrent.CountDownLatch processReady = new java.util.concurrent.CountDownLatch(1);
        AtomicLong rootPid = new AtomicLong(-1);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            rootPid.set(process.pid());
            childPid.set(waitForPositivePidUnchecked(childPidFile, Duration.ofSeconds(5)));
            waitForDescendantUnchecked(process, childPid.get(), Duration.ofSeconds(5));
            processReady.countDown();
        }));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptRestored = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(
                () -> {
                    try {
                        service.run()
                                .withArgs(
                                        "spawn-child",
                                        "--child-scenario=never-exit",
                                        "--pid-file=" + childPidFile,
                                        "--wait=true")
                                .withInput(CommandInput.bytes(new byte[8 * 1024 * 1024]))
                                .withTimeout(Duration.ZERO)
                                .withShutdown(
                                        ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofSeconds(2)))
                                .execute();
                    } catch (Throwable failure) {
                        thrown.set(failure);
                        interruptRestored.set(Thread.currentThread().isInterrupted());
                    }
                },
                "procwright-interrupted-stdin-integration");

        try {
            caller.start();
            assertTrue(processReady.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(caller.isAlive(), "interrupted run remained blocked in stdin writer cleanup");
            assertTrue(thrown.get() instanceof CommandExecutionException, () -> "unexpected failure: " + thrown.get());
            assertTrue(interruptRestored.get());
            assertProcessEventuallyStops(rootPid.get());
            assertProcessEventuallyStops(childPid.get());
        } finally {
            bestEffortDestroyPid(rootPid.get());
            bestEffortDestroyPid(childPid.get());
        }
    }

    @Test
    void callerInterruptUsesConfiguredGracefulShutdown(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "POSIX signal shutdown semantics are not available on Windows");
        Path hookFile = directory.resolve("interrupt-shutdown-hook.txt");
        Path readyFile = directory.resolve("interrupt-ready.txt");
        java.util.concurrent.CountDownLatch processStarted = new java.util.concurrent.CountDownLatch(1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> processStarted.countDown()));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedAfterCatch =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                service.run()
                        .withArgs(
                                "shutdown-hook",
                                "--hook-delay-millis=50",
                                "--hook-file=" + hookFile,
                                "--ready-file=" + readyFile)
                        .withTimeout(Duration.ZERO)
                        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(1), Duration.ofSeconds(1)))
                        .execute();
            } catch (Throwable failure) {
                thrown.set(failure);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        assertTrue(processStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
        long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!java.nio.file.Files.exists(readyFile) && System.nanoTime() < readyDeadline) {
            Thread.sleep(10);
        }
        assertTrue(java.nio.file.Files.exists(readyFile));
        caller.interrupt();
        caller.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive());
        assertTrue(thrown.get() instanceof CommandExecutionException);
        assertTrue(interruptedAfterCatch.get());
        String hookMarkers = java.nio.file.Files.readString(hookFile);
        assertTrue(hookMarkers.contains("shutdown-hook:start"));
        assertTrue(hookMarkers.contains("shutdown-hook:end"));
    }

    @Test
    void callerInterruptDuringOrphanedOutputDrainWaitsForDescendantCleanup(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("drain-child.pid");
        AtomicLong rootPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> rootPid.set(process.pid())));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedAfterCatch =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                service.run()
                        .withArgs(
                                "spawn-child",
                                "--child-scenario=never-exit",
                                "--inherit-output=true",
                                "--pid-file=" + childPidFile,
                                "--linger-millis=500")
                        .withTimeout(Duration.ofSeconds(10))
                        .execute();
            } catch (Throwable failure) {
                thrown.set(failure);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        long childPid = waitForPositivePid(childPidFile, Duration.ofSeconds(10));
        waitForProcessExit(rootPid.get(), Duration.ofSeconds(10));
        try {
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(caller.isAlive());
            assertTrue(thrown.get() instanceof CommandExecutionException, () -> "unexpected failure: " + thrown.get());
            assertTrue(interruptedAfterCatch.get());
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(java.util.Arrays.stream(thrown.get().getSuppressed())
                    .anyMatch(failure -> failure.getMessage() != null
                            && failure.getMessage().contains("Interrupted while waiting for command cleanup")));
        } finally {
            ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    static void waitForProcessExit(long pid, Duration timeout) throws Exception {
        assertTrue(pid > 0, "process pid was not captured");
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process did not exit");
    }
}

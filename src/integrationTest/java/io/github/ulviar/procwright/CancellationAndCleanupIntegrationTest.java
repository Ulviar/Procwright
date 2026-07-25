/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ProcessKernel;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CancellationAndCleanupIntegrationTest extends OneShotCancellationIntegrationSupport {

    @Test
    void timeoutWithFileCaptureStillStopsProcessAndReportsTimedOut(@TempDir Path directory) {
        Path stdoutFile = directory.resolve("stdout.log");
        Path stderrFile = directory.resolve("stderr.log");

        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withCapture(CapturePolicy.toPath(stdoutFile, stderrFile))
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200)))
                .execute();

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("", result);
    }

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
    void earlyBrokenPipeBeatsLongTimeoutAndStopsRootAndDescendant(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("broken-pipe-child.pid");
        AtomicLong rootPid = new AtomicLong(-1);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            rootPid.set(process.pid());
            childPid.set(waitForPositivePidUnchecked(childPidFile, Duration.ofSeconds(5)));
            waitForDescendantUnchecked(process, childPid.get(), Duration.ofSeconds(5));
        }));
        java.time.Instant started = java.time.Instant.now();

        CommandExecutionException failure = assertThrows(CommandExecutionException.class, () -> service.run()
                .withArgs(
                        "spawn-child",
                        "--close-stdin=true",
                        "--child-scenario=never-exit",
                        "--pid-file=" + childPidFile,
                        "--wait=true")
                .withInput(CommandInput.bytes(new byte[8 * 1024 * 1024]))
                .withTimeout(Duration.ofSeconds(30))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofSeconds(2)))
                .execute());

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
        assertTrue(failure.getCause() instanceof IOException, () -> "unexpected writer cause: " + failure.getCause());
        assertTrue(
                Duration.between(started, java.time.Instant.now()).compareTo(Duration.ofSeconds(10)) < 0,
                "writer failure waited for the run deadline");
        assertProcessEventuallyStops(rootPid.get());
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
    void shutdownEscalationForceKillsProcessThatSurvivesInterruptSignal(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "Windows destroy() does not expose a SIGTERM-then-KILL escalation");
        Path hookFile = directory.resolve("shutdown-hook.txt");
        Duration interruptGrace = Duration.ofSeconds(2);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service =
                fixtureService(ProcessKernel.withPostStartHook(process -> childPid.set(process.pid())));

        // The shutdown hook blocks for 60 s, so the interrupt signal alone cannot end the process;
        // only the force-kill escalation after the interrupt grace can explain a bounded, dead
        // process. The hook records its progress in a file because output emitted during shutdown is inherently racy
        // with pipe draining. The 2 s run timeout leaves the fixture JVM time to register its hook before the signal.
        java.time.Instant stopStarted = java.time.Instant.now();
        CommandResult result = service.run()
                .withArgs("shutdown-hook", "--hook-delay-millis=60000", "--hook-file=" + hookFile)
                .withTimeout(Duration.ofSeconds(2))
                .withShutdown(ShutdownPolicy.interruptThenKill(interruptGrace, Duration.ofSeconds(5)))
                .execute();
        Duration wallClockElapsed = Duration.between(stopStarted, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertTrue(normalizeLineEndings(result.stdout()).startsWith("started\n"));
        String hookMarkers = java.nio.file.Files.exists(hookFile) ? java.nio.file.Files.readString(hookFile) : "";
        assertTrue(
                hookMarkers.contains("shutdown-hook:start"),
                () -> "interrupt signal must reach the shutdown hook before escalation, hook file: " + hookMarkers);
        assertFalse(
                hookMarkers.contains("shutdown-hook:end"),
                () -> "force kill must preempt the blocking shutdown hook, hook file: " + hookMarkers);
        assertTrue(
                wallClockElapsed.compareTo(interruptGrace) >= 0,
                () -> "process must survive the full interrupt grace before the kill, took " + wallClockElapsed);
        assertTrue(
                wallClockElapsed.compareTo(Duration.ofSeconds(30)) < 0,
                () -> "escalation must stay bounded, took " + wallClockElapsed);
        assertProcessEventuallyStops(childPid.get());
    }

    @Test
    void shutdownEscalationIsNotBlockedByAFullStdinPipe(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "POSIX signal shutdown semantics are not available on Windows");
        Path hookFile = directory.resolve("full-stdin-shutdown-hook.txt");
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service =
                fixtureService(ProcessKernel.withPostStartHook(process -> childPid.set(process.pid())));

        java.time.Instant started = java.time.Instant.now();
        CommandResult result = service.run()
                .withArgs("shutdown-hook", "--hook-delay-millis=60000", "--hook-file=" + hookFile)
                .withInput(CommandInput.bytes(new byte[8 * 1024 * 1024]))
                .withTimeout(Duration.ofSeconds(2))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(200), Duration.ofSeconds(5)))
                .execute();
        Duration elapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertTrue(elapsed.compareTo(Duration.ofSeconds(15)) < 0, () -> "shutdown took " + elapsed);
        assertProcessEventuallyStops(childPid.get());
    }

    @Test
    void timeoutStopsProcessAndReturnsDiagnosticResult() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200)))
                .execute();

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("started\n", result);
    }

    @Test
    void timeoutIsEnforcedWhileWritingInput() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService()
                .run()
                .withArgs("ignore-stdin", "--millis=5000")
                .withInput("x".repeat(8 * 1024 * 1024))
                .withTimeout(Duration.ofMillis(100))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200)))
                .execute();
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        String stdout = normalizeLineEndings(result.stdout());
        assertTrue(stdout.isEmpty() || "started\n".equals(stdout));
        assertTrue(result.elapsed().compareTo(Duration.ofSeconds(3)) < 0);
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void timeoutCleanupIsBoundedWhileStdoutAndStderrPumpsAreActive() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService()
                .run()
                .withArgs("long-run", "--ticks=100000", "--interval-millis=20", "--stderr-every=1")
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))
                .execute();
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertTrue(normalizeLineEndings(result.stdout()).contains("tick:"));
        assertTrue(normalizeLineEndings(result.stderr()).contains("err-tick:"));
        assertTrue(result.elapsed().compareTo(boundedCleanupLimit()) < 0);
        assertTrue(wallClockElapsed.compareTo(boundedCleanupLimit()) < 0);
    }

    @Test
    void timeoutStopsDescendantProcesses() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("spawn-child", "--child-scenario=sleep", "--child-millis=10000", "--wait=true")
                .withTimeout(descendantStartupTimeout())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(500)))
                .execute();
        long childPid = result.stdout()
                .lines()
                .filter(line -> line.startsWith("child:"))
                .map(line -> line.substring("child:".length()))
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "fixture did not report child pid before timeout: " + normalizeLineEndings(result.stdout())));

        assertTrue(result.timedOut());
        assertFalse(isAliveEventually(childPid));
    }

    @Test
    void timeoutStopsDescendantCreatedDuringGracefulShutdown(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "POSIX shutdown hooks require a graceful termination signal");
        Path readyFile = directory.resolve("late-descendant-ready.txt");
        Path childPidFile = directory.resolve("late-descendant.pid");
        CommandService service = fixtureService(
                ProcessKernel.withPostStartHook(process -> waitForFileUnchecked(readyFile, Duration.ofSeconds(5))));
        long childPid = -1;
        try {
            CommandResult result = service.run()
                    .withArgs(
                            "shutdown-hook",
                            "--ready-file=" + readyFile,
                            "--hook-child-pid-file=" + childPidFile,
                            "--hook-delay-millis=1000")
                    .withTimeout(Duration.ofSeconds(2))
                    .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                    .execute();

            childPid = waitForPositivePid(childPidFile, Duration.ofSeconds(2));
            assertTrue(result.timedOut());
            assertTrue(
                    normalizeLineEndings(result.stdout()).contains("shutdown-hook:start"),
                    () -> "graceful shutdown hook did not retain stdout: stdout='"
                            + normalizeLineEndings(result.stdout())
                            + "', stderr='"
                            + normalizeLineEndings(result.stderr())
                            + "'");
            assertFalse(
                    ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                    "cleanup left a descendant created by the shutdown hook alive");
        } finally {
            bestEffortDestroyCapturedPid(childPid, childPidFile);
        }
    }

    @Test
    void postStartFailureStopsStartedProcessAndPreservesPrimaryCause() throws Exception {
        AtomicLong childPid = new AtomicLong(-1);
        IllegalStateException failure = new IllegalStateException("synthetic post-start failure");

        java.time.Instant started = java.time.Instant.now();
        CommandExecutionException exception;
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            childPid.set(process.pid());
            throw failure;
        }));
        exception = assertThrows(CommandExecutionException.class, () -> service.run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))
                .execute());
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, exception.reason());
        assertSame(failure, exception.getCause());
        assertTrue(childPid.get() > 0);
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void postStartErrorStopsStartedProcessAndPreservesPrimaryError() throws Exception {
        AtomicLong childPid = new AtomicLong(-1);
        AssertionError failure = new AssertionError("synthetic post-start error");
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            childPid.set(process.pid());
            throw failure;
        }));

        AssertionError thrown = assertThrows(AssertionError.class, () -> service.run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))
                .execute());

        assertEquals(failure, thrown);
        assertTrue(childPid.get() > 0);
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void cyclicPostStartFailureTerminatesAndStopsRootAndDescendant(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("cyclic-failure-child.pid");
        AtomicLong rootPid = new AtomicLong(-1);
        AtomicLong childPid = new AtomicLong(-1);
        IllegalStateException primary = new IllegalStateException("cyclic post-start failure");
        IllegalArgumentException cycle = new IllegalArgumentException("cycle");
        primary.initCause(cycle);
        cycle.initCause(primary);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            rootPid.set(process.pid());
            childPid.set(waitForPositivePidUnchecked(childPidFile, Duration.ofSeconds(5)));
            waitForDescendantUnchecked(process, childPid.get(), Duration.ofSeconds(5));
            throw primary;
        }));

        FutureTask<Throwable> invocation = new FutureTask<>(() -> {
            try {
                service.run()
                        .withArgs(
                                "spawn-child",
                                "--child-scenario=never-exit",
                                "--pid-file=" + childPidFile,
                                "--wait=true")
                        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofSeconds(2)))
                        .execute();
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
        Thread worker = new Thread(invocation, "cyclic-post-start-failure-test");
        worker.setDaemon(true);
        worker.start();

        try {
            Throwable thrown;
            try {
                thrown = invocation.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                String diagnostic = cyclicFailureDiagnostic(worker, rootPid.get(), childPid.get(), childPidFile);
                worker.interrupt();
                bestEffortDestroyPid(rootPid.get());
                bestEffortDestroyCapturedPid(childPid.get(), childPidFile);
                worker.join(TimeUnit.SECONDS.toMillis(5));
                throw new AssertionError(
                        "cyclic post-start cleanup exceeded its composed budget: " + diagnostic, timeout);
            }

            CommandExecutionException commandFailure = assertInstanceOf(CommandExecutionException.class, thrown);
            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, commandFailure.reason());
            assertSame(primary, commandFailure.getCause());
            assertEquals(0, primary.getSuppressed().length);
            assertTrue(rootPid.get() > 0, "root pid was not captured");
            assertTrue(childPid.get() > 0, "descendant pid was not captured");
            assertProcessEventuallyStops(rootPid.get());
            assertProcessEventuallyStops(childPid.get());
        } finally {
            bestEffortDestroyPid(rootPid.get());
            bestEffortDestroyCapturedPid(childPid.get(), childPidFile);
        }
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

    @Test
    void orphanedDescendantHoldingOutputPipeIsKilledAndFailureExplainsCause(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "The orphaned POSIX pipe fixture is not portable to Windows");
        Path pidFile = directory.resolve("grandchild.pid");

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs(
                        "spawn-child",
                        "--child-scenario=never-exit",
                        "--inherit-output=true",
                        "--pid-file=" + pidFile,
                        "--linger-millis=500")
                .withTimeout(Duration.ofSeconds(10))
                .execute());

        assertTrue(
                exception.getMessage().contains("a descendant process that inherited stdout or stderr"),
                () -> "failure message must explain the orphaned pipe holder: " + exception.getMessage());
        long orphanPid = waitForPositivePid(pidFile, Duration.ofSeconds(5));
        assertProcessEventuallyStops(orphanPid);
    }
}

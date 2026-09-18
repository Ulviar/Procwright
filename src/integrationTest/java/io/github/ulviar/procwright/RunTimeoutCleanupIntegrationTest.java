/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.assertStdoutEquals;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.isWindows;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.normalizeLineEndings;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.bestEffortDestroyCapturedPid;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForPositivePid;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ProcessKernel;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunTimeoutCleanupIntegrationTest {

    @Test
    void timeoutWithFileCaptureStillStopsProcessAndReportsTimedOut(@TempDir Path directory) {
        Path stdoutFile = directory.resolve("stdout.log");
        Path stderrFile = directory.resolve("stderr.log");

        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withCapture(CapturePolicy.toPath(stdoutFile, stderrFile))
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(timeoutCleanupPolicy())
                .execute();

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("", result);
    }

    @Test
    void timeoutStopsProcessAndReturnsDiagnosticResult() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(timeoutCleanupPolicy())
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
                .withArgs("ignore-stdin", "--millis=60000")
                .withInput("x".repeat(8 * 1024 * 1024))
                .withTimeout(Duration.ofMillis(100))
                .withShutdown(timeoutCleanupPolicy())
                .execute();
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        String stdout = normalizeLineEndings(result.stdout());
        assertTrue(stdout.isEmpty() || "started\n".equals(stdout));
        assertTrue(result.elapsed().compareTo(boundedCleanupLimit()) < 0);
        assertTrue(wallClockElapsed.compareTo(boundedCleanupLimit()) < 0);
    }

    @Test
    void timeoutCleanupIsBoundedWhileStdoutAndStderrPumpsAreActive() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService()
                .run()
                .withArgs("long-run", "--ticks=100000", "--interval-millis=20", "--stderr-every=1")
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(timeoutCleanupPolicy())
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
                .withShutdown(timeoutCleanupPolicy())
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

    private static boolean isAliveEventually(long pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted", exception);
                }
                continue;
            }
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void waitForFile(Path path, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!java.nio.file.Files.exists(path) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertTrue(java.nio.file.Files.exists(path), () -> "timed out waiting for " + path);
    }

    private static void waitForFileUnchecked(Path path, Duration timeout) {
        try {
            waitForFile(path, timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + path, exception);
        } catch (Exception exception) {
            throw new AssertionError("could not wait for " + path, exception);
        }
    }

    private static ShutdownPolicy timeoutCleanupPolicy() {
        return ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1));
    }

    private static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static Duration boundedCleanupLimit() {
        return isWindows() ? Duration.ofSeconds(6) : Duration.ofSeconds(3);
    }

    private static Duration descendantStartupTimeout() {
        // The spawned child JVM sleeps for 10 s, so a generous startup window keeps the assertion
        // semantics (timeout fires after the child pid is reported) while absorbing cold JVM starts
        // on loaded machines.
        return Duration.ofSeconds(2);
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

abstract class OneShotCancellationIntegrationSupport extends OneShotIntegrationSupport {

    static void assertProcessEventuallyStops(long pid) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return;
            }
            Thread.sleep(25);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        throw new AssertionError("orphaned descendant process " + pid + " is still alive");
    }

    static void bestEffortDestroyCapturedPid(long capturedPid, Path pidFile) {
        long pid = capturedPid > 0 ? capturedPid : readPositivePidBestEffort(pidFile, Duration.ofSeconds(2));
        if (pid <= 0) {
            return;
        }
        try {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            waitForProcessStopBestEffort(pid, Duration.ofSeconds(2));
        } catch (RuntimeException ignored) {
            // Test cleanup is best-effort and must not replace the behavioral assertion failure.
        }
    }

    static void bestEffortDestroyPid(long pid) {
        if (pid <= 0) {
            return;
        }
        try {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            waitForProcessStopBestEffort(pid, Duration.ofSeconds(2));
        } catch (RuntimeException ignored) {
            // Test cleanup is best-effort and must not replace the behavioral assertion failure.
        }
    }

    static String cyclicFailureDiagnostic(Thread worker, long rootPid, long childPid, Path childPidFile) {
        String pidFileState;
        try {
            pidFileState = java.nio.file.Files.exists(childPidFile)
                    ? java.nio.file.Files.readString(childPidFile).trim()
                    : "<missing>";
        } catch (IOException failure) {
            pidFileState = "<unreadable: " + failure.getClass().getSimpleName() + ">";
        }
        return "workerState=" + worker.getState()
                + ", rootPid=" + rootPid
                + ", rootAlive=" + processAliveBestEffort(rootPid)
                + ", childPid=" + childPid
                + ", childAlive=" + processAliveBestEffort(childPid)
                + ", pidFile='" + pidFileState + "'";
    }

    static boolean processAliveBestEffort(long pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static long waitForPositivePid(Path path, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String lastContent = "";
        IOException lastReadFailure = null;
        while (System.nanoTime() < deadlineNanos) {
            if (java.nio.file.Files.isRegularFile(path)) {
                try {
                    lastContent = java.nio.file.Files.readString(path).trim();
                    long pid = Long.parseLong(lastContent);
                    if (pid > 0) {
                        return pid;
                    }
                } catch (IOException exception) {
                    lastReadFailure = exception;
                } catch (NumberFormatException ignored) {
                    // The producer may still be replacing a partial PID marker.
                }
            }
            Thread.sleep(10);
        }
        AssertionError failure = new AssertionError(
                "PID file did not contain a positive process id: " + path + " (last content: '" + lastContent + "')");
        if (lastReadFailure != null) {
            failure.initCause(lastReadFailure);
        }
        throw failure;
    }

    static long readPositivePidBestEffort(Path path, Duration timeout) {
        try {
            return waitForPositivePid(path, timeout);
        } catch (AssertionError ignored) {
            return -1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    static long waitForPositivePidUnchecked(Path path, Duration timeout) {
        try {
            return waitForPositivePid(path, timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a positive PID in " + path, exception);
        }
    }

    static void waitForProcessStopBestEffort(long pid, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos
                    && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(10);
            }
        } catch (RuntimeException ignored) {
            // Cleanup remains best-effort when process liveness cannot be observed.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    static void waitForFile(Path path, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!java.nio.file.Files.exists(path) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertTrue(java.nio.file.Files.exists(path), () -> "timed out waiting for " + path);
    }

    static void waitForFileUnchecked(Path path, Duration timeout) {
        try {
            waitForFile(path, timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + path, exception);
        } catch (Exception exception) {
            throw new AssertionError("could not wait for " + path, exception);
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

    static void waitForDescendantUnchecked(Process process, long descendantPid, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos) {
                if (process.descendants().anyMatch(handle -> handle.pid() == descendantPid)) {
                    return;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for descendant " + descendantPid, exception);
        }
        throw new AssertionError("process " + process.pid() + " did not expose descendant " + descendantPid);
    }

    static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    static Duration boundedCleanupLimit() {
        return isWindows() ? Duration.ofSeconds(6) : Duration.ofSeconds(3);
    }

    static Duration descendantStartupTimeout() {
        // The spawned child JVM sleeps for 10 s, so a generous startup window keeps the assertion
        // semantics (timeout fires after the child pid is reported) while absorbing cold JVM starts
        // on loaded machines.
        return Duration.ofSeconds(2);
    }
}

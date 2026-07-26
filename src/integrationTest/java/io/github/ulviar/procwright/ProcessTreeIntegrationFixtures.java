/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

final class ProcessTreeIntegrationFixtures {

    private ProcessTreeIntegrationFixtures() {}

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
}

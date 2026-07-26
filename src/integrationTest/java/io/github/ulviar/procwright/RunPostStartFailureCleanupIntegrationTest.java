/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.assertProcessEventuallyStops;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.bestEffortDestroyCapturedPid;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.bestEffortDestroyPid;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForDescendantUnchecked;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForPositivePidUnchecked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
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

final class RunPostStartFailureCleanupIntegrationTest {

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

    private static String cyclicFailureDiagnostic(Thread worker, long rootPid, long childPid, Path childPidFile) {
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

    private static boolean processAliveBestEffort(long pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}

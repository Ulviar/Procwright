/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.isWindows;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.assertProcessEventuallyStops;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForDescendantUnchecked;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForPositivePid;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.waitForPositivePidUnchecked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ProcessKernel;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunPipeCleanupIntegrationTest {

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
    void inheritedOutputPipeCountsAgainstTheRunDeadlineAndItsHolderIsKilled(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "The orphaned POSIX pipe fixture is not portable to Windows");
        Path pidFile = directory.resolve("grandchild.pid");

        CommandResult result = fixtureService()
                .run()
                .withArgs(
                        "spawn-child",
                        "--child-scenario=never-exit",
                        "--inherit-output=true",
                        "--pid-file=" + pidFile,
                        "--linger-millis=500")
                .withTimeout(Duration.ofSeconds(1))
                .execute();

        assertTrue(result.timedOut(), "an open inherited pipe is part of the run operation");
        assertEquals(0, result.exitCode().orElseThrow(), "the root process exited normally before the drain timeout");
        long orphanPid = waitForPositivePid(pidFile, Duration.ofSeconds(5));
        assertProcessEventuallyStops(orphanPid);
    }
}

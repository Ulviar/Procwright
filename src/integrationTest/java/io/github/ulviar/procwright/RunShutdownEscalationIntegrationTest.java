/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.isWindows;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.normalizeLineEndings;
import static io.github.ulviar.procwright.ProcessTreeIntegrationFixtures.assertProcessEventuallyStops;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ProcessKernel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunShutdownEscalationIntegrationTest {

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
}

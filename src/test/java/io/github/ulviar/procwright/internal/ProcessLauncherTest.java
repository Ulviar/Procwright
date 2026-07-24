/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessLauncherTest {

    @TempDir
    Path workingDirectory;

    @Test
    void builderAppliesResolvedLaunchAndStdioSettingsOnce() {
        LaunchPlan plan = plan(
                List.of("tool", "argument"),
                Optional.of(workingDirectory),
                EnvironmentPolicy.CLEAN,
                Map.of("ONLY", "value"),
                OutputMode.MERGED);
        StdioConfig stdio = new StdioConfig(
                ProcessBuilder.Redirect.PIPE, ProcessBuilder.Redirect.DISCARD, ProcessBuilder.Redirect.DISCARD);

        ProcessBuilder builder = ProcessLauncher.builder(plan, stdio);

        assertEquals(List.of("tool", "argument"), builder.command());
        assertEquals(workingDirectory.toFile(), builder.directory());
        assertEquals(Map.of("ONLY", "value"), builder.environment());
        assertTrue(builder.redirectErrorStream());
        assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectInput());
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput());
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
    }

    @Test
    void startFailureIsTypedAndUsesTheRedactedCommandSummary() {
        Path missingExecutable = workingDirectory.resolve("missing-procwright-command");
        LaunchPlan plan = plan(
                List.of(missingExecutable.toString(), "secret"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE);

        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> ProcessLauncher.start(plan, StdioConfig.pipes()));

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
        assertTrue(failure.getMessage().contains(missingExecutable.toString()));
        assertFalse(failure.getMessage().contains("secret"));
        assertTrue(failure.getCause() instanceof IOException);
    }

    private static LaunchPlan plan(
            List<String> command,
            Optional<Path> workingDirectory,
            EnvironmentPolicy environmentPolicy,
            Map<String, String> environment,
            OutputMode outputMode) {
        return new LaunchPlan(
                LaunchMode.DIRECT,
                command,
                workingDirectory,
                environmentPolicy,
                environment,
                outputMode,
                TerminalPolicy.DISABLED);
    }
}

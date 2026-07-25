/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LaunchPlanTest {

    @Test
    void directCommandMaterializesArgvAndLaunchMetadata() {
        Path workingDirectory = Path.of("work");
        CommandSpec command = CommandSpec.of("tool")
                .withArgs("first", "second")
                .withWorkingDirectory(workingDirectory)
                .withCleanEnvironment()
                .withEnvironment("MODE", "test");

        LaunchPlan plan = LaunchPlan.from(command, OutputMode.MERGED, TerminalPolicy.AUTO);

        assertEquals(List.of("tool", "first", "second"), plan.command());
        assertEquals(workingDirectory, plan.workingDirectory().orElseThrow());
        assertEquals(EnvironmentPolicy.CLEAN, plan.environmentPolicy());
        assertEquals(Map.of("MODE", "test"), plan.environment());
        assertEquals(OutputMode.MERGED, plan.outputMode());
        assertEquals(TerminalPolicy.AUTO, plan.terminalPolicy());
    }

    @Test
    void shellCommandMaterializesThroughSystemShell() {
        CommandSpec command = CommandSpec.shell("echo ready");

        LaunchPlan plan = LaunchPlan.from(command, OutputMode.SEPARATE, TerminalPolicy.DISABLED);

        assertEquals(SystemShell.command("echo ready"), plan.command());
    }
}

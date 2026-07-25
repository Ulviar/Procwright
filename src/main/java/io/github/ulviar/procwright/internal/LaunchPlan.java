/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record LaunchPlan(
        List<String> command,
        Optional<Path> workingDirectory,
        EnvironmentPolicy environmentPolicy,
        Map<String, String> environment,
        OutputMode outputMode,
        TerminalPolicy terminalPolicy) {

    public LaunchPlan {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environmentPolicy, "environmentPolicy");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(terminalPolicy, "terminalPolicy");
    }

    public static LaunchPlan from(CommandSpec command, OutputMode outputMode, TerminalPolicy terminalPolicy) {
        Objects.requireNonNull(command, "command");
        List<String> commandLine;
        if (command.usesShell()) {
            commandLine = SystemShell.command(command.executable());
        } else {
            ArrayList<String> direct =
                    new ArrayList<>(Math.addExact(command.arguments().size(), 1));
            direct.add(command.executable());
            direct.addAll(command.arguments());
            commandLine = direct;
        }
        return new LaunchPlan(
                commandLine,
                command.workingDirectory(),
                command.environmentPolicy(),
                command.environment(),
                outputMode,
                terminalPolicy);
    }
}

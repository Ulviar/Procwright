/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Fully normalized immutable process-launch state. */
public record LaunchSettings(
        String executable,
        boolean shell,
        List<String> arguments,
        Optional<Path> workingDirectory,
        EnvironmentPolicy environmentPolicy,
        Map<String, String> environment) {

    public LaunchSettings {
        executable = CommandValidation.requireText(executable, "executable");
        arguments = List.copyOf(arguments);
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environmentPolicy, "environmentPolicy");
        environment = Map.copyOf(environment);
        if (shell && !arguments.isEmpty()) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
    }

    public static LaunchSettings from(CommandSpec commandSpec) {
        Objects.requireNonNull(commandSpec, "commandSpec");
        return new LaunchSettings(
                commandSpec.executable(),
                commandSpec.usesShell(),
                commandSpec.arguments(),
                commandSpec.workingDirectory(),
                commandSpec.environmentPolicy(),
                commandSpec.environment());
    }

    public LaunchSettings withArg(String argument) {
        if (shell) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
        int combinedSize = combinedSize(arguments.size(), 1);
        ArrayList<String> updated = new ArrayList<>(combinedSize);
        updated.addAll(arguments);
        updated.add(CommandValidation.requireArgument(argument));
        return copy(updated, workingDirectory, environmentPolicy, environment);
    }

    public LaunchSettings withArgs(String... newArguments) {
        Objects.requireNonNull(newArguments, "arguments");
        return withArgs(Arrays.asList(newArguments));
    }

    public LaunchSettings withArgs(Collection<String> newArguments) {
        Objects.requireNonNull(newArguments, "arguments");
        List<String> additions = List.copyOf(newArguments);
        if (additions.isEmpty()) {
            return this;
        }
        if (shell) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
        additions.forEach(CommandValidation::requireArgument);

        int combinedSize = combinedSize(arguments.size(), additions.size());
        ArrayList<String> updated = new ArrayList<>(combinedSize);
        updated.addAll(arguments);
        updated.addAll(additions);
        return copy(updated, workingDirectory, environmentPolicy, environment);
    }

    public LaunchSettings withWorkingDirectory(Path directory) {
        return copy(
                arguments,
                Optional.of(Objects.requireNonNull(directory, "workingDirectory")),
                environmentPolicy,
                environment);
    }

    public LaunchSettings withEnvironment(String name, String value) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(environment);
        updated.put(CommandValidation.requireEnvironmentName(name), CommandValidation.requireEnvironmentValue(value));
        return copy(arguments, workingDirectory, environmentPolicy, updated);
    }

    public LaunchSettings withEnvironmentPolicy(EnvironmentPolicy policy) {
        return copy(arguments, workingDirectory, Objects.requireNonNull(policy, "environmentPolicy"), environment);
    }

    public LaunchPlan plan(OutputMode outputMode, TerminalPolicy terminalPolicy) {
        List<String> command;
        LaunchMode launchMode;
        if (shell) {
            launchMode = LaunchMode.SHELL;
            command = SystemShell.command(executable);
        } else {
            launchMode = LaunchMode.DIRECT;
            ArrayList<String> direct = new ArrayList<>(arguments.size() + 1);
            direct.add(executable);
            direct.addAll(arguments);
            command = direct;
        }
        return new LaunchPlan(
                launchMode, command, workingDirectory, environmentPolicy, environment, outputMode, terminalPolicy);
    }

    private LaunchSettings copy(
            List<String> newArguments,
            Optional<Path> newWorkingDirectory,
            EnvironmentPolicy newEnvironmentPolicy,
            Map<String, String> newEnvironment) {
        return new LaunchSettings(
                executable, shell, newArguments, newWorkingDirectory, newEnvironmentPolicy, newEnvironment);
    }

    private static int combinedSize(int existingSize, int additionsSize) {
        return Math.addExact(existingSize, additionsSize);
    }
}

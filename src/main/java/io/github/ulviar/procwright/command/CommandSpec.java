/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import io.github.ulviar.procwright.internal.CommandValidation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable reusable base command value. */
public final class CommandSpec {

    private final String executable;
    private final List<String> arguments;
    private final @Nullable Path workingDirectory;
    private final Map<String, String> environment;
    private final EnvironmentPolicy environmentPolicy;
    private final boolean shell;

    private CommandSpec(
            String executable,
            List<String> arguments,
            @Nullable Path workingDirectory,
            Map<String, String> environment,
            EnvironmentPolicy environmentPolicy,
            boolean shell) {
        this.executable = CommandValidation.requireText(executable, "executable");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
        if (shell && !arguments.isEmpty()) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
        this.workingDirectory = workingDirectory;
        LinkedHashMap<String, String> copiedEnvironment = new LinkedHashMap<>();
        environment.forEach((name, value) -> copiedEnvironment.put(
                CommandValidation.requireEnvironmentName(name), CommandValidation.requireEnvironmentValue(value)));
        this.environment = Map.copyOf(copiedEnvironment);
        this.environmentPolicy = Objects.requireNonNull(environmentPolicy, "environmentPolicy");
        this.shell = shell;
    }

    /**
     * Creates a direct command with no base arguments.
     *
     * @param executable executable name or path
     * @return direct command specification
     */
    public static CommandSpec of(String executable) {
        return new CommandSpec(executable, List.of(), null, Map.of(), EnvironmentPolicy.INHERIT, false);
    }

    /**
     * Creates an explicit operating-system shell command.
     *
     * @param commandLine command line interpreted by the operating-system shell
     * @return shell command specification
     */
    public static CommandSpec shell(String commandLine) {
        return new CommandSpec(commandLine, List.of(), null, Map.of(), EnvironmentPolicy.INHERIT, true);
    }

    /**
     * Appends one base argument.
     *
     * @param argument argument to append
     * @return updated command specification
     */
    public CommandSpec withArg(String argument) {
        if (shell) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
        int combinedSize = combinedSize(arguments.size(), 1);
        ArrayList<String> updated = new ArrayList<>(combinedSize);
        updated.addAll(arguments);
        updated.add(CommandValidation.requireArgument(argument));
        return copy(List.copyOf(updated), workingDirectory, environment, environmentPolicy);
    }

    /**
     * Appends base arguments after copying the caller array.
     *
     * @param arguments arguments to append
     * @return updated command specification
     */
    public CommandSpec withArgs(String... arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return withArgs(Arrays.asList(arguments));
    }

    /**
     * Appends base arguments after copying the caller collection.
     *
     * @param arguments arguments to append
     * @return updated command specification
     */
    public CommandSpec withArgs(Collection<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        List<String> additions = List.copyOf(arguments);
        if (additions.isEmpty()) {
            return this;
        }
        if (shell) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
        additions.forEach(CommandValidation::requireArgument);

        int combinedSize = combinedSize(this.arguments.size(), additions.size());
        ArrayList<String> updated = new ArrayList<>(combinedSize);
        updated.addAll(this.arguments);
        updated.addAll(additions);
        return copy(List.copyOf(updated), workingDirectory, environment, environmentPolicy);
    }

    /**
     * Sets the process working directory.
     *
     * @param workingDirectory working directory
     * @return updated command specification
     */
    public CommandSpec withWorkingDirectory(Path workingDirectory) {
        return copy(
                arguments,
                Objects.requireNonNull(workingDirectory, "workingDirectory"),
                environment,
                environmentPolicy);
    }

    /**
     * Adds or replaces one environment variable.
     *
     * @param name variable name
     * @param value variable value
     * @return updated command specification
     */
    public CommandSpec withEnvironment(String name, String value) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(environment);
        updated.put(CommandValidation.requireEnvironmentName(name), CommandValidation.requireEnvironmentValue(value));
        return copy(arguments, workingDirectory, updated, environmentPolicy);
    }

    /**
     * Makes the child inherit the parent environment before applying configured entries.
     *
     * @return updated command specification
     */
    public CommandSpec withInheritedEnvironment() {
        return copy(arguments, workingDirectory, environment, EnvironmentPolicy.INHERIT);
    }

    /**
     * Starts the child with an empty environment before applying configured entries.
     *
     * @return updated command specification
     */
    public CommandSpec withCleanEnvironment() {
        return copy(arguments, workingDirectory, environment, EnvironmentPolicy.CLEAN);
    }

    /**
     * Returns the executable or shell command line.
     *
     * @return executable or shell command line
     */
    public String executable() {
        return executable;
    }

    /**
     * Returns the immutable base argument list.
     *
     * @return base arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Returns the configured working directory.
     *
     * @return working directory, or empty when the child inherits the current directory
     */
    public Optional<Path> workingDirectory() {
        return Optional.ofNullable(workingDirectory);
    }

    /**
     * Returns the immutable environment overrides.
     *
     * @return environment overrides
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns how the initial child environment is constructed.
     *
     * @return environment policy
     */
    public EnvironmentPolicy environmentPolicy() {
        return environmentPolicy;
    }

    /**
     * Reports whether the specification is interpreted by the operating-system shell.
     *
     * @return {@code true} for a shell command
     */
    public boolean usesShell() {
        return shell;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CommandSpec that)) {
            return false;
        }
        return executable.equals(that.executable)
                && arguments.equals(that.arguments)
                && Objects.equals(workingDirectory, that.workingDirectory)
                && environment.equals(that.environment)
                && environmentPolicy == that.environmentPolicy
                && shell == that.shell;
    }

    @Override
    public int hashCode() {
        return Objects.hash(executable, arguments, workingDirectory, environment, environmentPolicy, shell);
    }

    private CommandSpec copy(
            List<String> arguments,
            @Nullable Path workingDirectory,
            Map<String, String> environment,
            EnvironmentPolicy environmentPolicy) {
        return new CommandSpec(executable, arguments, workingDirectory, environment, environmentPolicy, shell);
    }

    private static int combinedSize(int existingSize, int additionsSize) {
        return Math.addExact(existingSize, additionsSize);
    }
}

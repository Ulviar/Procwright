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

/**
 * Immutable executable, arguments, working directory, and child environment shared by command scenarios.
 *
 * <p>Use {@link #of(String)} for direct argument passing or {@link #shell(String)} for an explicitly interpreted
 * command line. Creating a specification does not start a process or check that an executable or directory exists.
 * By default, a child inherits the current working directory and environment and has no arguments.
 *
 * <p>Each {@code with*} method returns an updated value without changing this instance. Argument collections and
 * environment overrides are immutable snapshots, so a specification can be reused across threads and executions.
 */
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
        // Factories and withEnvironment supply validated, immutable environment snapshots.
        this.environment = Objects.requireNonNull(environment, "environment");
        this.environmentPolicy = Objects.requireNonNull(environmentPolicy, "environmentPolicy");
        this.shell = shell;
    }

    /**
     * Creates a direct command with no base arguments.
     *
     * <p>The executable is one token, not a command line. Pass each argument separately through {@link #withArg(String)}
     * or {@link #withArgs(String...)}. Procwright does not split whitespace, remove quotes, expand wildcards or variables,
     * or interpret pipes and redirections. Executable lookup follows the operating system and JDK process-launch rules.
     *
     * @param executable non-blank executable name or path without NUL characters
     * @return direct command specification
     * @throws IllegalArgumentException if {@code executable} is blank or contains NUL
     */
    public static CommandSpec of(String executable) {
        return new CommandSpec(executable, List.of(), null, Map.of(), EnvironmentPolicy.INHERIT, false);
    }

    /**
     * Creates an explicit operating-system shell command.
     *
     * <p>Unix systems use {@code /bin/sh -c}; Windows uses the trusted system {@code cmd.exe /d /s /c}. Syntax and
     * quoting therefore depend on the platform. The whole string is interpreted: do not concatenate untrusted values
     * into it. Prefer {@link #of(String)} with separate arguments when shell features are unnecessary.
     *
     * <p>A shell specification does not accept additional arguments, including arguments added through a scenario
     * draft. Working-directory and environment settings remain available.
     *
     * @param commandLine non-blank shell command line without NUL characters
     * @return shell command specification
     * @throws IllegalArgumentException if {@code commandLine} is blank or contains NUL
     */
    public static CommandSpec shell(String commandLine) {
        return new CommandSpec(commandLine, List.of(), null, Map.of(), EnvironmentPolicy.INHERIT, true);
    }

    /**
     * Appends one base argument.
     *
     * @param argument literal argument to append; empty strings are allowed, NUL characters are not
     * @return updated command specification
     * @throws IllegalArgumentException if this is a shell specification or {@code argument} contains NUL
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
     * @param arguments literal arguments to append; empty strings are allowed, NUL characters are not
     * @return updated command specification
     * @throws IllegalArgumentException if any argument contains NUL, or a non-empty array is supplied to a shell
     *     specification
     */
    public CommandSpec withArgs(String... arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return withArgs(Arrays.asList(arguments));
    }

    /**
     * Appends base arguments after copying the caller collection.
     *
     * @param arguments literal arguments to append in iteration order; empty strings are allowed, NUL characters are not
     * @return updated command specification
     * @throws IllegalArgumentException if any argument contains NUL, or a non-empty collection is supplied to a shell
     *     specification
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
     * <p>The path is retained without checking existence. An unusable directory causes process launch to fail.
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
     * @param name non-blank variable name without NUL or {@code =}
     * @param value variable value without NUL; an empty string sets an empty value rather than removing the variable
     * @return updated command specification
     * @throws IllegalArgumentException if the name or value violates these restrictions
     */
    public CommandSpec withEnvironment(String name, String value) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(environment);
        updated.put(CommandValidation.requireEnvironmentName(name), CommandValidation.requireEnvironmentValue(value));
        return copy(arguments, workingDirectory, Map.copyOf(updated), environmentPolicy);
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
     * <p>Previously configured overrides are retained. The platform or an explicitly selected shell may supply its
     * own mandatory variables; this setting controls the environment Procwright gives to the process launcher.
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

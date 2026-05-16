package com.github.ulviar.icli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable base command configuration shared by command invocations.
 */
public final class CommandSpec {

    private final String executable;
    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final boolean shell;

    private CommandSpec(Builder builder) {
        executable = builder.executable;
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        shell = builder.shell;
    }

    /**
     * Creates a command specification for an executable with no base arguments.
     *
     * @param executable executable name or path
     * @return immutable command specification
     */
    public static CommandSpec of(String executable) {
        return builder(executable).build();
    }

    /**
     * Creates a shell command specification.
     *
     * @param commandLine command line interpreted by the system shell
     * @return immutable shell command specification
     */
    public static CommandSpec shell(String commandLine) {
        return new Builder(commandLine, true).build();
    }

    /**
     * Creates a command specification builder.
     *
     * @param executable executable name or path
     * @return mutable command specification builder
     */
    public static Builder builder(String executable) {
        return new Builder(executable);
    }

    /**
     * Returns the direct executable name/path or explicit shell command line.
     *
     * @return direct executable name/path or shell command line
     */
    public String executable() {
        return executable;
    }

    /**
     * Returns immutable base arguments.
     *
     * @return base arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Returns the base working directory override.
     *
     * @return working directory when configured
     */
    public Optional<Path> workingDirectory() {
        return Optional.ofNullable(workingDirectory);
    }

    /**
     * Returns immutable base environment overrides.
     *
     * @return environment overrides
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns whether this command is interpreted by the system shell.
     *
     * @return {@code true} for explicit shell commands
     */
    public boolean usesShell() {
        return shell;
    }

    @Override
    public boolean equals(Object other) {
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
                && shell == that.shell;
    }

    @Override
    public int hashCode() {
        return Objects.hash(executable, arguments, workingDirectory, environment, shell);
    }

    /**
     * Mutable builder for {@link CommandSpec}.
     */
    public static final class Builder {

        private final String executable;
        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private final boolean shell;
        private Path workingDirectory;

        private Builder(String executable) {
            this(executable, false);
        }

        private Builder(String executable, boolean shell) {
            this.executable = requireText(executable, "executable");
            this.shell = shell;
        }

        /**
         * Adds one base argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder arg(String argument) {
            arguments.add(requireArgument(argument));
            return this;
        }

        /**
         * Adds base arguments.
         *
         * @param arguments argument values
         * @return this builder
         */
        public Builder args(String... arguments) {
            Objects.requireNonNull(arguments, "arguments");
            for (String argument : arguments) {
                arg(argument);
            }
            return this;
        }

        /**
         * Adds base arguments.
         *
         * @param arguments argument values
         * @return this builder
         */
        public Builder args(Collection<String> arguments) {
            Objects.requireNonNull(arguments, "arguments");
            for (String argument : arguments) {
                arg(argument);
            }
            return this;
        }

        /**
         * Sets the base working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            return this;
        }

        /**
         * Adds or replaces one base environment override.
         *
         * @param name environment variable name
         * @param value environment variable value
         * @return this builder
         */
        public Builder putEnvironment(String name, String value) {
            environment.put(requireEnvironmentName(name), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Builds an immutable command specification.
         *
         * @return immutable command specification
         */
        public CommandSpec build() {
            return new CommandSpec(this);
        }
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String requireArgument(String argument) {
        return Objects.requireNonNull(argument, "argument");
    }

    static String requireEnvironmentName(String name) {
        requireText(name, "name");
        if (name.indexOf('=') >= 0) {
            throw new IllegalArgumentException("environment name must not contain '='");
        }
        return name;
    }
}

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
 * Per-call overrides for a one-shot command invocation.
 */
public final class CommandInvocation {

    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final CapturePolicy capturePolicy;
    private final ShutdownPolicy shutdownPolicy;

    private CommandInvocation(Builder builder) {
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        capturePolicy = builder.capturePolicy;
        shutdownPolicy = builder.shutdownPolicy;
    }

    /**
     * Creates a per-call invocation builder.
     *
     * @return invocation builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns immutable per-call arguments.
     *
     * @return per-call arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Returns the per-call working directory override.
     *
     * @return working directory when configured
     */
    public Optional<Path> workingDirectory() {
        return Optional.ofNullable(workingDirectory);
    }

    /**
     * Returns immutable per-call environment overrides.
     *
     * @return environment overrides
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns the per-call capture policy override.
     *
     * @return capture policy when configured
     */
    public Optional<CapturePolicy> capturePolicy() {
        return Optional.ofNullable(capturePolicy);
    }

    /**
     * Returns the per-call shutdown policy override.
     *
     * @return shutdown policy when configured
     */
    public Optional<ShutdownPolicy> shutdownPolicy() {
        return Optional.ofNullable(shutdownPolicy);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CommandInvocation that)) {
            return false;
        }
        return arguments.equals(that.arguments)
                && Objects.equals(workingDirectory, that.workingDirectory)
                && environment.equals(that.environment)
                && Objects.equals(capturePolicy, that.capturePolicy)
                && Objects.equals(shutdownPolicy, that.shutdownPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(arguments, workingDirectory, environment, capturePolicy, shutdownPolicy);
    }

    /**
     * Mutable builder used by scenario callbacks.
     */
    public static final class Builder {

        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private CapturePolicy capturePolicy;
        private ShutdownPolicy shutdownPolicy;

        private Builder() {}

        /**
         * Adds one per-call argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder arg(String argument) {
            arguments.add(CommandSpec.requireArgument(argument));
            return this;
        }

        /**
         * Adds per-call arguments.
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
         * Adds per-call arguments.
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
         * Sets the per-call working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            return this;
        }

        /**
         * Adds or replaces one per-call environment override.
         *
         * @param name environment variable name
         * @param value environment variable value
         * @return this builder
         */
        public Builder putEnvironment(String name, String value) {
            environment.put(CommandSpec.requireEnvironmentName(name), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets the per-call capture policy override.
         *
         * @param capturePolicy capture policy
         * @return this builder
         */
        public Builder capture(CapturePolicy capturePolicy) {
            this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
            return this;
        }

        /**
         * Sets the per-call shutdown policy override.
         *
         * @param shutdownPolicy shutdown policy
         * @return this builder
         */
        public Builder shutdown(ShutdownPolicy shutdownPolicy) {
            this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            return this;
        }

        /**
         * Builds an immutable invocation draft.
         *
         * @return immutable invocation draft
         */
        public CommandInvocation build() {
            return new CommandInvocation(this);
        }
    }
}

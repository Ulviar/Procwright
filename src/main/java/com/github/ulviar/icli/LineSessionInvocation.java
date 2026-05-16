package com.github.ulviar.icli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-session launch overrides for a line-oriented command session.
 */
public final class LineSessionInvocation {

    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final ShutdownPolicy shutdownPolicy;
    private final Duration idleTimeout;

    private LineSessionInvocation(Builder builder) {
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        shutdownPolicy = builder.shutdownPolicy;
        idleTimeout = builder.idleTimeout;
    }

    /**
     * Creates a per-line-session invocation builder.
     *
     * @return line-session invocation builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns immutable per-session arguments.
     *
     * @return per-session arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Returns the per-session working directory override.
     *
     * @return working directory when configured
     */
    public Optional<Path> workingDirectory() {
        return Optional.ofNullable(workingDirectory);
    }

    /**
     * Returns immutable per-session environment overrides.
     *
     * @return environment overrides
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns the per-session shutdown policy override.
     *
     * @return shutdown policy when configured
     */
    public Optional<ShutdownPolicy> shutdownPolicy() {
        return Optional.ofNullable(shutdownPolicy);
    }

    /**
     * Returns the per-session caller-visible idle timeout override.
     *
     * @return idle timeout when configured
     */
    public Optional<Duration> idleTimeout() {
        return Optional.ofNullable(idleTimeout);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LineSessionInvocation that)) {
            return false;
        }
        return arguments.equals(that.arguments)
                && Objects.equals(workingDirectory, that.workingDirectory)
                && environment.equals(that.environment)
                && Objects.equals(shutdownPolicy, that.shutdownPolicy)
                && Objects.equals(idleTimeout, that.idleTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(arguments, workingDirectory, environment, shutdownPolicy, idleTimeout);
    }

    /**
     * Mutable builder used by line-session callbacks.
     */
    public static final class Builder {

        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private ShutdownPolicy shutdownPolicy;
        private Duration idleTimeout;

        private Builder() {}

        /**
         * Adds one per-session argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder arg(String argument) {
            arguments.add(CommandSpec.requireArgument(argument));
            return this;
        }

        /**
         * Adds per-session arguments.
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
         * Adds per-session arguments.
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
         * Sets the per-session working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            return this;
        }

        /**
         * Adds or replaces one per-session environment override.
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
         * Sets the per-session shutdown policy override.
         *
         * @param shutdownPolicy shutdown policy
         * @return this builder
         */
        public Builder shutdown(ShutdownPolicy shutdownPolicy) {
            this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            return this;
        }

        /**
         * Sets the per-session caller-visible idle timeout override.
         *
         * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
         * @return this builder
         */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
            return this;
        }

        /**
         * Builds an immutable line-session invocation draft.
         *
         * @return immutable line-session invocation draft
         */
        public LineSessionInvocation build() {
            return new LineSessionInvocation(this);
        }

        private static Duration requireNonNegative(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return duration;
        }
    }
}

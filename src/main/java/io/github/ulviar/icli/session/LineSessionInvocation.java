package io.github.ulviar.icli.session;

import io.github.ulviar.icli.command.EnvironmentPolicy;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.internal.CommandValidation;
import io.github.ulviar.icli.terminal.TerminalPolicy;
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
import java.util.function.Consumer;

/**
 * Per-session launch overrides for a line-oriented command session.
 */
public final class LineSessionInvocation {

    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final EnvironmentPolicy environmentPolicy;
    private final ShutdownPolicy shutdownPolicy;
    private final Duration idleTimeout;
    private final TerminalPolicy terminalPolicy;
    private final Consumer<LineSession> readinessProbe;
    private final Duration readinessTimeout;

    private LineSessionInvocation(Builder builder) {
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        environmentPolicy = builder.environmentPolicy;
        shutdownPolicy = builder.shutdownPolicy;
        idleTimeout = builder.idleTimeout;
        terminalPolicy = builder.terminalPolicy;
        readinessProbe = builder.readinessProbe;
        readinessTimeout = builder.readinessTimeout;
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
     * Returns the per-session environment policy override.
     *
     * @return environment policy when configured
     */
    public Optional<EnvironmentPolicy> environmentPolicy() {
        return Optional.ofNullable(environmentPolicy);
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

    /**
     * Returns the per-session terminal policy override.
     *
     * @return terminal policy when configured
     */
    public Optional<TerminalPolicy> terminalPolicy() {
        return Optional.ofNullable(terminalPolicy);
    }

    /**
     * Returns the per-session readiness probe.
     *
     * @return readiness probe when configured
     */
    public Optional<Consumer<LineSession>> readinessProbe() {
        return Optional.ofNullable(readinessProbe);
    }

    /**
     * Returns the per-session readiness timeout.
     *
     * @return readiness timeout when configured
     */
    public Optional<Duration> readinessTimeout() {
        return Optional.ofNullable(readinessTimeout);
    }

    /**
     * Mutable builder used by line-session callbacks.
     */
    public static final class Builder {

        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private EnvironmentPolicy environmentPolicy;
        private ShutdownPolicy shutdownPolicy;
        private Duration idleTimeout;
        private TerminalPolicy terminalPolicy;
        private Consumer<LineSession> readinessProbe;
        private Duration readinessTimeout;

        private Builder() {}

        /**
         * Adds one per-session argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder arg(String argument) {
            arguments.add(CommandValidation.requireArgument(argument));
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
            environment.put(
                    CommandValidation.requireEnvironmentName(name), CommandValidation.requireEnvironmentValue(value));
            return this;
        }

        /**
         * Inherits the current process environment before applying configured overrides for this session.
         *
         * @return this builder
         */
        public Builder inheritEnvironment() {
            environmentPolicy = EnvironmentPolicy.INHERIT;
            return this;
        }

        /**
         * Starts this session with only configured environment overrides.
         *
         * @return this builder
         */
        public Builder cleanEnvironment() {
            environmentPolicy = EnvironmentPolicy.CLEAN;
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
         * Sets the per-session terminal policy override.
         *
         * @param terminalPolicy terminal policy
         * @return this builder
         */
        public Builder terminal(TerminalPolicy terminalPolicy) {
            this.terminalPolicy = Objects.requireNonNull(terminalPolicy, "terminalPolicy");
            return this;
        }

        /**
         * Sets a readiness probe that runs after launch and before this line session is returned.
         *
         * @param readinessProbe readiness probe
         * @return this builder
         */
        public Builder readiness(Consumer<LineSession> readinessProbe) {
            this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe");
            return this;
        }

        /**
         * Sets the readiness probe timeout.
         *
         * @param readinessTimeout readiness timeout
         * @return this builder
         */
        public Builder readinessTimeout(Duration readinessTimeout) {
            this.readinessTimeout = requirePositive(readinessTimeout, "readinessTimeout");
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

        private static Duration requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return duration;
        }
    }
}

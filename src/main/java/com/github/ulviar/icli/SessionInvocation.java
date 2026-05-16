package com.github.ulviar.icli;

import java.nio.charset.Charset;
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
 * Per-session launch overrides for an interactive command session.
 */
public final class SessionInvocation {

    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final ShutdownPolicy shutdownPolicy;
    private final Duration idleTimeout;
    private final Charset charset;
    private final TerminalPolicy terminalPolicy;

    private SessionInvocation(Builder builder) {
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        shutdownPolicy = builder.shutdownPolicy;
        idleTimeout = builder.idleTimeout;
        charset = builder.charset;
        terminalPolicy = builder.terminalPolicy;
    }

    /**
     * Creates a per-session invocation builder.
     *
     * @return session invocation builder
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

    /**
     * Returns the per-session text-send charset override.
     *
     * @return charset when configured
     */
    public Optional<Charset> charset() {
        return Optional.ofNullable(charset);
    }

    /**
     * Returns the per-session terminal policy override.
     *
     * @return terminal policy when configured
     */
    public Optional<TerminalPolicy> terminalPolicy() {
        return Optional.ofNullable(terminalPolicy);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionInvocation that)) {
            return false;
        }
        return arguments.equals(that.arguments)
                && Objects.equals(workingDirectory, that.workingDirectory)
                && environment.equals(that.environment)
                && Objects.equals(shutdownPolicy, that.shutdownPolicy)
                && Objects.equals(idleTimeout, that.idleTimeout)
                && Objects.equals(charset, that.charset)
                && terminalPolicy == that.terminalPolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                arguments, workingDirectory, environment, shutdownPolicy, idleTimeout, charset, terminalPolicy);
    }

    /**
     * Mutable builder used by interactive-session callbacks.
     */
    public static final class Builder {

        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private ShutdownPolicy shutdownPolicy;
        private Duration idleTimeout;
        private Charset charset;
        private TerminalPolicy terminalPolicy;

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
         * Sets the per-session text-send charset override.
         *
         * @param charset text-send charset
         * @return this builder
         */
        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "charset");
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
         * Builds an immutable session invocation draft.
         *
         * @return immutable session invocation draft
         */
        public SessionInvocation build() {
            return new SessionInvocation(this);
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

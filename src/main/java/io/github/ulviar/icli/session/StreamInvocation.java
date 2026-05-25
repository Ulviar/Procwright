package io.github.ulviar.icli.session;

import io.github.ulviar.icli.command.EnvironmentPolicy;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.internal.CommandValidation;
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
 * Per-listen launch and listener overrides for a streaming command.
 */
public final class StreamInvocation {

    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final EnvironmentPolicy environmentPolicy;
    private final ShutdownPolicy shutdownPolicy;
    private final Duration timeout;
    private final StreamStdinPolicy stdinPolicy;
    private final StreamListener listener;

    private StreamInvocation(Builder builder) {
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        environmentPolicy = builder.environmentPolicy;
        shutdownPolicy = builder.shutdownPolicy;
        timeout = builder.timeout;
        stdinPolicy = builder.stdinPolicy;
        listener = builder.listener;
    }

    /**
     * Creates a streaming invocation builder.
     *
     * @return stream invocation builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns immutable per-stream arguments.
     *
     * @return per-stream arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Returns the per-stream working directory override.
     *
     * @return working directory when configured
     */
    public Optional<Path> workingDirectory() {
        return Optional.ofNullable(workingDirectory);
    }

    /**
     * Returns immutable per-stream environment overrides.
     *
     * @return environment overrides
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns the per-stream environment policy override.
     *
     * @return environment policy when configured
     */
    public Optional<EnvironmentPolicy> environmentPolicy() {
        return Optional.ofNullable(environmentPolicy);
    }

    /**
     * Returns the per-stream shutdown policy override.
     *
     * @return shutdown policy when configured
     */
    public Optional<ShutdownPolicy> shutdownPolicy() {
        return Optional.ofNullable(shutdownPolicy);
    }

    /**
     * Returns the per-stream timeout override.
     *
     * @return timeout when configured
     */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Returns stdin handling for this streaming invocation.
     *
     * @return stdin policy
     */
    public StreamStdinPolicy stdinPolicy() {
        return stdinPolicy;
    }

    /**
     * Returns the streaming output listener.
     *
     * @return output listener
     */
    public StreamListener listener() {
        return listener;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamInvocation that)) {
            return false;
        }
        return arguments.equals(that.arguments)
                && Objects.equals(workingDirectory, that.workingDirectory)
                && environment.equals(that.environment)
                && environmentPolicy == that.environmentPolicy
                && Objects.equals(shutdownPolicy, that.shutdownPolicy)
                && Objects.equals(timeout, that.timeout)
                && stdinPolicy == that.stdinPolicy
                && listener.equals(that.listener);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                arguments,
                workingDirectory,
                environment,
                environmentPolicy,
                shutdownPolicy,
                timeout,
                stdinPolicy,
                listener);
    }

    /**
     * Mutable builder used by listen callbacks.
     */
    public static final class Builder {

        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private EnvironmentPolicy environmentPolicy;
        private ShutdownPolicy shutdownPolicy;
        private Duration timeout;
        private StreamStdinPolicy stdinPolicy = StreamStdinPolicy.CLOSE_ON_START;
        private StreamListener listener = StreamListener.noop();

        private Builder() {}

        /**
         * Adds one per-stream argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder arg(String argument) {
            arguments.add(CommandValidation.requireArgument(argument));
            return this;
        }

        /**
         * Adds per-stream arguments.
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
         * Adds per-stream arguments.
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
         * Sets the per-stream working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            return this;
        }

        /**
         * Adds or replaces one per-stream environment override.
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
         * Inherits the current process environment before applying configured overrides for this stream.
         *
         * @return this builder
         */
        public Builder inheritEnvironment() {
            environmentPolicy = EnvironmentPolicy.INHERIT;
            return this;
        }

        /**
         * Starts this stream with only configured environment overrides.
         *
         * @return this builder
         */
        public Builder cleanEnvironment() {
            environmentPolicy = EnvironmentPolicy.CLEAN;
            return this;
        }

        /**
         * Sets the per-stream shutdown policy override.
         *
         * @param shutdownPolicy shutdown policy
         * @return this builder
         */
        public Builder shutdown(ShutdownPolicy shutdownPolicy) {
            this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            return this;
        }

        /**
         * Sets the per-stream absolute timeout override.
         *
         * @param timeout absolute timeout, or {@link Duration#ZERO} to disable it
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = requireNonNegative(timeout, "timeout");
            return this;
        }

        /**
         * Keeps stdin open after the stream starts.
         *
         * @return this builder
         */
        public Builder keepStdinOpen() {
            stdinPolicy = StreamStdinPolicy.KEEP_OPEN;
            return this;
        }

        /**
         * Closes stdin immediately after the stream starts.
         *
         * @return this builder
         */
        public Builder closeStdinOnStart() {
            stdinPolicy = StreamStdinPolicy.CLOSE_ON_START;
            return this;
        }

        /**
         * Sets the output listener.
         *
         * @param listener output listener
         * @return this builder
         */
        public Builder onOutput(StreamListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Builds an immutable stream invocation draft.
         *
         * @return immutable stream invocation draft
         */
        public StreamInvocation build() {
            return new StreamInvocation(this);
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

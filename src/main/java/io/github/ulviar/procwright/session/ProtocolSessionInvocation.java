package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.CommandValidation;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
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
 * Per-session launch and protocol overrides for a generic request/response session.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class ProtocolSessionInvocation<I, O> {

    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final EnvironmentPolicy environmentPolicy;
    private final ShutdownPolicy shutdownPolicy;
    private final Duration idleTimeout;
    private final TerminalPolicy terminalPolicy;
    private final ProtocolSessionOptions options;
    private final Consumer<ProtocolSession<I, O>> readinessProbe;
    private final Duration readinessTimeout;

    private ProtocolSessionInvocation(Builder<I, O> builder) {
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        environmentPolicy = builder.environmentPolicy;
        shutdownPolicy = builder.shutdownPolicy;
        idleTimeout = builder.idleTimeout;
        terminalPolicy = builder.terminalPolicy;
        options = builder.options();
        readinessProbe = builder.readinessProbe;
        readinessTimeout = builder.readinessTimeout;
    }

    /**
     * Creates a protocol-session invocation builder.
     *
     * @param <I> request type
     * @param <O> response type
     * @return protocol-session invocation builder
     */
    public static <I, O> Builder<I, O> builder() {
        return new Builder<>(ProtocolSessionOptions.defaults());
    }

    /**
     * Creates a protocol-session invocation builder from default options.
     *
     * @param options default protocol options
     * @param <I> request type
     * @param <O> response type
     * @return protocol-session invocation builder
     */
    public static <I, O> Builder<I, O> builder(ProtocolSessionOptions options) {
        return new Builder<>(options);
    }

    /**
     * Returns per-session arguments.
     *
     * @return immutable arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Returns per-session working directory override.
     *
     * @return working directory override
     */
    public Optional<Path> workingDirectory() {
        return Optional.ofNullable(workingDirectory);
    }

    /**
     * Returns per-session environment overrides.
     *
     * @return immutable environment overrides
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns per-session environment policy override.
     *
     * @return environment policy override
     */
    public Optional<EnvironmentPolicy> environmentPolicy() {
        return Optional.ofNullable(environmentPolicy);
    }

    /**
     * Returns per-session shutdown policy override.
     *
     * @return shutdown policy override
     */
    public Optional<ShutdownPolicy> shutdownPolicy() {
        return Optional.ofNullable(shutdownPolicy);
    }

    /**
     * Returns per-session idle timeout override.
     *
     * @return idle timeout override
     */
    public Optional<Duration> idleTimeout() {
        return Optional.ofNullable(idleTimeout);
    }

    /**
     * Returns per-session terminal policy override.
     *
     * @return terminal policy override
     */
    public Optional<TerminalPolicy> terminalPolicy() {
        return Optional.ofNullable(terminalPolicy);
    }

    /**
     * Returns protocol-session options.
     *
     * @return protocol-session options
     */
    public ProtocolSessionOptions options() {
        return options;
    }

    /**
     * Returns readiness probe configured for this invocation.
     *
     * @return readiness probe
     */
    public Optional<Consumer<ProtocolSession<I, O>>> readinessProbe() {
        return Optional.ofNullable(readinessProbe);
    }

    /**
     * Returns readiness timeout configured for this invocation.
     *
     * @return readiness timeout
     */
    public Optional<Duration> readinessTimeout() {
        return Optional.ofNullable(readinessTimeout);
    }

    /**
     * Mutable builder used by protocol-session callbacks.
     *
     * @param <I> request type
     * @param <O> response type
     */
    public static final class Builder<I, O> {

        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private EnvironmentPolicy environmentPolicy;
        private ShutdownPolicy shutdownPolicy;
        private Duration idleTimeout;
        private TerminalPolicy terminalPolicy;
        private Duration requestTimeout;
        private int transcriptLimit;
        private int stdoutBacklogLimit;
        private int maxRequestBytes;
        private int maxRequestChars;
        private int maxResponseBytes;
        private int maxResponseChars;
        private CharsetPolicy charsetPolicy;
        private Consumer<ProtocolSession<I, O>> readinessProbe;
        private Duration readinessTimeout;

        private Builder(ProtocolSessionOptions options) {
            ProtocolSessionOptions defaults = Objects.requireNonNull(options, "options");
            requestTimeout = defaults.requestTimeout();
            transcriptLimit = defaults.transcriptLimit();
            stdoutBacklogLimit = defaults.stdoutBacklogLimit();
            maxRequestBytes = defaults.maxRequestBytes();
            maxRequestChars = defaults.maxRequestChars();
            maxResponseBytes = defaults.maxResponseBytes();
            maxResponseChars = defaults.maxResponseChars();
            charsetPolicy = defaults.charsetPolicy();
        }

        /**
         * Adds one per-session argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder<I, O> arg(String argument) {
            arguments.add(CommandValidation.requireArgument(argument));
            return this;
        }

        /**
         * Adds per-session arguments.
         *
         * @param arguments argument values
         * @return this builder
         */
        public Builder<I, O> args(String... arguments) {
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
        public Builder<I, O> args(Collection<String> arguments) {
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
        public Builder<I, O> workingDirectory(Path workingDirectory) {
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
        public Builder<I, O> putEnvironment(String name, String value) {
            environment.put(
                    CommandValidation.requireEnvironmentName(name), CommandValidation.requireEnvironmentValue(value));
            return this;
        }

        /**
         * Inherits the current process environment before applying configured overrides.
         *
         * @return this builder
         */
        public Builder<I, O> inheritEnvironment() {
            environmentPolicy = EnvironmentPolicy.INHERIT;
            return this;
        }

        /**
         * Starts the process with only configured environment overrides.
         *
         * @return this builder
         */
        public Builder<I, O> cleanEnvironment() {
            environmentPolicy = EnvironmentPolicy.CLEAN;
            return this;
        }

        /**
         * Sets the per-session shutdown policy override.
         *
         * @param shutdownPolicy shutdown policy
         * @return this builder
         */
        public Builder<I, O> shutdown(ShutdownPolicy shutdownPolicy) {
            this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            return this;
        }

        /**
         * Sets the per-session caller-visible idle timeout override.
         *
         * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
         * @return this builder
         */
        public Builder<I, O> idleTimeout(Duration idleTimeout) {
            this.idleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
            return this;
        }

        /**
         * Sets the per-session terminal policy override.
         *
         * @param terminalPolicy terminal policy
         * @return this builder
         */
        public Builder<I, O> terminal(TerminalPolicy terminalPolicy) {
            this.terminalPolicy = Objects.requireNonNull(terminalPolicy, "terminalPolicy");
            return this;
        }

        /**
         * Sets the default request timeout.
         *
         * @param requestTimeout request timeout
         * @return this builder
         */
        public Builder<I, O> requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        /**
         * Sets the retained transcript character limit.
         *
         * @param transcriptLimit transcript limit
         * @return this builder
         */
        public Builder<I, O> transcriptLimit(int transcriptLimit) {
            this.transcriptLimit = requirePositive(transcriptLimit, "transcriptLimit");
            return this;
        }

        /**
         * Sets the maximum pending output bytes per stream.
         *
         * @param stdoutBacklogLimit output backlog limit
         * @return this builder
         */
        public Builder<I, O> stdoutBacklogLimit(int stdoutBacklogLimit) {
            this.stdoutBacklogLimit = requirePositive(stdoutBacklogLimit, "stdoutBacklogLimit");
            return this;
        }

        /**
         * Sets the maximum pending output bytes per stream.
         *
         * @param outputBacklogLimit output backlog limit
         * @return this builder
         */
        public Builder<I, O> outputBacklogLimit(int outputBacklogLimit) {
            return stdoutBacklogLimit(outputBacklogLimit);
        }

        /**
         * Sets the request byte limit.
         *
         * @param maxRequestBytes request byte limit
         * @return this builder
         */
        public Builder<I, O> maxRequestBytes(int maxRequestBytes) {
            this.maxRequestBytes = requirePositive(maxRequestBytes, "maxRequestBytes");
            return this;
        }

        /**
         * Sets the request character limit for text writes.
         *
         * @param maxRequestChars request character limit
         * @return this builder
         */
        public Builder<I, O> maxRequestChars(int maxRequestChars) {
            this.maxRequestChars = requirePositive(maxRequestChars, "maxRequestChars");
            return this;
        }

        /**
         * Sets the response byte limit.
         *
         * @param maxResponseBytes response byte limit
         * @return this builder
         */
        public Builder<I, O> maxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = requirePositive(maxResponseBytes, "maxResponseBytes");
            return this;
        }

        /**
         * Sets the response character limit for text reads.
         *
         * @param maxResponseChars response character limit
         * @return this builder
         */
        public Builder<I, O> maxResponseChars(int maxResponseChars) {
            this.maxResponseChars = requirePositive(maxResponseChars, "maxResponseChars");
            return this;
        }

        /**
         * Sets the protocol text charset policy.
         *
         * @param charsetPolicy charset policy
         * @return this builder
         */
        public Builder<I, O> charsetPolicy(CharsetPolicy charsetPolicy) {
            this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
            return this;
        }

        /**
         * Sets the readiness probe run after launch and before returning the protocol session.
         *
         * @param readinessProbe readiness probe
         * @return this builder
         */
        public Builder<I, O> readiness(Consumer<ProtocolSession<I, O>> readinessProbe) {
            this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe");
            return this;
        }

        /**
         * Sets the readiness probe timeout.
         *
         * @param readinessTimeout readiness timeout
         * @return this builder
         */
        public Builder<I, O> readinessTimeout(Duration readinessTimeout) {
            this.readinessTimeout = requirePositive(readinessTimeout, "readinessTimeout");
            return this;
        }

        /**
         * Builds an immutable protocol-session invocation draft.
         *
         * @return immutable invocation draft
         */
        public ProtocolSessionInvocation<I, O> build() {
            return new ProtocolSessionInvocation<>(this);
        }

        private ProtocolSessionOptions options() {
            return new ProtocolSessionOptions(
                    requestTimeout,
                    transcriptLimit,
                    stdoutBacklogLimit,
                    maxRequestBytes,
                    maxRequestChars,
                    maxResponseBytes,
                    maxResponseChars,
                    charsetPolicy);
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static Duration requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return duration;
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

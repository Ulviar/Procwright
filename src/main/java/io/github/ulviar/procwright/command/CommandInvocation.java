/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import io.github.ulviar.procwright.internal.CommandValidation;
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
 * Per-call overrides for a one-shot command invocation.
 */
public final class CommandInvocation {

    private final List<String> arguments;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final EnvironmentPolicy environmentPolicy;
    private final CapturePolicy capturePolicy;
    private final ShutdownPolicy shutdownPolicy;
    private final Duration timeout;
    private final CharsetPolicy charsetPolicy;
    private final OutputMode outputMode;
    private final CommandInput input;

    private CommandInvocation(Builder builder) {
        arguments = List.copyOf(builder.arguments);
        workingDirectory = builder.workingDirectory;
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        environmentPolicy = builder.environmentPolicy;
        capturePolicy = builder.capturePolicy;
        shutdownPolicy = builder.shutdownPolicy;
        timeout = builder.timeout;
        charsetPolicy = builder.charsetPolicy;
        outputMode = builder.outputMode;
        input = builder.input;
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
     * Returns the per-call environment policy override.
     *
     * @return environment policy when configured
     */
    public Optional<EnvironmentPolicy> environmentPolicy() {
        return Optional.ofNullable(environmentPolicy);
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

    /**
     * Returns the per-call timeout override.
     *
     * @return timeout when configured
     */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Returns the per-call charset override.
     *
     * @return charset when configured
     */
    public Optional<Charset> charset() {
        return charsetPolicy().map(CharsetPolicy::charset);
    }

    /**
     * Returns the per-call charset decoding policy override.
     *
     * @return charset policy when configured
     */
    public Optional<CharsetPolicy> charsetPolicy() {
        return Optional.ofNullable(charsetPolicy);
    }

    /**
     * Returns the per-call output mode override.
     *
     * @return output mode when configured
     */
    public Optional<OutputMode> outputMode() {
        return Optional.ofNullable(outputMode);
    }

    /**
     * Returns the per-call stdin input override.
     *
     * @return input text when configured
     */
    public Optional<CommandInput> input() {
        return Optional.ofNullable(input);
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
                && environmentPolicy == that.environmentPolicy
                && Objects.equals(capturePolicy, that.capturePolicy)
                && Objects.equals(shutdownPolicy, that.shutdownPolicy)
                && Objects.equals(timeout, that.timeout)
                && Objects.equals(charsetPolicy, that.charsetPolicy)
                && Objects.equals(outputMode, that.outputMode)
                && Objects.equals(input, that.input);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                arguments,
                workingDirectory,
                environment,
                environmentPolicy,
                capturePolicy,
                shutdownPolicy,
                timeout,
                charsetPolicy,
                outputMode,
                input);
    }

    /**
     * Mutable builder used by scenario callbacks.
     */
    public static final class Builder {

        private final ArrayList<String> arguments = new ArrayList<>();
        private final LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private EnvironmentPolicy environmentPolicy;
        private CapturePolicy capturePolicy;
        private ShutdownPolicy shutdownPolicy;
        private Duration timeout;
        private CharsetPolicy charsetPolicy;
        private OutputMode outputMode;
        private CommandInput input;

        private Builder() {}

        /**
         * Adds one per-call argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder arg(String argument) {
            arguments.add(CommandValidation.requireArgument(argument));
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
            environment.put(
                    CommandValidation.requireEnvironmentName(name), CommandValidation.requireEnvironmentValue(value));
            return this;
        }

        /**
         * Inherits the current process environment before applying configured overrides for this call.
         *
         * @return this builder
         */
        public Builder inheritEnvironment() {
            environmentPolicy = EnvironmentPolicy.INHERIT;
            return this;
        }

        /**
         * Starts this call with only configured environment overrides.
         *
         * @return this builder
         */
        public Builder cleanEnvironment() {
            environmentPolicy = EnvironmentPolicy.CLEAN;
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
         * Sets the per-call timeout override.
         *
         * @param timeout timeout, or {@link Duration#ZERO} to disable the run timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = requireNonNegative(timeout, "timeout");
            return this;
        }

        /**
         * Sets the per-call output charset override.
         *
         * @param charset output charset
         * @return this builder
         */
        public Builder charset(Charset charset) {
            this.charsetPolicy = CharsetPolicy.replace(charset);
            return this;
        }

        /**
         * Sets the per-call output charset decoding policy override.
         *
         * @param charsetPolicy output charset policy
         * @return this builder
         */
        public Builder charsetPolicy(CharsetPolicy charsetPolicy) {
            this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
            return this;
        }

        /**
         * Sets the per-call output mode override.
         *
         * @param outputMode output mode
         * @return this builder
         */
        public Builder output(OutputMode outputMode) {
            this.outputMode = Objects.requireNonNull(outputMode, "outputMode");
            return this;
        }

        /**
         * Sets stdin text that will be written before stdin is closed.
         *
         * @param input stdin text
         * @return this builder
         */
        public Builder input(String input) {
            this.input = CommandInput.utf8(input);
            return this;
        }

        /**
         * Sets stdin text that will be written with the provided charset.
         *
         * @param input stdin text
         * @param charset input charset
         * @return this builder
         */
        public Builder input(String input, Charset charset) {
            this.input = CommandInput.text(input, charset);
            return this;
        }

        /**
         * Sets explicit stdin bytes.
         *
         * @param input command input
         * @return this builder
         */
        public Builder input(CommandInput input) {
            this.input = Objects.requireNonNull(input, "input");
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

        private static Duration requireNonNegative(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return duration;
        }
    }
}

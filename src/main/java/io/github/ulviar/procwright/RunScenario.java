/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandInvocation;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configures and executes a finite process run.
 */
public final class RunScenario {

    private final CommandService service;
    private final Consumer<CommandInvocation.Builder> configure;

    RunScenario(CommandService service) {
        this(service, builder -> {});
    }

    private RunScenario(CommandService service, Consumer<CommandInvocation.Builder> configure) {
        this.service = Objects.requireNonNull(service, "service");
        this.configure = Objects.requireNonNull(configure, "configure");
    }

    /**
     * Returns a copy with one additional argv argument.
     *
     * @param argument argument value
     * @return updated scenario
     */
    public RunScenario withArg(String argument) {
        return with(builder -> builder.arg(argument));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public RunScenario withArgs(String... arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public RunScenario withArgs(Collection<String> arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with a per-call working directory.
     *
     * @param workingDirectory working directory
     * @return updated scenario
     */
    public RunScenario withWorkingDirectory(Path workingDirectory) {
        return with(builder -> builder.workingDirectory(workingDirectory));
    }

    /**
     * Returns a copy with one environment override.
     *
     * @param name environment variable name
     * @param value environment variable value
     * @return updated scenario
     */
    public RunScenario withEnvironment(String name, String value) {
        return with(builder -> builder.putEnvironment(name, value));
    }

    /**
     * Returns a copy that inherits the current process environment before applying overrides.
     *
     * @return updated scenario
     */
    public RunScenario withInheritedEnvironment() {
        return with(CommandInvocation.Builder::inheritEnvironment);
    }

    /**
     * Returns a copy that starts with only configured environment overrides.
     *
     * @return updated scenario
     */
    public RunScenario withCleanEnvironment() {
        return with(CommandInvocation.Builder::cleanEnvironment);
    }

    /**
     * Returns a copy with a per-call capture policy.
     *
     * @param capturePolicy capture policy
     * @return updated scenario
     */
    public RunScenario withCapture(CapturePolicy capturePolicy) {
        return with(builder -> builder.capture(capturePolicy));
    }

    /**
     * Returns a copy with a per-call shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated scenario
     */
    public RunScenario withShutdown(ShutdownPolicy shutdownPolicy) {
        return with(builder -> builder.shutdown(shutdownPolicy));
    }

    /**
     * Returns a copy with a per-call timeout.
     *
     * @param timeout timeout, or {@link Duration#ZERO} to disable the run timeout
     * @return updated scenario
     */
    public RunScenario withTimeout(Duration timeout) {
        return with(builder -> builder.timeout(timeout));
    }

    /**
     * Returns a copy with a per-call output charset using replacement decoding.
     *
     * @param charset output charset
     * @return updated scenario
     */
    public RunScenario withCharset(Charset charset) {
        return with(builder -> builder.charset(charset));
    }

    /**
     * Returns a copy with a per-call output charset policy.
     *
     * @param charsetPolicy output charset policy
     * @return updated scenario
     */
    public RunScenario withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return with(builder -> builder.charsetPolicy(charsetPolicy));
    }

    /**
     * Returns a copy with a per-call output mode.
     *
     * @param outputMode output mode
     * @return updated scenario
     */
    public RunScenario withOutput(OutputMode outputMode) {
        return with(builder -> builder.output(outputMode));
    }

    /**
     * Returns a copy with UTF-8 stdin text.
     *
     * @param input stdin text
     * @return updated scenario
     */
    public RunScenario withInput(String input) {
        return with(builder -> builder.input(input));
    }

    /**
     * Returns a copy with stdin text encoded by the provided charset.
     *
     * @param input stdin text
     * @param charset input charset
     * @return updated scenario
     */
    public RunScenario withInput(String input, Charset charset) {
        return with(builder -> builder.input(input, charset));
    }

    /**
     * Returns a copy with explicit stdin input.
     *
     * <p>Use {@link CommandInput#fromPath(java.nio.file.Path)} to stream stdin from a file without loading it into
     * memory.
     *
     * @param input command input
     * @return updated scenario
     */
    public RunScenario withInput(CommandInput input) {
        return with(builder -> builder.input(input));
    }

    /**
     * Returns a copy configured by a scenario-specific callback.
     *
     * <p>This is useful for reusable presets and integrations that already operate on invocation builders.
     *
     * @param configure configuration callback
     * @return updated scenario
     */
    public RunScenario configuredBy(Consumer<CommandInvocation.Builder> configure) {
        return with(configure);
    }

    /**
     * Executes the configured run.
     *
     * @return command result
     */
    public CommandResult execute() {
        return service.run(configure);
    }

    /**
     * Executes the run with additional argv arguments.
     *
     * @param arguments argument values
     * @return command result
     */
    public CommandResult execute(String... arguments) {
        return withArgs(arguments).execute();
    }

    private RunScenario with(Consumer<CommandInvocation.Builder> step) {
        Objects.requireNonNull(step, "step");
        return new RunScenario(service, configure.andThen(step));
    }
}

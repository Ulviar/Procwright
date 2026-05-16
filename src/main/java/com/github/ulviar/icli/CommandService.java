package com.github.ulviar.icli;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Entry point for scenario-first command workflows.
 */
public final class CommandService {

    private final CommandSpec commandSpec;
    private final RunOptions runOptions;

    /**
     * Creates a service from a base command specification and default run options.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     */
    public CommandService(CommandSpec commandSpec, RunOptions runOptions) {
        this.commandSpec = Objects.requireNonNull(commandSpec, "commandSpec");
        this.runOptions = Objects.requireNonNull(runOptions, "runOptions");
    }

    /**
     * Creates a service for an executable using default run options.
     *
     * @param executable executable name or path
     * @return command service
     */
    public static CommandService forCommand(String executable) {
        return new CommandService(CommandSpec.of(executable), RunOptions.defaults());
    }

    /**
     * Creates a service for an explicit shell command using default run options.
     *
     * @param commandLine command line interpreted by the system shell
     * @return command service
     */
    public static CommandService forShellCommand(String commandLine) {
        return new CommandService(CommandSpec.shell(commandLine), RunOptions.defaults());
    }

    /**
     * Returns the base command specification.
     *
     * @return base command specification
     */
    public CommandSpec commandSpec() {
        return commandSpec;
    }

    /**
     * Returns the default run options.
     *
     * @return default run options
     */
    public RunOptions runOptions() {
        return runOptions;
    }

    /**
     * Defines a one-shot run scenario.
     *
     * @param configure invocation callback
     * @return command result
     * @throws CommandExecutionException when the process cannot be started, supervised, or captured
     */
    public CommandResult run(Consumer<CommandInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        CommandInvocation.Builder builder = CommandInvocation.builder();
        configure.accept(builder);
        CommandInvocation invocation = builder.build();

        ExecutionPlan plan = ExecutionPlanResolver.resolve(ScenarioProfile.run(runOptions), commandSpec, invocation);
        return ProcessKernel.run(plan);
    }
}

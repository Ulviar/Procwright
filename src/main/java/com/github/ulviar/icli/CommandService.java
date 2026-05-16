package com.github.ulviar.icli;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Entry point for scenario-first command workflows.
 */
public final class CommandService {

    private final CommandSpec commandSpec;
    private final RunOptions runOptions;
    private final SessionOptions sessionOptions;
    private final LineSessionOptions lineSessionOptions;
    private final StreamOptions streamOptions;
    private final PooledLineSessionOptions pooledLineSessionOptions;
    private final DiagnosticsOptions diagnosticsOptions;

    /**
     * Creates a service from a base command specification and default run options.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     */
    public CommandService(CommandSpec commandSpec, RunOptions runOptions) {
        this(commandSpec, runOptions, SessionOptions.defaults());
    }

    /**
     * Creates a service from a base command specification, default run options, and default session options.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     * @param sessionOptions default interactive session options
     */
    public CommandService(CommandSpec commandSpec, RunOptions runOptions, SessionOptions sessionOptions) {
        this(commandSpec, runOptions, sessionOptions, LineSessionOptions.defaults());
    }

    /**
     * Creates a service from base command, run, session, and line-session defaults.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     * @param sessionOptions default interactive session options
     * @param lineSessionOptions default line-session options
     */
    public CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions) {
        this(commandSpec, runOptions, sessionOptions, lineSessionOptions, StreamOptions.defaults());
    }

    /**
     * Creates a service from base command and all scenario defaults.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     * @param sessionOptions default interactive session options
     * @param lineSessionOptions default line-session options
     * @param streamOptions default stream options
     */
    public CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions) {
        this(
                commandSpec,
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                PooledLineSessionOptions.defaults());
    }

    /**
     * Creates a service from base command and all scenario defaults.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     * @param sessionOptions default interactive session options
     * @param lineSessionOptions default line-session options
     * @param streamOptions default stream options
     * @param pooledLineSessionOptions default pooled line-session options
     */
    public CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions,
            PooledLineSessionOptions pooledLineSessionOptions) {
        this(
                commandSpec,
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                DiagnosticsOptions.defaults());
    }

    private CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions,
            PooledLineSessionOptions pooledLineSessionOptions,
            DiagnosticsOptions diagnosticsOptions) {
        this.commandSpec = Objects.requireNonNull(commandSpec, "commandSpec");
        this.runOptions = Objects.requireNonNull(runOptions, "runOptions");
        this.sessionOptions = Objects.requireNonNull(sessionOptions, "sessionOptions");
        this.lineSessionOptions = Objects.requireNonNull(lineSessionOptions, "lineSessionOptions");
        this.streamOptions = Objects.requireNonNull(streamOptions, "streamOptions");
        this.pooledLineSessionOptions = Objects.requireNonNull(pooledLineSessionOptions, "pooledLineSessionOptions");
        this.diagnosticsOptions = Objects.requireNonNull(diagnosticsOptions, "diagnosticsOptions");
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
     * Returns the default interactive session options.
     *
     * @return default session options
     */
    public SessionOptions sessionOptions() {
        return sessionOptions;
    }

    /**
     * Returns the default line-session options.
     *
     * @return default line-session options
     */
    public LineSessionOptions lineSessionOptions() {
        return lineSessionOptions;
    }

    /**
     * Returns the default stream options.
     *
     * @return default stream options
     */
    public StreamOptions streamOptions() {
        return streamOptions;
    }

    /**
     * Returns the default pooled line-session options.
     *
     * @return default pooled line-session options
     */
    public PooledLineSessionOptions pooledLineSessionOptions() {
        return pooledLineSessionOptions;
    }

    /**
     * Returns the default diagnostics options.
     *
     * @return diagnostics options
     */
    public DiagnosticsOptions diagnosticsOptions() {
        return diagnosticsOptions;
    }

    /**
     * Returns a copy with different diagnostics options.
     *
     * <p>Diagnostics are emitted by command-service scenarios that own process lifecycle: {@code run},
     * {@code interactive}, {@code lineSession}, {@code listen}, and worker launches inside {@code pooled}. {@code Expect}
     * is a helper over an already opened {@link Session} and does not emit separate process lifecycle events.
     *
     * @param diagnosticsOptions diagnostics options
     * @return updated command service
     */
    public CommandService withDiagnostics(DiagnosticsOptions diagnosticsOptions) {
        return new CommandService(
                commandSpec,
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                diagnosticsOptions);
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

        ExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.run(runOptions), commandSpec, invocation, diagnosticsOptions);
        return ProcessKernel.run(plan);
    }

    /**
     * Opens a raw interactive session.
     *
     * @param configure invocation callback
     * @return interactive session
     * @throws CommandExecutionException when the process cannot be started
     */
    public Session interactive(Consumer<SessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        SessionInvocation.Builder builder = SessionInvocation.builder();
        configure.accept(builder);
        SessionInvocation invocation = builder.build();

        SessionExecutionPlan plan =
                ExecutionPlanResolver.resolve(ScenarioProfile.interactive(sessionOptions), commandSpec, invocation);
        return openSession("interactive", plan);
    }

    /**
     * Opens a line-oriented request/response session.
     *
     * @param configure invocation callback
     * @return line-oriented session
     * @throws CommandExecutionException when the process cannot be started
     */
    public LineSession lineSession(Consumer<LineSessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();
        configure.accept(builder);
        LineSessionInvocation invocation = builder.build();

        return openLineSession("lineSession", invocation);
    }

    /**
     * Opens a pooled line-oriented request/response session.
     *
     * @param configure invocation callback
     * @return pooled line-oriented session
     * @throws CommandExecutionException when a worker process cannot be started
     */
    public PooledLineSession pooled(Consumer<PooledLineSessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        PooledLineSessionInvocation.Builder builder = PooledLineSessionInvocation.builder(pooledLineSessionOptions);
        configure.accept(builder);
        PooledLineSessionInvocation invocation = builder.build();

        return new PooledLineSession(
                () -> openLineSession("pooled", invocation.lineSessionInvocation()), invocation.options());
    }

    /**
     * Opens a listen-only streaming session.
     *
     * @param configure invocation callback
     * @return streaming session
     * @throws CommandExecutionException when the process cannot be started
     */
    public StreamSession listen(Consumer<StreamInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        StreamInvocation.Builder builder = StreamInvocation.builder();
        configure.accept(builder);
        StreamInvocation invocation = builder.build();

        StreamExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.stream(streamOptions), commandSpec, invocation, diagnosticsOptions);
        return StreamRuntime.open(plan);
    }

    private Session openSession(String scenario, SessionExecutionPlan plan) {
        Diagnostics diagnostics = Diagnostics.of(diagnosticsOptions, scenario, CommandEcho.from(plan.launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        try {
            return SessionRuntime.open(plan, diagnostics);
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    Diagnostics.attributes("error", exception.getClass().getName()));
            throw exception;
        }
    }

    private LineSession openLineSession(String scenario, LineSessionInvocation invocation) {
        SessionExecutionPlan plan =
                ExecutionPlanResolver.resolve(ScenarioProfile.interactive(sessionOptions), commandSpec, invocation);
        Session session = openSession(scenario, plan);
        try {
            return new LineSession(session, lineSessionOptions);
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
    }
}

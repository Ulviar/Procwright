package io.github.ulviar.icli;

import io.github.ulviar.icli.command.CommandExecutionException;
import io.github.ulviar.icli.command.CommandInvocation;
import io.github.ulviar.icli.command.CommandResult;
import io.github.ulviar.icli.command.CommandSpec;
import io.github.ulviar.icli.command.RunOptions;
import io.github.ulviar.icli.diagnostics.DiagnosticEventType;
import io.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import io.github.ulviar.icli.internal.CommandEchoSupport;
import io.github.ulviar.icli.internal.DiagnosticEmitter;
import io.github.ulviar.icli.internal.ExecutionPlan;
import io.github.ulviar.icli.internal.ExecutionPlanResolver;
import io.github.ulviar.icli.internal.ProcessKernel;
import io.github.ulviar.icli.internal.ScenarioProfile;
import io.github.ulviar.icli.internal.SessionExecutionPlan;
import io.github.ulviar.icli.internal.StreamExecutionPlan;
import io.github.ulviar.icli.internal.session.PooledLineSessionInvocationDefaults;
import io.github.ulviar.icli.internal.session.ReadinessSupport;
import io.github.ulviar.icli.internal.session.SessionRuntime;
import io.github.ulviar.icli.internal.session.SessionScenarioSupport;
import io.github.ulviar.icli.internal.session.StreamRuntime;
import io.github.ulviar.icli.session.LineSession;
import io.github.ulviar.icli.session.LineSessionInvocation;
import io.github.ulviar.icli.session.LineSessionOptions;
import io.github.ulviar.icli.session.PooledLineSession;
import io.github.ulviar.icli.session.PooledLineSessionInvocation;
import io.github.ulviar.icli.session.PooledLineSessionOptions;
import io.github.ulviar.icli.session.PooledProtocolSession;
import io.github.ulviar.icli.session.PooledProtocolSessionInvocation;
import io.github.ulviar.icli.session.PooledProtocolSessionOptions;
import io.github.ulviar.icli.session.ProtocolAdapter;
import io.github.ulviar.icli.session.ProtocolSession;
import io.github.ulviar.icli.session.ProtocolSessionInvocation;
import io.github.ulviar.icli.session.ProtocolSessionOptions;
import io.github.ulviar.icli.session.Session;
import io.github.ulviar.icli.session.SessionInvocation;
import io.github.ulviar.icli.session.SessionOptions;
import io.github.ulviar.icli.session.StreamInvocation;
import io.github.ulviar.icli.session.StreamOptions;
import io.github.ulviar.icli.session.StreamSession;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Entry point for scenario-first command workflows.
 */
public final class CommandService {

    private final CommandSpec commandSpec;
    private final CommandServiceDefaults defaults;
    private final ProcessKernel processKernel;

    /**
     * Creates a service from a base command specification and default run options.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     */
    CommandService(CommandSpec commandSpec, RunOptions runOptions) {
        this(commandSpec, CommandServiceDefaults.of(runOptions));
    }

    /**
     * Creates a service from a base command specification, default run options, and default session options.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     * @param sessionOptions default interactive session options
     */
    CommandService(CommandSpec commandSpec, RunOptions runOptions, SessionOptions sessionOptions) {
        this(commandSpec, CommandServiceDefaults.of(runOptions).withSessionOptions(sessionOptions));
    }

    /**
     * Creates a service from base command, run, session, and line-session defaults.
     *
     * @param commandSpec base command specification
     * @param runOptions default run options
     * @param sessionOptions default interactive session options
     * @param lineSessionOptions default line-session options
     */
    CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions) {
        this(
                commandSpec,
                CommandServiceDefaults.of(runOptions)
                        .withSessionOptions(sessionOptions)
                        .withLineSessionOptions(lineSessionOptions));
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
    CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions) {
        this(
                commandSpec,
                CommandServiceDefaults.of(runOptions)
                        .withSessionOptions(sessionOptions)
                        .withLineSessionOptions(lineSessionOptions)
                        .withStreamOptions(streamOptions));
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
    CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions,
            PooledLineSessionOptions pooledLineSessionOptions) {
        this(
                commandSpec,
                CommandServiceDefaults.of(runOptions)
                        .withSessionOptions(sessionOptions)
                        .withLineSessionOptions(lineSessionOptions)
                        .withStreamOptions(streamOptions)
                        .withPooledLineSessionOptions(pooledLineSessionOptions));
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
     * @param protocolSessionOptions default protocol-session options
     */
    CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions,
            PooledLineSessionOptions pooledLineSessionOptions,
            ProtocolSessionOptions protocolSessionOptions) {
        this(
                commandSpec,
                CommandServiceDefaults.of(runOptions)
                        .withSessionOptions(sessionOptions)
                        .withLineSessionOptions(lineSessionOptions)
                        .withStreamOptions(streamOptions)
                        .withPooledLineSessionOptions(pooledLineSessionOptions)
                        .withProtocolSessionOptions(protocolSessionOptions));
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
     * @param protocolSessionOptions default protocol-session options
     * @param pooledProtocolSessionOptions default pooled protocol-session options
     */
    CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions,
            PooledLineSessionOptions pooledLineSessionOptions,
            ProtocolSessionOptions protocolSessionOptions,
            PooledProtocolSessionOptions pooledProtocolSessionOptions) {
        this(
                commandSpec,
                new CommandServiceDefaults(
                        runOptions,
                        sessionOptions,
                        lineSessionOptions,
                        streamOptions,
                        pooledLineSessionOptions,
                        protocolSessionOptions,
                        pooledProtocolSessionOptions,
                        DiagnosticsOptions.defaults()));
    }

    private CommandService(CommandSpec commandSpec, CommandServiceDefaults defaults) {
        this(commandSpec, defaults, ProcessKernel.standard());
    }

    CommandService(
            CommandSpec commandSpec,
            RunOptions runOptions,
            SessionOptions sessionOptions,
            LineSessionOptions lineSessionOptions,
            StreamOptions streamOptions,
            PooledLineSessionOptions pooledLineSessionOptions,
            ProtocolSessionOptions protocolSessionOptions,
            PooledProtocolSessionOptions pooledProtocolSessionOptions,
            DiagnosticsOptions diagnosticsOptions,
            ProcessKernel processKernel) {
        this(
                commandSpec,
                new CommandServiceDefaults(
                        runOptions,
                        sessionOptions,
                        lineSessionOptions,
                        streamOptions,
                        pooledLineSessionOptions,
                        protocolSessionOptions,
                        pooledProtocolSessionOptions,
                        diagnosticsOptions),
                processKernel);
    }

    private CommandService(CommandSpec commandSpec, CommandServiceDefaults defaults, ProcessKernel processKernel) {
        this.commandSpec = Objects.requireNonNull(commandSpec, "commandSpec");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.processKernel = Objects.requireNonNull(processKernel, "processKernel");
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
     * <p>The command line is interpreted by the operating-system shell. Do not build this value by concatenating
     * untrusted input; prefer {@link #forCommand(String)} plus argv arguments unless shell syntax is required.
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
        return defaults.runOptions();
    }

    /**
     * Returns the default interactive session options.
     *
     * @return default session options
     */
    public SessionOptions sessionOptions() {
        return defaults.sessionOptions();
    }

    /**
     * Returns the default line-session options.
     *
     * @return default line-session options
     */
    public LineSessionOptions lineSessionOptions() {
        return defaults.lineSessionOptions();
    }

    /**
     * Returns the default stream options.
     *
     * @return default stream options
     */
    public StreamOptions streamOptions() {
        return defaults.streamOptions();
    }

    /**
     * Returns the default pooled line-session options.
     *
     * @return default pooled line-session options
     */
    public PooledLineSessionOptions pooledLineSessionOptions() {
        return defaults.pooledLineSessionOptions();
    }

    /**
     * Returns the default protocol-session options.
     *
     * @return default protocol-session options
     */
    public ProtocolSessionOptions protocolSessionOptions() {
        return defaults.protocolSessionOptions();
    }

    /**
     * Returns the default pooled protocol-session options.
     *
     * @return default pooled protocol-session options
     */
    public PooledProtocolSessionOptions pooledProtocolSessionOptions() {
        return defaults.pooledProtocolSessionOptions();
    }

    /**
     * Returns the default diagnostics options.
     *
     * @return diagnostics options
     */
    public DiagnosticsOptions diagnosticsOptions() {
        return defaults.diagnosticsOptions();
    }

    /**
     * Returns a copy with different diagnostics options.
     *
     * <p>Diagnostics are emitted by command-service scenarios that own process lifecycle: {@code run},
     * {@code interactive}, {@code lineSession}, {@code protocolSession}, {@code listen}, worker launches inside
     * {@code lineSession().pooled()}, and worker launches inside {@code protocolSession(factory).pooled()}.
     * {@code Expect} is a helper over an already opened {@link Session} and does not emit separate process lifecycle
     * events.
     *
     * @param diagnosticsOptions diagnostics options
     * @return updated command service
     */
    public CommandService withDiagnostics(DiagnosticsOptions diagnosticsOptions) {
        return new CommandService(commandSpec, defaults.withDiagnosticsOptions(diagnosticsOptions), processKernel);
    }

    /**
     * Returns a copy with different default run options.
     *
     * @param runOptions default run options
     * @return updated command service
     */
    public CommandService withRunOptions(RunOptions runOptions) {
        return new CommandService(commandSpec, defaults.withRunOptions(runOptions), processKernel);
    }

    /**
     * Returns a copy with different default interactive session options.
     *
     * @param sessionOptions default interactive session options
     * @return updated command service
     */
    public CommandService withSessionOptions(SessionOptions sessionOptions) {
        return new CommandService(commandSpec, defaults.withSessionOptions(sessionOptions), processKernel);
    }

    /**
     * Returns a copy with different default line-session options.
     *
     * @param lineSessionOptions default line-session options
     * @return updated command service
     */
    public CommandService withLineSessionOptions(LineSessionOptions lineSessionOptions) {
        return new CommandService(commandSpec, defaults.withLineSessionOptions(lineSessionOptions), processKernel);
    }

    /**
     * Returns a copy with different default streaming options.
     *
     * @param streamOptions default stream options
     * @return updated command service
     */
    public CommandService withStreamOptions(StreamOptions streamOptions) {
        return new CommandService(commandSpec, defaults.withStreamOptions(streamOptions), processKernel);
    }

    /**
     * Returns a copy with different default pooled line-session options.
     *
     * @param pooledLineSessionOptions default pooled line-session options
     * @return updated command service
     */
    public CommandService withPooledLineSessionOptions(PooledLineSessionOptions pooledLineSessionOptions) {
        return new CommandService(
                commandSpec, defaults.withPooledLineSessionOptions(pooledLineSessionOptions), processKernel);
    }

    /**
     * Returns a copy with different default protocol-session options.
     *
     * @param protocolSessionOptions default protocol-session options
     * @return updated command service
     */
    public CommandService withProtocolSessionOptions(ProtocolSessionOptions protocolSessionOptions) {
        return new CommandService(
                commandSpec, defaults.withProtocolSessionOptions(protocolSessionOptions), processKernel);
    }

    /**
     * Returns a copy with different default pooled protocol-session options.
     *
     * @param pooledProtocolSessionOptions default pooled protocol-session options
     * @return updated command service
     */
    public CommandService withPooledProtocolSessionOptions(PooledProtocolSessionOptions pooledProtocolSessionOptions) {
        return new CommandService(
                commandSpec, defaults.withPooledProtocolSessionOptions(pooledProtocolSessionOptions), processKernel);
    }

    /**
     * Selects the finite process run scenario.
     *
     * @return run scenario
     */
    public RunScenario run() {
        return new RunScenario(this);
    }

    /**
     * Selects the raw interactive session scenario.
     *
     * @return interactive scenario
     */
    public InteractiveScenario interactive() {
        return new InteractiveScenario(this);
    }

    /**
     * Selects the line-oriented request/response session scenario.
     *
     * @return line-session scenario
     */
    public LineSessionScenario lineSession() {
        return new LineSessionScenario(this, defaults.lineSessionOptions());
    }

    /**
     * Selects a generic request/response protocol session from a single adapter instance.
     *
     * <p>Use {@link #protocolSession(Supplier)} when the same scenario may branch into pooled mode.
     *
     * @param adapter protocol adapter
     * @param <I> request type
     * @param <O> response type
     * @return protocol-session scenario
     */
    public <I, O> ProtocolSessionScenario<I, O> protocolSession(ProtocolAdapter<I, O> adapter) {
        return new ProtocolSessionScenario<>(this, adapter, defaults.protocolSessionOptions());
    }

    /**
     * Selects a generic request/response protocol session from an adapter factory.
     *
     * <p>The factory form can open one worker or branch into pooled mode. Each pooled worker receives a fresh adapter
     * instance.
     *
     * @param adapterFactory protocol adapter factory
     * @param <I> request type
     * @param <O> response type
     * @return reusable protocol-session scenario
     */
    public <I, O> ReusableProtocolSessionScenario<I, O> protocolSession(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        return new ReusableProtocolSessionScenario<>(this, adapterFactory, defaults.protocolSessionOptions());
    }

    /**
     * Selects the listen-only streaming scenario.
     *
     * @return streaming scenario
     */
    public StreamScenario listen() {
        return new StreamScenario(this);
    }

    /**
     * Defines a one-shot run scenario.
     *
     * @param configure invocation callback
     * @return command result
     * @throws CommandExecutionException when the process cannot be started, supervised, or captured
     */
    CommandResult run(Consumer<CommandInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        CommandInvocation.Builder builder = CommandInvocation.builder();
        configure.accept(builder);
        CommandInvocation invocation = builder.build();

        ExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.run(defaults.runOptions()), commandSpec, invocation, defaults.diagnosticsOptions());
        return processKernel.run(plan);
    }

    /**
     * Opens a raw interactive session.
     *
     * @param configure invocation callback
     * @return interactive session
     * @throws CommandExecutionException when the process cannot be started or the configured readiness probe fails
     */
    Session interactive(Consumer<SessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        SessionInvocation.Builder builder = SessionInvocation.builder();
        configure.accept(builder);
        SessionInvocation invocation = builder.build();

        SessionExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.interactive(defaults.sessionOptions()), commandSpec, invocation);
        Session session = openSession("interactive", plan);
        invocation
                .readinessProbe()
                .ifPresent(probe -> ReadinessSupport.check(
                        session,
                        probe,
                        invocation.readinessTimeout().orElse(ReadinessSupport.DEFAULT_TIMEOUT),
                        session::close));
        return session;
    }

    /**
     * Opens a line-oriented request/response session.
     *
     * @param configure invocation callback
     * @return line-oriented session
     * @throws CommandExecutionException when the process cannot be started or the configured readiness probe fails
     */
    LineSession lineSession(Consumer<LineSessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();
        configure.accept(builder);
        LineSessionInvocation invocation = builder.build();

        return openLineSession(invocation, defaults.lineSessionOptions());
    }

    /**
     * Opens a pooled line-oriented request/response session.
     *
     * @param configure invocation callback
     * @return pooled line-oriented session
     * @throws CommandExecutionException when a worker process cannot be started or cannot become ready
     */
    PooledLineSession pooled(Consumer<PooledLineSessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        PooledLineSessionInvocation.Builder builder = pooledInvocationBuilder();
        configure.accept(builder);
        PooledLineSessionInvocation invocation = builder.build();

        return openPooledLineSession(
                invocation.lineSessionInvocation(), defaults.lineSessionOptions(), invocation.options());
    }

    /**
     * Opens a generic request/response protocol session.
     *
     * @param adapter protocol adapter
     * @param configure invocation callback
     * @param <I> request type
     * @param <O> response type
     * @return protocol session
     * @throws CommandExecutionException when the process cannot be started or the configured readiness probe fails
     */
    <I, O> ProtocolSession<I, O> protocolSession(
            ProtocolAdapter<I, O> adapter, Consumer<ProtocolSessionInvocation.Builder<I, O>> configure) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(configure, "configure");

        ProtocolSessionInvocation.Builder<I, O> builder =
                ProtocolSessionInvocation.builder(defaults.protocolSessionOptions());
        configure.accept(builder);
        ProtocolSessionInvocation<I, O> invocation = builder.build();

        return openProtocolSession(adapter, invocation);
    }

    /**
     * Opens a pooled typed protocol session.
     *
     * <p>Each worker receives a fresh adapter instance from {@code adapterFactory}. This keeps protocol state scoped to
     * one worker and avoids requiring adapters to be thread-safe. iCLI serializes factory calls, but the returned
     * adapters are used independently by their worker sessions.
     *
     * @param adapterFactory per-worker protocol adapter factory
     * @param configure invocation callback
     * @param <I> request type
     * @param <O> response type
     * @return pooled protocol session
     * @throws CommandExecutionException when a worker process cannot be started or cannot become ready
     */
    <I, O> PooledProtocolSession<I, O> pooledProtocol(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            Consumer<PooledProtocolSessionInvocation.Builder<I, O>> configure) {
        Objects.requireNonNull(adapterFactory, "adapterFactory");
        Objects.requireNonNull(configure, "configure");

        PooledProtocolSessionInvocation.Builder<I, O> builder = PooledProtocolSessionInvocation.builder(
                defaults.protocolSessionOptions(), defaults.pooledProtocolSessionOptions());
        configure.accept(builder);
        PooledProtocolSessionInvocation<I, O> invocation = builder.build();
        return openPooledProtocolSession(adapterFactory, invocation);
    }

    /**
     * Opens a listen-only streaming session.
     *
     * @param configure invocation callback
     * @return streaming session
     * @throws CommandExecutionException when the process cannot be started
     */
    StreamSession listen(Consumer<StreamInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        StreamInvocation.Builder builder = StreamInvocation.builder();
        configure.accept(builder);
        StreamInvocation invocation = builder.build();

        StreamExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.stream(defaults.streamOptions()),
                commandSpec,
                invocation,
                defaults.diagnosticsOptions());
        return StreamRuntime.open(plan);
    }

    private Session openSession(String scenario, SessionExecutionPlan plan) {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                defaults.diagnosticsOptions(), scenario, () -> CommandEchoSupport.from(plan.launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        try {
            return SessionRuntime.open(plan, diagnostics);
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    DiagnosticEmitter.attributes("error", exception.getClass().getName()));
            throw exception;
        }
    }

    LineSession openLineSession(LineSessionInvocation invocation, LineSessionOptions options) {
        return openLineSession("lineSession", invocation, options);
    }

    PooledLineSession openPooledLineSession(
            LineSessionInvocation invocation, LineSessionOptions lineOptions, PooledLineSessionOptions poolOptions) {
        return SessionScenarioSupport.openPooledLineSession(
                () -> openLineSession("pooled", invocation, lineOptions), poolOptions);
    }

    <I, O> ProtocolSession<I, O> openProtocolSession(
            ProtocolAdapter<I, O> adapter, ProtocolSessionInvocation<I, O> invocation) {
        return openProtocolSession("protocolSession", adapter, invocation);
    }

    <I, O> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            PooledProtocolSessionInvocation<I, O> invocation) {
        Objects.requireNonNull(adapterFactory, "adapterFactory");
        Objects.requireNonNull(invocation, "invocation");
        Object adapterFactoryLock = new Object();

        return SessionScenarioSupport.openPooledProtocolSession(
                () -> openProtocolSession(
                        "pooledProtocol",
                        createProtocolAdapter(adapterFactory, adapterFactoryLock),
                        invocation.protocolSessionInvocation()),
                invocation);
    }

    <I, O> ProtocolAdapter<I, O> createProtocolAdapter(Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        return createProtocolAdapter(adapterFactory, new Object());
    }

    private LineSession openLineSession(String scenario, LineSessionInvocation invocation, LineSessionOptions options) {
        SessionExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.interactive(defaults.sessionOptions()), commandSpec, invocation);
        Session session = openSession(scenario, plan);
        try {
            LineSession lineSession = SessionScenarioSupport.openLineSession(session, options);
            invocation
                    .readinessProbe()
                    .ifPresent(probe -> ReadinessSupport.check(
                            lineSession,
                            probe,
                            invocation.readinessTimeout().orElse(ReadinessSupport.DEFAULT_TIMEOUT),
                            lineSession::close));
            return lineSession;
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
    }

    private <I, O> ProtocolSession<I, O> openProtocolSession(
            String scenario, ProtocolAdapter<I, O> adapter, ProtocolSessionInvocation<I, O> invocation) {
        SessionExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.interactive(defaults.sessionOptions()), commandSpec, invocation);
        Session session = openSession(scenario, plan);
        try {
            ProtocolSession<I, O> protocolSession =
                    SessionScenarioSupport.openProtocolSession(session, adapter, invocation.options());
            invocation
                    .readinessProbe()
                    .ifPresent(probe -> ReadinessSupport.check(
                            protocolSession,
                            probe,
                            invocation.readinessTimeout().orElse(ReadinessSupport.DEFAULT_TIMEOUT),
                            protocolSession::close));
            return protocolSession;
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
    }

    private static <I, O> ProtocolAdapter<I, O> createProtocolAdapter(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory, Object lock) {
        synchronized (lock) {
            return Objects.requireNonNull(adapterFactory.get(), "adapterFactory returned null");
        }
    }

    private PooledLineSessionInvocation.Builder pooledInvocationBuilder() {
        return PooledLineSessionInvocationDefaults.builder(defaults.pooledLineSessionOptions());
    }
}

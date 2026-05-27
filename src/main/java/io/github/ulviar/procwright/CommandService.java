package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInvocation;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.diagnostics.DiagnosticsOptions;
import io.github.ulviar.procwright.internal.ProcessKernel;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionInvocation;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionInvocation;
import io.github.ulviar.procwright.session.PooledLineSessionOptions;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionInvocation;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionInvocation;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionInvocation;
import io.github.ulviar.procwright.session.SessionOptions;
import io.github.ulviar.procwright.session.StreamInvocation;
import io.github.ulviar.procwright.session.StreamOptions;
import io.github.ulviar.procwright.session.StreamSession;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Entry point for scenario-first command workflows.
 */
public final class CommandService {

    private final ScenarioRuntime runtime;

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
        this.runtime = new ScenarioRuntime(commandSpec, defaults, processKernel);
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
        return runtime.commandSpec();
    }

    /**
     * Returns the default run options.
     *
     * @return default run options
     */
    public RunOptions runOptions() {
        return runtime.runOptions();
    }

    /**
     * Returns the default interactive session options.
     *
     * @return default session options
     */
    public SessionOptions sessionOptions() {
        return runtime.sessionOptions();
    }

    /**
     * Returns the default line-session options.
     *
     * @return default line-session options
     */
    public LineSessionOptions lineSessionOptions() {
        return runtime.lineSessionOptions();
    }

    /**
     * Returns the default stream options.
     *
     * @return default stream options
     */
    public StreamOptions streamOptions() {
        return runtime.streamOptions();
    }

    /**
     * Returns the default pooled line-session options.
     *
     * @return default pooled line-session options
     */
    public PooledLineSessionOptions pooledLineSessionOptions() {
        return runtime.pooledLineSessionOptions();
    }

    /**
     * Returns the default protocol-session options.
     *
     * @return default protocol-session options
     */
    public ProtocolSessionOptions protocolSessionOptions() {
        return runtime.protocolSessionOptions();
    }

    /**
     * Returns the default pooled protocol-session options.
     *
     * @return default pooled protocol-session options
     */
    public PooledProtocolSessionOptions pooledProtocolSessionOptions() {
        return runtime.pooledProtocolSessionOptions();
    }

    /**
     * Returns the default diagnostics options.
     *
     * @return diagnostics options
     */
    public DiagnosticsOptions diagnosticsOptions() {
        return runtime.diagnosticsOptions();
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
        return new CommandService(
                runtime.commandSpec(),
                runtime.defaults().withDiagnosticsOptions(diagnosticsOptions),
                runtime.processKernel());
    }

    /**
     * Returns a copy with different default run options.
     *
     * @param runOptions default run options
     * @return updated command service
     */
    public CommandService withRunOptions(RunOptions runOptions) {
        return new CommandService(
                runtime.commandSpec(), runtime.defaults().withRunOptions(runOptions), runtime.processKernel());
    }

    /**
     * Returns a copy with different default interactive session options.
     *
     * @param sessionOptions default interactive session options
     * @return updated command service
     */
    public CommandService withSessionOptions(SessionOptions sessionOptions) {
        return new CommandService(
                runtime.commandSpec(), runtime.defaults().withSessionOptions(sessionOptions), runtime.processKernel());
    }

    /**
     * Returns a copy with different default line-session options.
     *
     * @param lineSessionOptions default line-session options
     * @return updated command service
     */
    public CommandService withLineSessionOptions(LineSessionOptions lineSessionOptions) {
        return new CommandService(
                runtime.commandSpec(),
                runtime.defaults().withLineSessionOptions(lineSessionOptions),
                runtime.processKernel());
    }

    /**
     * Returns a copy with different default streaming options.
     *
     * @param streamOptions default stream options
     * @return updated command service
     */
    public CommandService withStreamOptions(StreamOptions streamOptions) {
        return new CommandService(
                runtime.commandSpec(), runtime.defaults().withStreamOptions(streamOptions), runtime.processKernel());
    }

    /**
     * Returns a copy with different default pooled line-session options.
     *
     * @param pooledLineSessionOptions default pooled line-session options
     * @return updated command service
     */
    public CommandService withPooledLineSessionOptions(PooledLineSessionOptions pooledLineSessionOptions) {
        return new CommandService(
                runtime.commandSpec(),
                runtime.defaults().withPooledLineSessionOptions(pooledLineSessionOptions),
                runtime.processKernel());
    }

    /**
     * Returns a copy with different default protocol-session options.
     *
     * @param protocolSessionOptions default protocol-session options
     * @return updated command service
     */
    public CommandService withProtocolSessionOptions(ProtocolSessionOptions protocolSessionOptions) {
        return new CommandService(
                runtime.commandSpec(),
                runtime.defaults().withProtocolSessionOptions(protocolSessionOptions),
                runtime.processKernel());
    }

    /**
     * Returns a copy with different default pooled protocol-session options.
     *
     * @param pooledProtocolSessionOptions default pooled protocol-session options
     * @return updated command service
     */
    public CommandService withPooledProtocolSessionOptions(PooledProtocolSessionOptions pooledProtocolSessionOptions) {
        return new CommandService(
                runtime.commandSpec(),
                runtime.defaults().withPooledProtocolSessionOptions(pooledProtocolSessionOptions),
                runtime.processKernel());
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
        return new LineSessionScenario(this, runtime.lineSessionOptions());
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
        return new ProtocolSessionScenario<>(this, adapter, runtime.protocolSessionOptions());
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
        return new ReusableProtocolSessionScenario<>(this, adapterFactory, runtime.protocolSessionOptions());
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
        return runtime.run(configure);
    }

    /**
     * Opens a raw interactive session.
     *
     * @param configure invocation callback
     * @return interactive session
     * @throws CommandExecutionException when the process cannot be started or the configured readiness probe fails
     */
    Session interactive(Consumer<SessionInvocation.Builder> configure) {
        return runtime.interactive(configure);
    }

    /**
     * Opens a line-oriented request/response session.
     *
     * @param configure invocation callback
     * @return line-oriented session
     * @throws CommandExecutionException when the process cannot be started or the configured readiness probe fails
     */
    LineSession lineSession(Consumer<LineSessionInvocation.Builder> configure) {
        return runtime.lineSession(configure);
    }

    /**
     * Opens a pooled line-oriented request/response session.
     *
     * @param configure invocation callback
     * @return pooled line-oriented session
     * @throws CommandExecutionException when a worker process cannot be started or cannot become ready
     */
    PooledLineSession pooled(Consumer<PooledLineSessionInvocation.Builder> configure) {
        return runtime.pooled(configure);
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
        return runtime.protocolSession(adapter, configure);
    }

    /**
     * Opens a pooled typed protocol session.
     *
     * <p>Each worker receives a fresh adapter instance from {@code adapterFactory}. This keeps protocol state scoped to
     * one worker and avoids requiring adapters to be thread-safe. Procwright serializes factory calls, but the returned
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
        return runtime.pooledProtocol(adapterFactory, configure);
    }

    /**
     * Opens a listen-only streaming session.
     *
     * @param configure invocation callback
     * @return streaming session
     * @throws CommandExecutionException when the process cannot be started
     */
    StreamSession listen(Consumer<StreamInvocation.Builder> configure) {
        return runtime.listen(configure);
    }

    LineSession openLineSession(LineSessionInvocation invocation, LineSessionOptions options) {
        return runtime.openLineSession(invocation, options);
    }

    PooledLineSession openPooledLineSession(
            LineSessionInvocation invocation, LineSessionOptions lineOptions, PooledLineSessionOptions poolOptions) {
        return runtime.openPooledLineSession(invocation, lineOptions, poolOptions);
    }

    <I, O> ProtocolSession<I, O> openProtocolSession(
            ProtocolAdapter<I, O> adapter, ProtocolSessionInvocation<I, O> invocation) {
        return runtime.openProtocolSession(adapter, invocation);
    }

    <I, O> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            PooledProtocolSessionInvocation<I, O> invocation) {
        return runtime.openPooledProtocolSession(adapterFactory, invocation);
    }

    <I, O> ProtocolAdapter<I, O> createProtocolAdapter(Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        return runtime.createProtocolAdapter(adapterFactory);
    }
}

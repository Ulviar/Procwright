package io.github.ulviar.icli;

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
 * Scenario orchestration boundary behind the public {@link CommandService} facade.
 *
 * <p>The public service selects scenarios and exposes defaults. This runtime owns the common path from scenario draft
 * builders to resolved plans, session wrappers, readiness checks, diagnostics, and worker-pool opening.
 */
final class ScenarioRuntime {

    private final CommandSpec commandSpec;
    private final CommandServiceDefaults defaults;
    private final ProcessKernel processKernel;

    ScenarioRuntime(CommandSpec commandSpec, CommandServiceDefaults defaults, ProcessKernel processKernel) {
        this.commandSpec = Objects.requireNonNull(commandSpec, "commandSpec");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.processKernel = Objects.requireNonNull(processKernel, "processKernel");
    }

    CommandSpec commandSpec() {
        return commandSpec;
    }

    CommandServiceDefaults defaults() {
        return defaults;
    }

    ProcessKernel processKernel() {
        return processKernel;
    }

    RunOptions runOptions() {
        return defaults.runOptions();
    }

    SessionOptions sessionOptions() {
        return defaults.sessionOptions();
    }

    LineSessionOptions lineSessionOptions() {
        return defaults.lineSessionOptions();
    }

    StreamOptions streamOptions() {
        return defaults.streamOptions();
    }

    PooledLineSessionOptions pooledLineSessionOptions() {
        return defaults.pooledLineSessionOptions();
    }

    ProtocolSessionOptions protocolSessionOptions() {
        return defaults.protocolSessionOptions();
    }

    PooledProtocolSessionOptions pooledProtocolSessionOptions() {
        return defaults.pooledProtocolSessionOptions();
    }

    DiagnosticsOptions diagnosticsOptions() {
        return defaults.diagnosticsOptions();
    }

    CommandResult run(Consumer<CommandInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        CommandInvocation.Builder builder = CommandInvocation.builder();
        configure.accept(builder);
        CommandInvocation invocation = builder.build();

        ExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.run(defaults.runOptions()), commandSpec, invocation, defaults.diagnosticsOptions());
        return processKernel.run(plan);
    }

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

    LineSession lineSession(Consumer<LineSessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();
        configure.accept(builder);
        LineSessionInvocation invocation = builder.build();

        return openLineSession(invocation, defaults.lineSessionOptions());
    }

    PooledLineSession pooled(Consumer<PooledLineSessionInvocation.Builder> configure) {
        Objects.requireNonNull(configure, "configure");

        PooledLineSessionInvocation.Builder builder = pooledInvocationBuilder();
        configure.accept(builder);
        PooledLineSessionInvocation invocation = builder.build();

        return openPooledLineSession(
                invocation.lineSessionInvocation(), defaults.lineSessionOptions(), invocation.options());
    }

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

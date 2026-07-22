/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProcessKernel;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.ReadinessSettings;
import io.github.ulviar.procwright.internal.RunSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.SessionScenarioSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.internal.StreamSettings;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.internal.session.ReadinessSupport;
import io.github.ulviar.procwright.internal.session.SessionRuntime;
import io.github.ulviar.procwright.internal.session.SessionScenarioSupport;
import io.github.ulviar.procwright.internal.session.StreamRuntime;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.StreamSession;
import java.util.Objects;
import java.util.function.Supplier;

/** Package-private terminal-operation boundary for immutable scenario drafts. */
final class ScenarioRuntime {

    private final LaunchSettings launchSettings;
    private final ProcessKernel processKernel;

    ScenarioRuntime(CommandSpec commandSpec, ProcessKernel processKernel) {
        launchSettings = LaunchSettings.from(Objects.requireNonNull(commandSpec, "commandSpec"));
        this.processKernel = Objects.requireNonNull(processKernel, "processKernel");
    }

    LaunchSettings launchSettings() {
        return launchSettings;
    }

    CommandResult run(RunSettings settings) {
        return processKernel.run(Objects.requireNonNull(settings, "settings").plan());
    }

    Session interactive(SessionSettings settings, ReadinessSettings<Session> readiness) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(readiness, "readiness");
        OpenedSession opened = openSession("interactive", settings.plan(), settings.diagnostics());
        try {
            readiness
                    .probe()
                    .ifPresent(probe -> ReadinessSupport.check(
                            opened.session(), probe, readiness.timeout(), opened.session()::close));
            return opened.session();
        } catch (RuntimeException | Error failure) {
            failOpen(opened, failure);
            throw failure;
        }
    }

    StreamSession listen(StreamSettings settings) {
        return StreamRuntime.open(Objects.requireNonNull(settings, "settings").plan());
    }

    LineSession openLineSession(SessionScenarioSettings<LineSession, LineSessionSettings> settings) {
        return openLineSession("lineSession", settings);
    }

    PooledLineSession openPooledLineSession(
            SessionScenarioSettings<LineSession, LineSessionSettings> worker, WorkerPoolSettings<LineSession> pool) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(pool, "pool");
        return SessionScenarioSupport.openPooledLineSession(
                () -> openLineSession("pooled", worker), worker.protocol(), pool);
    }

    <I extends Object, O extends Object> ProtocolSession<I, O> openProtocolSession(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> settings) {
        return openProtocolSession("protocolSession", createProtocolAdapter(adapterFactory), settings);
    }

    <I extends Object, O extends Object> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> worker,
            WorkerPoolSettings<ProtocolSession<I, O>> pool) {
        Objects.requireNonNull(adapterFactory, "adapterFactory");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(pool, "pool");
        return SessionScenarioSupport.openPooledProtocolSession(
                () -> openProtocolSession("pooledProtocol", createProtocolAdapter(adapterFactory), worker), pool);
    }

    static <I extends Object, O extends Object> ProtocolAdapter<I, O> createProtocolAdapter(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        Objects.requireNonNull(adapterFactory, "adapterFactory");
        return Objects.requireNonNull(adapterFactory.get(), "adapterFactory returned null");
    }

    private OpenedSession openSession(
            String scenario, SessionExecutionPlan plan, DiagnosticsSettings diagnosticsSettings) {
        DiagnosticEmitter diagnostics =
                DiagnosticEmitter.of(diagnosticsSettings, scenario, () -> CommandEchoSupport.from(plan.launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        try {
            return new OpenedSession(SessionRuntime.open(plan, diagnostics), diagnostics);
        } catch (RuntimeException | Error exception) {
            emitPreserving(
                    diagnostics,
                    DiagnosticEventType.PROCESS_FAILED,
                    DiagnosticEmitter.failureAttributes(exception),
                    exception);
            throw exception;
        }
    }

    private LineSession openLineSession(
            String scenario, SessionScenarioSettings<LineSession, LineSessionSettings> settings) {
        Objects.requireNonNull(settings, "settings");
        OpenedSession opened = openSession(
                scenario, settings.session().plan(), settings.session().diagnostics());
        try {
            LineSession lineSession = SessionScenarioSupport.openLineSession(opened.session(), settings.protocol());
            settings.readiness()
                    .probe()
                    .ifPresent(probe -> ReadinessSupport.check(
                            lineSession, probe, settings.readiness().timeout(), lineSession::close));
            return lineSession;
        } catch (RuntimeException | Error exception) {
            failOpen(opened, exception);
            throw exception;
        }
    }

    private <I extends Object, O extends Object> ProtocolSession<I, O> openProtocolSession(
            String scenario,
            ProtocolAdapter<I, O> adapter,
            SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> settings) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(settings, "settings");
        OpenedSession opened = openSession(
                scenario, settings.session().plan(), settings.session().diagnostics());
        try {
            ProtocolSession<I, O> protocolSession =
                    SessionScenarioSupport.openProtocolSession(opened.session(), adapter, settings.protocol());
            settings.readiness()
                    .probe()
                    .ifPresent(probe -> ReadinessSupport.check(
                            protocolSession, probe, settings.readiness().timeout(), protocolSession::close));
            return protocolSession;
        } catch (RuntimeException | Error exception) {
            failOpen(opened, exception);
            throw exception;
        }
    }

    private static void failOpen(OpenedSession opened, Throwable primaryFailure) {
        emitPreserving(
                opened.diagnostics(),
                DiagnosticEventType.PROCESS_FAILED,
                DiagnosticEmitter.failureAttributes(primaryFailure),
                primaryFailure);
        closePreserving(opened.session(), primaryFailure);
    }

    private static void closePreserving(Session session, Throwable primaryFailure) {
        try {
            session.close();
        } catch (RuntimeException | Error closeFailure) {
            SuppressionSupport.attach(primaryFailure, closeFailure);
        }
    }

    private static void emitPreserving(
            DiagnosticEmitter diagnostics,
            DiagnosticEventType type,
            java.util.Map<String, String> attributes,
            Throwable primaryFailure) {
        try {
            diagnostics.emit(type, attributes);
        } catch (RuntimeException | Error diagnosticFailure) {
            SuppressionSupport.attach(primaryFailure, diagnosticFailure);
        }
    }

    private record OpenedSession(Session session, DiagnosticEmitter diagnostics) {}
}

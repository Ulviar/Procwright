/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProcessKernel;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.ReadinessSettings;
import io.github.ulviar.procwright.internal.RunSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.SessionScenarioSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.internal.StreamSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.internal.session.ReadinessSupport;
import io.github.ulviar.procwright.internal.session.SessionRuntime;
import io.github.ulviar.procwright.internal.session.StreamRuntime;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.StreamSession;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Package-private terminal-operation boundary for immutable scenario drafts. */
final class ScenarioRuntime {

    private final CommandSpec commandSpec;
    private final ProcessKernel processKernel;

    ScenarioRuntime(CommandSpec commandSpec, ProcessKernel processKernel) {
        this.commandSpec = Objects.requireNonNull(commandSpec, "commandSpec");
        this.processKernel = Objects.requireNonNull(processKernel, "processKernel");
    }

    CommandSpec commandSpec() {
        return commandSpec;
    }

    CommandResult run(RunSettings settings) {
        return processKernel.run(Objects.requireNonNull(settings, "settings").plan());
    }

    Session interactive(SessionSettings settings, ReadinessSettings<Session> readiness) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(readiness, "readiness");
        return openReadyHandle(
                "interactive",
                settings.plan(),
                settings.diagnostics(),
                diagnostics -> SessionRuntime.open(settings.plan(), diagnostics),
                readiness);
    }

    StreamSession listen(StreamSettings settings) {
        return StreamRuntime.open(Objects.requireNonNull(settings, "settings").plan());
    }

    Expect openExpect(SessionSettings settings, ExpectSettings expectSettings, ReadinessSettings<Expect> readiness) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(expectSettings, "expectSettings");
        Objects.requireNonNull(readiness, "readiness");
        return openReadyHandle(
                "expect",
                settings.plan(),
                settings.diagnostics(),
                diagnostics -> SessionRuntime.openExpect(settings.plan(), diagnostics, expectSettings),
                readiness);
    }

    LineSession openLineSession(SessionScenarioSettings<LineSession, LineSessionSettings> settings) {
        return openLineSession("lineSession", settings);
    }

    PooledLineSession openPooledLineSession(
            SessionScenarioSettings<LineSession, LineSessionSettings> worker, WorkerPoolSettings<LineSession> pool) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(pool, "pool");
        return SessionRuntime.openPooledLineSession(() -> openLineSession("pooled", worker), worker.protocol(), pool);
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
        return SessionRuntime.openPooledProtocolSession(
                () -> openProtocolSession("pooledProtocol", createProtocolAdapter(adapterFactory), worker), pool);
    }

    static <I extends Object, O extends Object> ProtocolAdapter<I, O> createProtocolAdapter(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        Objects.requireNonNull(adapterFactory, "adapterFactory");
        return Objects.requireNonNull(adapterFactory.get(), "adapterFactory returned null");
    }

    private <T extends AutoCloseable> T openReadyHandle(
            String scenario,
            SessionExecutionPlan plan,
            DiagnosticsSettings diagnosticsSettings,
            Function<? super DiagnosticEmitter, ? extends T> opener,
            ReadinessSettings<T> readiness) {
        DiagnosticEmitter diagnostics =
                DiagnosticEmitter.of(diagnosticsSettings, scenario, () -> CommandEchoSupport.from(plan.launchPlan()));
        diagnostics.emitBestEffort(DiagnosticEventType.COMMAND_PREPARED);
        T handle = Objects.requireNonNull(opener.apply(diagnostics), "opener returned null");
        try {
            readiness
                    .probe()
                    .ifPresent(probe ->
                            ReadinessSupport.check(handle, probe, readiness.timeout(), () -> closeHandle(handle)));
            return handle;
        } catch (RuntimeException | Error failure) {
            diagnostics.emitBestEffort(
                    DiagnosticEventType.PROCESS_FAILED, DiagnosticEmitter.failureAttributes(failure));
            throw failure;
        }
    }

    private LineSession openLineSession(
            String scenario, SessionScenarioSettings<LineSession, LineSessionSettings> settings) {
        Objects.requireNonNull(settings, "settings");
        return openReadyHandle(
                scenario,
                settings.session().plan(),
                settings.session().diagnostics(),
                diagnostics ->
                        SessionRuntime.openLineSession(settings.session().plan(), diagnostics, settings.protocol()),
                settings.readiness());
    }

    private <I extends Object, O extends Object> ProtocolSession<I, O> openProtocolSession(
            String scenario,
            ProtocolAdapter<I, O> adapter,
            SessionScenarioSettings<ProtocolSession<I, O>, ProtocolSessionSettings> settings) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(settings, "settings");
        return openReadyHandle(
                scenario,
                settings.session().plan(),
                settings.session().diagnostics(),
                diagnostics -> SessionRuntime.openProtocolSession(
                        settings.session().plan(), diagnostics, adapter, settings.protocol()),
                settings.readiness());
    }

    private static void closeHandle(AutoCloseable handle) {
        try {
            handle.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not close session handle", failure);
        }
    }
}

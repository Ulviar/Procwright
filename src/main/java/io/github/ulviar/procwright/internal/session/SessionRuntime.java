/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProcessTransport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.Session;
import java.util.Objects;
import java.util.function.Supplier;

public final class SessionRuntime {

    private SessionRuntime() {}

    public static DefaultSession open(SessionExecutionPlan plan) {
        return open(
                plan,
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled(), "session", () -> CommandEchoSupport.from(plan.launchPlan())));
    }

    public static DefaultSession open(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        return open(plan, diagnostics, diagnostics);
    }

    static DefaultSession openForStream(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        DiagnosticEmitter sessionDiagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled(), "stream-session", () -> CommandEchoSupport.from(plan.launchPlan()));
        return open(plan, diagnostics, sessionDiagnostics);
    }

    public static LineSession openLineSession(Session session, LineSessionSettings options) {
        return new DefaultLineSession(requireDefaultSession(session), options);
    }

    public static PooledLineSession openPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options) {
        return new DefaultPooledLineSession(workerFactory, lineOptions, options);
    }

    public static <I, O> ProtocolSession<I, O> openProtocolSession(
            Session session, ProtocolAdapter<I, O> adapter, ProtocolSessionSettings options) {
        return new DefaultProtocolSession<>(requireDefaultSession(session), adapter, options);
    }

    public static <I, O> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, WorkerPoolSettings<ProtocolSession<I, O>> options) {
        return new DefaultPooledProtocolSession<>(workerFactory, options);
    }

    public static Expect openExpect(Session session, ExpectSettings options) {
        return new DefaultExpect(requireDefaultSession(session), Objects.requireNonNull(options, "options"));
    }

    private static DefaultSession requireDefaultSession(Session session) {
        if (Objects.requireNonNull(session, "session") instanceof DefaultSession defaultSession) {
            return defaultSession;
        }
        throw new IllegalArgumentException("Session must be a Procwright-created handle");
    }

    private static DefaultSession open(
            SessionExecutionPlan plan, DiagnosticEmitter lifecycleDiagnostics, DiagnosticEmitter sessionDiagnostics) {
        Process process;
        try {
            process = ProcessTransport.start(plan);
        } catch (RuntimeException | Error failure) {
            lifecycleDiagnostics.emitProcessFailure(failure);
            throw failure;
        }
        try {
            return DefaultSession.openTransactionally(
                    process,
                    plan.idleTimeout(),
                    plan.shutdownPolicy(),
                    plan.charset(),
                    sessionDiagnostics,
                    () -> lifecycleDiagnostics.emit(
                            DiagnosticEventType.PROCESS_STARTED,
                            DiagnosticEmitter.attributes("pid", Long.toString(process.pid()))));
        } catch (RuntimeException | Error failure) {
            lifecycleDiagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }
}

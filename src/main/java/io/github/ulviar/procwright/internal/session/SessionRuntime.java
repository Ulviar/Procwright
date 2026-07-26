/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProcessTransport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.StreamSession;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SessionRuntime {

    private SessionRuntime() {}

    public static DefaultSession open(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        Process process = startProcess(plan, diagnostics);
        try {
            return DefaultSession.openTransactionally(
                    process,
                    plan.idleTimeout(),
                    plan.shutdownPolicy(),
                    plan.charset(),
                    diagnostics,
                    () -> emitProcessStarted(diagnostics, process));
        } catch (RuntimeException | Error failure) {
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }

    public static Expect openExpect(SessionExecutionPlan plan, DiagnosticEmitter diagnostics, ExpectSettings options) {
        Objects.requireNonNull(options, "options");
        return openHelper(
                plan,
                diagnostics,
                diagnostics,
                SessionOutputMode.EXPECT,
                session -> new DefaultExpect(session, options));
    }

    public static LineSession openLineSession(
            SessionExecutionPlan plan, DiagnosticEmitter diagnostics, LineSessionSettings options) {
        Objects.requireNonNull(options, "options");
        return openHelper(
                plan,
                diagnostics,
                diagnostics,
                SessionOutputMode.LINE,
                session -> new DefaultLineSession(session, options));
    }

    public static <I, O> ProtocolSession<I, O> openProtocolSession(
            SessionExecutionPlan plan,
            DiagnosticEmitter diagnostics,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(options, "options");
        return openHelper(
                plan,
                diagnostics,
                diagnostics,
                SessionOutputMode.PROTOCOL,
                session -> new DefaultProtocolSession<>(session, adapter, options));
    }

    static StreamSession openStream(StreamExecutionPlan plan, DiagnosticEmitter diagnostics) {
        Objects.requireNonNull(plan, "plan");
        return openHelper(
                plan.sessionPlan(),
                diagnostics,
                diagnostics,
                SessionOutputMode.STREAM,
                session -> new DefaultStreamSession(session, plan, diagnostics));
    }

    public static PooledLineSession openPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options) {
        return new DefaultPooledLineSession(workerFactory, lineOptions, options);
    }

    public static <I, O> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, WorkerPoolSettings<ProtocolSession<I, O>> options) {
        return new DefaultPooledProtocolSession<>(workerFactory, options);
    }

    private static <T> T openHelper(
            SessionExecutionPlan plan,
            DiagnosticEmitter lifecycleDiagnostics,
            DiagnosticEmitter sessionDiagnostics,
            SessionOutputMode outputMode,
            Function<? super DefaultSession, ? extends T> handleFactory) {
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(handleFactory, "handleFactory");
        outputMode.requireHelper();
        Process process = startProcess(plan, lifecycleDiagnostics);
        try {
            return DefaultSession.openHelperTransactionally(
                    process,
                    plan.idleTimeout(),
                    plan.shutdownPolicy(),
                    plan.charset(),
                    sessionDiagnostics,
                    outputMode,
                    session -> {
                        emitProcessStarted(lifecycleDiagnostics, process);
                        return handleFactory.apply(session);
                    });
        } catch (RuntimeException | Error failure) {
            lifecycleDiagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }

    private static Process startProcess(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        try {
            return ProcessTransport.start(plan);
        } catch (RuntimeException | Error failure) {
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }

    private static void emitProcessStarted(DiagnosticEmitter diagnostics, Process process) {
        diagnostics.emitBestEffort(
                DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", Long.toString(process.pid())));
    }
}

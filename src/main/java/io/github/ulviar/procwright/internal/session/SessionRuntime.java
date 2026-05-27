package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.diagnostics.DiagnosticsOptions;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.ProcessTransport;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;

public final class SessionRuntime {

    private SessionRuntime() {}

    public static DefaultSession open(SessionExecutionPlan plan) {
        return open(
                plan,
                DiagnosticEmitter.of(
                        DiagnosticsOptions.defaults(), "session", () -> CommandEchoSupport.from(plan.launchPlan())));
    }

    public static DefaultSession open(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        Process process = ProcessTransport.resolve(plan).start(plan);
        diagnostics.emit(
                DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", Long.toString(process.pid())));
        return new DefaultSession(process, plan.idleTimeout(), plan.shutdownPolicy(), plan.charset(), diagnostics);
    }
}

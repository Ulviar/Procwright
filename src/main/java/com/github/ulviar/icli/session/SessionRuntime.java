package com.github.ulviar.icli.session;

import com.github.ulviar.icli.diagnostics.DiagnosticEventType;
import com.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import com.github.ulviar.icli.internal.CommandEchoSupport;
import com.github.ulviar.icli.internal.DiagnosticEmitter;
import com.github.ulviar.icli.internal.ProcessTransport;
import com.github.ulviar.icli.internal.SessionExecutionPlan;

public final class SessionRuntime {

    private SessionRuntime() {}

    public static Session open(SessionExecutionPlan plan) {
        return open(
                plan,
                DiagnosticEmitter.of(
                        DiagnosticsOptions.defaults(), "session", () -> CommandEchoSupport.from(plan.launchPlan())));
    }

    public static Session open(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        Process process = ProcessTransport.resolve(plan).start(plan);
        diagnostics.emit(
                DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", Long.toString(process.pid())));
        return new Session(process, plan.idleTimeout(), plan.shutdownPolicy(), plan.charset(), diagnostics);
    }
}

package com.github.ulviar.icli.session;

import com.github.ulviar.icli.diagnostics.DiagnosticEventType;
import com.github.ulviar.icli.internal.CommandEchoSupport;
import com.github.ulviar.icli.internal.DiagnosticEmitter;
import com.github.ulviar.icli.internal.StreamExecutionPlan;

public final class StreamRuntime {

    private StreamRuntime() {}

    public static StreamSession open(StreamExecutionPlan plan) {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                plan.diagnosticsOptions(),
                "listen",
                () -> CommandEchoSupport.from(plan.sessionPlan().launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        Session session;
        try {
            session = SessionRuntime.open(plan.sessionPlan());
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_STARTED,
                    DiagnosticEmitter.attributes("pid", Long.toString(session.pid())));
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    DiagnosticEmitter.attributes("error", exception.getClass().getName()));
            throw exception;
        }
        try {
            return new StreamSession(session, plan, diagnostics);
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
    }
}

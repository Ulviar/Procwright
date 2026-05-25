package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.diagnostics.DiagnosticEventType;
import io.github.ulviar.icli.internal.CommandEchoSupport;
import io.github.ulviar.icli.internal.DiagnosticEmitter;
import io.github.ulviar.icli.internal.StreamExecutionPlan;
import io.github.ulviar.icli.session.StreamSession;

public final class StreamRuntime {

    private StreamRuntime() {}

    public static StreamSession open(StreamExecutionPlan plan) {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                plan.diagnosticsOptions(),
                "listen",
                () -> CommandEchoSupport.from(plan.sessionPlan().launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        DefaultSession session;
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
            return new DefaultStreamSession(session, plan, diagnostics);
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
    }
}

package com.github.ulviar.icli;

final class StreamRuntime {

    private StreamRuntime() {}

    static StreamSession open(StreamExecutionPlan plan) {
        Diagnostics diagnostics = Diagnostics.of(
                plan.diagnosticsOptions(),
                "listen",
                () -> CommandEcho.from(plan.sessionPlan().launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        Session session;
        try {
            session = SessionRuntime.open(plan.sessionPlan());
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_STARTED, Diagnostics.attributes("pid", Long.toString(session.pid())));
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    Diagnostics.attributes("error", exception.getClass().getName()));
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

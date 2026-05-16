package com.github.ulviar.icli;

final class SessionRuntime {

    private SessionRuntime() {}

    static Session open(SessionExecutionPlan plan) {
        return open(
                plan, Diagnostics.of(DiagnosticsOptions.defaults(), "session", CommandEcho.from(plan.launchPlan())));
    }

    static Session open(SessionExecutionPlan plan, Diagnostics diagnostics) {
        Process process = ProcessTransport.resolve(plan).start(plan);
        diagnostics.emit(
                DiagnosticEventType.PROCESS_STARTED, Diagnostics.attributes("pid", Long.toString(process.pid())));
        return new Session(process, plan.idleTimeout(), plan.shutdownPolicy(), plan.charset(), diagnostics);
    }
}

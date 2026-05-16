package com.github.ulviar.icli;

final class SessionRuntime {

    private SessionRuntime() {}

    static Session open(SessionExecutionPlan plan) {
        Process process = ProcessLifecycle.start(plan.launchPlan());
        return new Session(process, plan.idleTimeout(), plan.shutdownPolicy(), plan.charset());
    }
}

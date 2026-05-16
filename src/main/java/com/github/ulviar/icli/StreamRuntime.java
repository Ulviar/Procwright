package com.github.ulviar.icli;

final class StreamRuntime {

    private StreamRuntime() {}

    static StreamSession open(StreamExecutionPlan plan) {
        Session session = SessionRuntime.open(plan.sessionPlan());
        try {
            return new StreamSession(session, plan);
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
    }
}

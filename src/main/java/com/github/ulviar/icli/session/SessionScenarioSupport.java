package com.github.ulviar.icli.session;

import java.util.function.Supplier;

/**
 * @hidden
 */
public final class SessionScenarioSupport {

    private SessionScenarioSupport() {}

    public static LineSession openLineSession(Session session, LineSessionOptions options) {
        return new LineSession(session, options);
    }

    public static PooledLineSessionInvocation.Builder pooledInvocationBuilder(PooledLineSessionOptions defaults) {
        return PooledLineSessionInvocation.builder(defaults);
    }

    public static PooledLineSession openPooledLineSession(
            Supplier<LineSession> workerFactory, PooledLineSessionOptions options) {
        return new PooledLineSession(workerFactory, options);
    }
}

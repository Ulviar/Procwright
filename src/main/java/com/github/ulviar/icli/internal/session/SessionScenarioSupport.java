package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledLineSessionOptions;
import com.github.ulviar.icli.session.Session;
import java.util.function.Supplier;

public final class SessionScenarioSupport {

    private SessionScenarioSupport() {}

    public static LineSession openLineSession(Session session, LineSessionOptions options) {
        return new DefaultLineSession(SessionInternals.requireDefaultSession(session), options);
    }

    public static PooledLineSession openPooledLineSession(
            Supplier<LineSession> workerFactory, PooledLineSessionOptions options) {
        return new DefaultPooledLineSession(workerFactory, options);
    }
}

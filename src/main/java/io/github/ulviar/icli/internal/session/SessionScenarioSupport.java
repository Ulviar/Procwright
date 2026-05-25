package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.session.LineSession;
import io.github.ulviar.icli.session.LineSessionOptions;
import io.github.ulviar.icli.session.PooledLineSession;
import io.github.ulviar.icli.session.PooledLineSessionOptions;
import io.github.ulviar.icli.session.PooledProtocolSession;
import io.github.ulviar.icli.session.PooledProtocolSessionInvocation;
import io.github.ulviar.icli.session.ProtocolAdapter;
import io.github.ulviar.icli.session.ProtocolSession;
import io.github.ulviar.icli.session.ProtocolSessionOptions;
import io.github.ulviar.icli.session.Session;
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

    public static <I, O> ProtocolSession<I, O> openProtocolSession(
            Session session, ProtocolAdapter<I, O> adapter, ProtocolSessionOptions options) {
        return new DefaultProtocolSession<>(SessionInternals.requireDefaultSession(session), adapter, options);
    }

    public static <I, O> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, PooledProtocolSessionInvocation<I, O> invocation) {
        return new DefaultPooledProtocolSession<>(workerFactory, invocation);
    }
}

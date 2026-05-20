package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledLineSessionOptions;
import com.github.ulviar.icli.session.PooledProtocolSession;
import com.github.ulviar.icli.session.PooledProtocolSessionInvocation;
import com.github.ulviar.icli.session.ProtocolAdapter;
import com.github.ulviar.icli.session.ProtocolSession;
import com.github.ulviar.icli.session.ProtocolSessionOptions;
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

    public static <I, O> ProtocolSession<I, O> openProtocolSession(
            Session session, ProtocolAdapter<I, O> adapter, ProtocolSessionOptions options) {
        return new DefaultProtocolSession<>(SessionInternals.requireDefaultSession(session), adapter, options);
    }

    public static <I, O> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, PooledProtocolSessionInvocation<I, O> invocation) {
        return new DefaultPooledProtocolSession<>(workerFactory, invocation);
    }
}

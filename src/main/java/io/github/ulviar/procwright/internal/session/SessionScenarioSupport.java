/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionOptions;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionInvocation;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.Session;
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

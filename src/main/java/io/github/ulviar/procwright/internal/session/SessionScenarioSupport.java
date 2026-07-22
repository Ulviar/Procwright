/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.Session;
import java.util.function.Supplier;

public final class SessionScenarioSupport {

    private SessionScenarioSupport() {}

    public static LineSession openLineSession(Session session, LineSessionSettings options) {
        return new DefaultLineSession(SessionInternals.requireDefaultSession(session), options);
    }

    public static PooledLineSession openPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options) {
        return new DefaultPooledLineSession(workerFactory, lineOptions, options);
    }

    public static <I, O> ProtocolSession<I, O> openProtocolSession(
            Session session, ProtocolAdapter<I, O> adapter, ProtocolSessionSettings options) {
        return new DefaultProtocolSession<>(SessionInternals.requireDefaultSession(session), adapter, options);
    }

    public static <I, O> PooledProtocolSession<I, O> openPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, WorkerPoolSettings<ProtocolSession<I, O>> options) {
        return new DefaultPooledProtocolSession<>(workerFactory, options);
    }
}

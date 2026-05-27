package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.Session;
import java.util.Objects;

public final class SessionInternals {

    private SessionInternals() {}

    public static DefaultSession requireDefaultSession(Session session) {
        Objects.requireNonNull(session, "session");
        if (session instanceof DefaultSession defaultSession) {
            return defaultSession;
        }
        throw new IllegalArgumentException("Session must be a Procwright-created handle");
    }
}

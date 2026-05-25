package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.session.Session;
import java.util.Objects;

public final class SessionInternals {

    private SessionInternals() {}

    public static DefaultSession requireDefaultSession(Session session) {
        Objects.requireNonNull(session, "session");
        if (session instanceof DefaultSession defaultSession) {
            return defaultSession;
        }
        throw new IllegalArgumentException("Session must be an iCLI-created handle");
    }
}

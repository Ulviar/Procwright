/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;

/** Typed immutable state shared by line and protocol scenario drafts. */
public record SessionScenarioSettings<H, P>(SessionSettings session, ReadinessSettings<H> readiness, P protocol) {

    public SessionScenarioSettings {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(protocol, "protocol");
    }

    public SessionScenarioSettings<H, P> withSession(SessionSettings updated) {
        return new SessionScenarioSettings<>(updated, readiness, protocol);
    }

    public SessionScenarioSettings<H, P> withReadiness(ReadinessSettings<H> updated) {
        return new SessionScenarioSettings<>(session, updated, protocol);
    }

    public SessionScenarioSettings<H, P> withProtocol(P updated) {
        return new SessionScenarioSettings<>(session, readiness, updated);
    }
}

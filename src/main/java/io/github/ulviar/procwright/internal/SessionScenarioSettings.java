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
}

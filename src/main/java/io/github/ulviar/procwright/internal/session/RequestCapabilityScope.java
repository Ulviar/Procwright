/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;

/** Confines an adapter capability to one callback invocation and its executing thread. */
final class RequestCapabilityScope {

    private final Object lock = new Object();
    private final String capabilityName;
    private final boolean unrestricted;
    private State state;
    private Thread owner;

    RequestCapabilityScope(String capabilityName) {
        this(capabilityName, false, State.PENDING);
    }

    private RequestCapabilityScope(String capabilityName, boolean unrestricted, State state) {
        this.capabilityName = Objects.requireNonNull(capabilityName, "capabilityName");
        this.unrestricted = unrestricted;
        this.state = Objects.requireNonNull(state, "state");
    }

    static RequestCapabilityScope unrestricted(String capabilityName) {
        return new RequestCapabilityScope(capabilityName, true, State.ACTIVE);
    }

    void activate() {
        if (unrestricted) {
            return;
        }
        synchronized (lock) {
            if (state != State.PENDING) {
                throw unavailable();
            }
            owner = Thread.currentThread();
            state = State.ACTIVE;
        }
    }

    void verifyAccess() {
        if (unrestricted) {
            return;
        }
        synchronized (lock) {
            if (state != State.ACTIVE || owner != Thread.currentThread()) {
                throw unavailable();
            }
        }
    }

    void invalidate() {
        if (unrestricted) {
            return;
        }
        synchronized (lock) {
            state = State.INVALID;
            owner = null;
        }
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException(
                capabilityName + " is available only on its owning callback thread during the current request");
    }

    private enum State {
        PENDING,
        ACTIVE,
        INVALID
    }
}

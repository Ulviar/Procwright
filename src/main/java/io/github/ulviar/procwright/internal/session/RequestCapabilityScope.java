/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;

/** Confines an adapter capability to one callback invocation and its executing thread. */
final class RequestCapabilityScope {

    private final Object lock = new Object();
    private final String capabilityName;
    private State state = State.PENDING;
    private Thread owner;

    RequestCapabilityScope(String capabilityName) {
        this.capabilityName = Objects.requireNonNull(capabilityName, "capabilityName");
    }

    void activate() {
        synchronized (lock) {
            if (state != State.PENDING) {
                throw unavailable();
            }
            owner = Thread.currentThread();
            state = State.ACTIVE;
        }
    }

    void verifyAccess() {
        synchronized (lock) {
            if (state != State.ACTIVE || owner != Thread.currentThread()) {
                throw unavailable();
            }
        }
    }

    void invalidate() {
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

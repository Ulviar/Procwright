/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.InputStream;
import java.util.Objects;

/** Keeps the output mode selected during construction. */
final class SessionOutputOwnership {

    private final SessionOutputMode outputMode;
    private HelperState helperState = HelperState.PLANNED;

    SessionOutputOwnership(SessionOutputMode mode) {
        outputMode = Objects.requireNonNull(mode, "mode");
    }

    InputStream publicStream(InputStream stream) {
        ensureRawOutput();
        return Objects.requireNonNull(stream, "stream");
    }

    void ensureOwnedBy(SessionOutputMode requestedMode) {
        ensurePlannedMode(requestedMode);
    }

    void claimHelper(SessionOutputMode requestedMode) {
        ensurePlannedMode(requestedMode);
        if (helperState != HelperState.PLANNED) {
            throw new IllegalStateException("Session output already has a helper owner");
        }
        helperState = HelperState.CLAIMED;
    }

    void markHelperReady(SessionOutputMode requestedMode) {
        ensurePlannedMode(requestedMode);
        if (helperState != HelperState.CLAIMED) {
            throw new IllegalStateException("Session helper did not claim its output mode exactly once");
        }
        helperState = HelperState.READY;
    }

    void requireHelperReady(SessionOutputMode requestedMode) {
        ensurePlannedMode(requestedMode);
        if (helperState != HelperState.READY) {
            throw new IllegalStateException("Session helper output pumps are not ready");
        }
    }

    boolean raw() {
        return outputMode.raw();
    }

    private void ensurePlannedMode(SessionOutputMode requestedMode) {
        Objects.requireNonNull(requestedMode, "requestedMode");
        if (outputMode.raw()) {
            throw new IllegalStateException("Session output is configured for public output streams");
        }
        if (outputMode != requestedMode) {
            throw new IllegalStateException("Session output is configured for " + outputMode.owner());
        }
    }

    private void ensureRawOutput() {
        if (!outputMode.raw()) {
            throw new IllegalStateException("Session output is configured for " + outputMode.owner());
        }
    }

    private enum HelperState {
        PLANNED,
        CLAIMED,
        READY
    }
}

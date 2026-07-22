/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Retains the typed failure selected by the session outcome owner for one user callback invocation. */
final class RequestFailureTracker<E extends RuntimeException> {

    private final AtomicReference<E> firstFailure = new AtomicReference<>();

    E record(E failure) {
        E candidate = Objects.requireNonNull(failure, "failure");
        firstFailure.compareAndSet(null, candidate);
        return firstFailure.get();
    }

    E failure() {
        return firstFailure.get();
    }

    E replaceWithTerminal(E failure) {
        E selected = Objects.requireNonNull(failure, "failure");
        firstFailure.set(selected);
        return selected;
    }

    void throwIfFailed() {
        E failure = firstFailure.get();
        if (failure != null) {
            throw failure;
        }
    }
}

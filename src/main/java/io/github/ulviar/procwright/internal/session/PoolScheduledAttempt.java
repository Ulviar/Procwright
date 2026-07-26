/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.function.Consumer;

/** Owns the race between immediate execution and attachment or cancellation of a scheduled pool task. */
final class PoolScheduledAttempt implements Runnable {

    private final Consumer<PoolScheduledAttempt> action;
    private Cancellation cancellation;
    private boolean started;
    private boolean cancelled;

    PoolScheduledAttempt(Consumer<PoolScheduledAttempt> action) {
        this.action = Objects.requireNonNull(action, "action");
    }

    @Override
    public void run() {
        synchronized (this) {
            if (started || cancelled) {
                return;
            }
            started = true;
            cancellation = null;
        }
        action.accept(this);
    }

    void attach(Cancellation selectedCancellation) {
        Objects.requireNonNull(selectedCancellation, "cancellation");
        boolean cancelNow;
        synchronized (this) {
            cancelNow = cancelled;
            if (!started && !cancelled) {
                cancellation = selectedCancellation;
            }
        }
        if (cancelNow) {
            selectedCancellation.cancel();
        }
    }

    void cancel() {
        Cancellation selectedCancellation;
        synchronized (this) {
            if (started || cancelled) {
                return;
            }
            cancelled = true;
            selectedCancellation = cancellation;
            cancellation = null;
        }
        if (selectedCancellation != null) {
            selectedCancellation.cancel();
        }
    }

    @FunctionalInterface
    interface Cancellation {

        Cancellation NONE = () -> {};

        void cancel();
    }
}

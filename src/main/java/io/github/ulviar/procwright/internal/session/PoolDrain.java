/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the single terminal outcome of a pool and cancellation-isolated views of that outcome. */
final class PoolDrain {

    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final CompletableFuture<Void> terminal = new CompletableFuture<>();
    private final PoolTerminalPublisher publisher;
    private final TerminalAction terminalAction = new TerminalAction();

    PoolDrain(PoolTerminalPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    CompletableFuture<Void> view() {
        return terminal.copy();
    }

    boolean claimed() {
        return state.get() != State.OPEN;
    }

    Publication claim(Throwable failure) {
        return state.compareAndSet(State.OPEN, State.CLAIMED) ? new Publication(this, failure) : null;
    }

    private void publish(Publication publication) {
        if (publication.owner != this) {
            throw new IllegalArgumentException("pool drain publication belongs to another drain");
        }
        if (!state.compareAndSet(State.CLAIMED, State.PUBLISHED)) {
            throw new IllegalStateException("pool drain outcome is not exclusively claimed");
        }
        terminalAction.failure = publication.failure;
        publisher.assign(terminalAction);
    }

    private void complete(Throwable failure) {
        if (failure == null) {
            terminal.complete(null);
        } else {
            terminal.completeExceptionally(failure);
        }
    }

    private final class TerminalAction implements Runnable {

        private Throwable failure;

        @Override
        public void run() {
            complete(failure);
        }
    }

    static final class Publication {

        private final PoolDrain owner;
        private final Throwable failure;

        private Publication(PoolDrain owner, Throwable failure) {
            this.owner = owner;
            this.failure = failure;
        }

        Throwable failure() {
            return failure;
        }

        void publish() {
            owner.publish(this);
        }
    }

    private enum State {
        OPEN,
        CLAIMED,
        PUBLISHED
    }
}

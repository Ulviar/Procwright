/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns exact-once initiation and one stable outcome for a worker retirement. */
final class WorkerRetirement<S> {

    private final S session;
    private final Action<S> action;
    private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
    private boolean initiationStarted;

    WorkerRetirement(S session, Action<S> action) {
        this.session = Objects.requireNonNull(session, "session");
        this.action = Objects.requireNonNull(action, "action");
    }

    void initiate() {
        synchronized (this) {
            if (initiationStarted) {
                return;
            }
            initiationStarted = true;
        }
        CompletableFuture<Outcome> selected;
        try {
            selected = Objects.requireNonNull(action.initiate(session), "worker close action returned null future");
        } catch (Throwable failure) {
            outcome.complete(Outcome.failure(failure));
            return;
        }
        observe(selected);
    }

    CompletableFuture<Outcome> outcome() {
        initiate();
        return outcome;
    }

    private void observe(CompletableFuture<Outcome> selected) {
        try {
            selected.whenComplete((closeOutcome, failure) -> {
                if (failure != null) {
                    outcome.complete(Outcome.failure(unwrap(failure)));
                } else if (closeOutcome == null) {
                    outcome.complete(Outcome.failure(new NullPointerException("worker close future returned null")));
                } else {
                    outcome.complete(closeOutcome);
                }
            });
        } catch (Throwable failure) {
            outcome.complete(Outcome.failure(failure));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause()
                : failure;
    }

    @FunctionalInterface
    interface Action<S> {

        CompletableFuture<Outcome> initiate(S session);
    }

    record Outcome(Throwable failure) {

        static Outcome success() {
            return new Outcome(null);
        }

        static Outcome failure(Throwable failure) {
            return new Outcome(Objects.requireNonNull(failure, "failure"));
        }
    }
}

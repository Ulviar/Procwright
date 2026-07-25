/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns exact-once initiation and one stable outcome for a worker retirement. */
final class WorkerRetirement<S> {

    private final Action<S> action;
    private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
    private S session;
    private PoolLifecycleDispatcher.Admission admission;
    private boolean initiationStarted;

    WorkerRetirement(Action<S> action) {
        this.action = Objects.requireNonNull(action, "action");
    }

    synchronized void admission(PoolLifecycleDispatcher.Admission acceptedAdmission) {
        if (admission != null) {
            throw new IllegalStateException("worker retirement admission is already owned");
        }
        admission = Objects.requireNonNull(acceptedAdmission, "acceptedAdmission");
    }

    synchronized PoolLifecycleDispatcher.Admission admissionOrNull() {
        return admission;
    }

    synchronized PoolLifecycleDispatcher.Admission detachAdmission() {
        PoolLifecycleDispatcher.Admission owned = admission;
        admission = null;
        return owned;
    }

    synchronized void accept(S acceptedSession) {
        if (session != null) {
            throw new IllegalStateException("worker session is already accepted");
        }
        session = Objects.requireNonNull(acceptedSession, "workerFactory returned null");
    }

    void initiate() {
        S acceptedSession;
        PoolLifecycleDispatcher.Admission acceptedAdmission;
        synchronized (this) {
            if (initiationStarted) {
                return;
            }
            acceptedSession = Objects.requireNonNull(session, "worker has no accepted session");
            acceptedAdmission = Objects.requireNonNull(admission, "worker has no retirement admission");
            initiationStarted = true;
        }
        CompletableFuture<Outcome> selected;
        try {
            selected = Objects.requireNonNull(
                    action.initiate(acceptedSession, acceptedAdmission), "worker close action returned null future");
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

        CompletableFuture<Outcome> initiate(S session, PoolLifecycleDispatcher.Admission admission);
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

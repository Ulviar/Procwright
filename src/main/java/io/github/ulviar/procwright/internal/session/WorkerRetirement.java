/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns exact-once initiation and normalized observation of one worker retirement. */
final class WorkerRetirement<S> {

    private final Action<S> action;
    private S session;
    private PoolLifecycleDispatcher.Admission admission;
    private Observation observation;
    private CompletableFuture<Outcome> outcome;

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

    synchronized void initiate() {
        if (observation != null) {
            return;
        }
        try {
            observation = Objects.requireNonNull(
                    action.initiate(
                            Objects.requireNonNull(session, "worker has no accepted session"),
                            Objects.requireNonNull(admission, "worker has no retirement admission")),
                    "worker close action returned null observation");
        } catch (Throwable failure) {
            observation = () -> CompletableFuture.completedFuture(Outcome.failure(failure));
        }
    }

    synchronized CompletableFuture<Outcome> outcome() {
        initiate();
        if (outcome == null) {
            outcome = observe();
        }
        return outcome;
    }

    private CompletableFuture<Outcome> observe() {
        try {
            CompletableFuture<Outcome> observed =
                    Objects.requireNonNull(observation.outcome(), "worker close observation returned null future");
            return observed.handle((closeOutcome, failure) -> {
                if (failure != null) {
                    return Outcome.failure(unwrap(failure));
                }
                if (closeOutcome == null) {
                    return Outcome.failure(new NullPointerException("worker close observation returned null"));
                }
                return closeOutcome;
            });
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(WorkerRetirement.Outcome.failure(failure));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause()
                : failure;
    }

    @FunctionalInterface
    interface Action<S> {

        Observation initiate(S session, PoolLifecycleDispatcher.Admission admission);
    }

    @FunctionalInterface
    interface Observation {

        CompletableFuture<Outcome> outcome();
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

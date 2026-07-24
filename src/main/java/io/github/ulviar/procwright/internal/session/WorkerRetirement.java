/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns exact-once initiation and normalized observation of one worker retirement. */
final class WorkerRetirement<S> {

    private final S session;
    private final PoolLifecycleDispatcher.Admission admission;
    private final Action<S> action;
    private Observation observation;
    private CompletableFuture<Outcome> outcome;

    WorkerRetirement(S session, PoolLifecycleDispatcher.Admission admission, Action<S> action) {
        this.session = Objects.requireNonNull(session, "session");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.action = Objects.requireNonNull(action, "action");
    }

    synchronized void initiate() {
        if (observation != null) {
            return;
        }
        try {
            observation = Objects.requireNonNull(
                    action.initiate(session, admission), "worker close action returned null observation");
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Owns publication of the public exit view after every registered cleanup dependency has settled. */
final class SessionExitBarrier {

    private final Object lock = new Object();
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private ProcessCompletion process = ProcessPending.INSTANCE;
    private OutputCompletion output = OutputPending.INSTANCE;
    private int helpers;
    private boolean publicationClaimed;

    SessionExitBarrier() {}

    void observe(
            CompletableFuture<SessionTermination.Outcome> processTerminal,
            CompletableFuture<SessionOutputCleanup.Outcome> outputCleanup) {
        Objects.requireNonNull(processTerminal, "processTerminal").whenComplete(this::processCompleted);
        Objects.requireNonNull(outputCleanup, "outputCleanup").whenComplete(this::outputCompleted);
    }

    CompletableFuture<SessionExit> view() {
        CompletableFuture<SessionExit> view = new CompletableFuture<>();
        exit.whenComplete((result, failure) -> {
            if (failure == null) {
                view.complete(result);
            } else {
                view.completeExceptionally(failure);
            }
        });
        return view;
    }

    boolean completed() {
        return exit.isDone();
    }

    Registration registerHelper() {
        Registration registration = new Registration(this);
        synchronized (lock) {
            if (publicationClaimed) {
                throw new IllegalStateException("Session cleanup publication has already been claimed");
            }
            helpers++;
        }
        return registration;
    }

    private void processCompleted(SessionTermination.Outcome outcome, Throwable impossibleFailure) {
        PublicationInputs ready;
        synchronized (lock) {
            if (process instanceof ProcessSettled) {
                throw new IllegalStateException("Process terminal cleanup was already recorded");
            }
            SessionTermination.Outcome selected = impossibleFailure == null
                    ? Objects.requireNonNull(outcome, "outcome")
                    : new SessionTermination.Outcome(null, List.of(impossibleFailure));
            process = new ProcessSettled(selected);
            ready = claimPublicationLocked();
        }
        publishIfReady(ready);
    }

    private void outputCompleted(SessionOutputCleanup.Outcome outcome, Throwable impossibleFailure) {
        PublicationInputs ready;
        synchronized (lock) {
            if (output instanceof OutputSettled) {
                throw new IllegalStateException("Physical output cleanup was already recorded");
            }
            SessionOutputCleanup.Outcome selected = impossibleFailure == null
                    ? Objects.requireNonNull(outcome, "outcome")
                    : new SessionOutputCleanup.Outcome(
                            List.of(impossibleFailure), SessionOutputCleanup.PhysicalClose.Success.INSTANCE);
            output = new OutputSettled(selected);
            ready = claimPublicationLocked();
        }
        publishIfReady(ready);
    }

    private void helperCompleted() {
        PublicationInputs ready;
        synchronized (lock) {
            if (helpers <= 0) {
                throw new IllegalStateException("Helper cleanup barrier accounting underflow");
            }
            helpers--;
            ready = claimPublicationLocked();
        }
        publishIfReady(ready);
    }

    private PublicationInputs claimPublicationLocked() {
        if (publicationClaimed
                || helpers != 0
                || !(process instanceof ProcessSettled processSettled)
                || !(output instanceof OutputSettled outputSettled)) {
            return null;
        }
        publicationClaimed = true;
        return new PublicationInputs(processSettled.outcome(), outputSettled.outcome());
    }

    private void publishIfReady(PublicationInputs ready) {
        if (ready == null) {
            return;
        }
        PublicationAction action = publicationAction(ready);
        action.complete(exit);
    }

    private static PublicationAction publicationAction(PublicationInputs ready) {
        List<Throwable> failures = new ArrayList<>(5);
        failures.addAll(ready.process().failures());
        failures.addAll(ready.output().inlineFailures());
        if (ready.output().physicalClose()
                instanceof SessionOutputCleanup.PhysicalClose.LifecycleFailure physicalFailure) {
            if (failures.isEmpty()) {
                reportPhysicalFailure(physicalFailure);
            } else {
                failures.addAll(FailureAggregation.sources(physicalFailure.failure()));
            }
        }
        Throwable failure = FailureAggregation.combine(failures, "Process termination or output cleanup failed");
        return new PublicationAction(ready.process().result(), failure);
    }

    private static void reportPhysicalFailure(SessionOutputCleanup.PhysicalClose.LifecycleFailure failure) {
        failure.reportingTarget().ifPresent(target -> reportPhysicalFailure(target, failure.failure()));
    }

    private static void reportPhysicalFailure(BoundedFailureReporter.FailureTarget target, Throwable failure) {
        try {
            BoundedFailureReporter.shared().report(target, failure);
        } catch (RuntimeException | Error ignored) {
            // Reporting is best effort and cannot replace a successful process outcome.
        }
    }

    private sealed interface ProcessCompletion permits ProcessPending, ProcessSettled {}

    private enum ProcessPending implements ProcessCompletion {
        INSTANCE
    }

    private record ProcessSettled(SessionTermination.Outcome outcome) implements ProcessCompletion {

        private ProcessSettled {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private sealed interface OutputCompletion permits OutputPending, OutputSettled {}

    private enum OutputPending implements OutputCompletion {
        INSTANCE
    }

    private record OutputSettled(SessionOutputCleanup.Outcome outcome) implements OutputCompletion {

        private OutputSettled {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private record PublicationInputs(SessionTermination.Outcome process, SessionOutputCleanup.Outcome output) {

        private PublicationInputs {
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(output, "output");
        }
    }

    private record PublicationAction(SessionExit result, Throwable failure) {

        private void complete(CompletableFuture<SessionExit> target) {
            if (failure == null) {
                target.complete(result);
            } else {
                target.completeExceptionally(failure);
            }
        }
    }

    static final class Registration {

        private final SessionExitBarrier owner;
        private boolean settled;

        private Registration(SessionExitBarrier owner) {
            this.owner = owner;
        }

        void complete() {
            synchronized (this) {
                if (settled) {
                    return;
                }
                settled = true;
            }
            owner.helperCompleted();
        }

        void rollback() {
            complete();
        }
    }
}

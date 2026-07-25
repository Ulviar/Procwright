/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Owns the raw session lifecycle state, terminal winner, and internal terminal publication. */
final class SessionTermination {

    private final DiagnosticEmitter diagnostics;
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    private final Object lock = new Object();
    private Phase phase = Running.INSTANCE;

    SessionTermination(DiagnosticEmitter diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void ensureOpenForOutputClaim() {
        synchronized (lock) {
            if (!(phase instanceof Running)) {
                throw new IllegalStateException("Session is closed");
            }
        }
    }

    boolean beginClosing() {
        synchronized (lock) {
            if (!(phase instanceof Running)) {
                return false;
            }
            phase = Closing.INSTANCE;
            return true;
        }
    }

    boolean published() {
        return completion.isDone();
    }

    void observe(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        Objects.requireNonNull(observer, "observer");
        completion.whenComplete((terminalOutcome, impossibleFailure) -> {
            if (impossibleFailure == null) {
                observer.accept(terminalOutcome.result(), terminalOutcome.failure());
            } else {
                observer.accept(null, impossibleFailure);
            }
        });
    }

    CompletableFuture<Outcome> outcome() {
        CompletableFuture<Outcome> view = new CompletableFuture<>();
        completion.whenComplete((terminalOutcome, impossibleFailure) -> {
            if (impossibleFailure == null) {
                view.complete(terminalOutcome);
            } else {
                view.completeExceptionally(impossibleFailure);
            }
        });
        return view;
    }

    Publication claimNaturalSuccess() {
        synchronized (lock) {
            if (!(phase instanceof Running)) {
                return null;
            }
            return selectPublicationLocked();
        }
    }

    Publication claimCloseSuccess() {
        synchronized (lock) {
            if (!(phase instanceof Closing)) {
                return null;
            }
            return selectPublicationLocked();
        }
    }

    FailureClaim claimFailure(Throwable terminalFailure) {
        Objects.requireNonNull(terminalFailure, "terminalFailure");
        synchronized (lock) {
            if (phase instanceof Publishing) {
                return null;
            }
            if (phase instanceof Selected selected) {
                phase = selected.withFailure(terminalFailure).withAdditionalCleanup();
                return new FailureClaim(false);
            }
            Publication publication = new Publication();
            phase = new Selected(publication, FailureRequest.INSTANCE, List.of(terminalFailure), 1);
            return new FailureClaim(true);
        }
    }

    final class Publication {

        void publishSuccess(SessionExit sessionExit) {
            request(new SuccessRequest(Objects.requireNonNull(sessionExit, "sessionExit")));
        }

        void recordFailure(Throwable terminalFailure) {
            Objects.requireNonNull(terminalFailure, "terminalFailure");
            synchronized (lock) {
                if (!(phase instanceof Selected selected) || selected.owner() != this) {
                    throw new IllegalStateException("Terminal publication has already started");
                }
                phase = selected.withFailure(terminalFailure);
            }
        }

        void publishFailure() {
            request(FailureRequest.INSTANCE);
        }

        private void request(PublicationRequest request) {
            Publishing pending;
            synchronized (lock) {
                if (!(phase instanceof Selected selected) || selected.owner() != this) {
                    throw new IllegalStateException("Terminal outcome has already been published");
                }
                if (!(selected.request() instanceof AwaitingRequest)) {
                    throw new IllegalStateException("Terminal outcome has already been published");
                }
                if (request instanceof FailureRequest && selected.failures().isEmpty()) {
                    throw new IllegalStateException("Failure publication has no recorded failure");
                }
                Selected requested = selected.withRequest(request);
                phase = requested;
                pending = claimPublicationLocked(requested);
            }
            publish(pending);
        }
    }

    final class FailureClaim {

        private final boolean ownsPublication;
        private boolean cleanupFinished;

        private FailureClaim(boolean ownsPublication) {
            this.ownsPublication = ownsPublication;
        }

        boolean ownsPublication() {
            return ownsPublication;
        }

        void recordFailure(Throwable cleanupFailure) {
            Objects.requireNonNull(cleanupFailure, "cleanupFailure");
            synchronized (lock) {
                if (cleanupFinished) {
                    throw new IllegalStateException("Terminal failure cleanup has already finished");
                }
                Selected selected = requireSelectedLocked();
                phase = selected.withFailure(cleanupFailure);
            }
        }

        void finishCleanup() {
            Publishing pending;
            synchronized (lock) {
                if (cleanupFinished) {
                    throw new IllegalStateException("Terminal failure cleanup has already finished");
                }
                cleanupFinished = true;
                Selected selected = requireSelectedLocked().withFinishedCleanup();
                phase = selected;
                pending = claimPublicationLocked(selected);
            }
            publish(pending);
        }
    }

    private Publication selectPublicationLocked() {
        Publication publication = new Publication();
        phase = new Selected(publication, AwaitingRequest.INSTANCE, List.of(), 0);
        return publication;
    }

    private Publishing claimPublicationLocked(Selected selected) {
        if (selected.pendingFailureCleanups() != 0 || selected.request() instanceof AwaitingRequest) {
            return null;
        }
        Publishing pending = new Publishing(selected.request(), selected.failures());
        phase = pending;
        return pending;
    }

    private void publish(Publishing pending) {
        if (pending == null) {
            return;
        }
        if (!pending.failures().isEmpty()) {
            publishCanonicalFailure(pending.failures());
            return;
        }
        if (!(pending.request() instanceof SuccessRequest success)) {
            throw new IllegalStateException("Failure publication has no recorded failure");
        }
        SessionExit sessionExit = success.result();
        try {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_EXITED, exitAttributes(sessionExit.exitCode(), sessionExit.timedOut()));
        } catch (RuntimeException | Error publicationFailure) {
            Outcome publishedOutcome = new Outcome(null, List.of(publicationFailure));
            complete(publishedOutcome);
            throw publicationFailure;
        }
        Outcome publishedOutcome = new Outcome(sessionExit, List.of());
        complete(publishedOutcome);
    }

    private void publishCanonicalFailure(List<Throwable> terminalFailures) {
        List<Throwable> publicationFailures = normalizeFailures(terminalFailures);
        publicationFailures = emit(
                publicationFailures,
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                DiagnosticEmitter.attributes("reason", "failure"));
        publicationFailures = emit(
                publicationFailures,
                DiagnosticEventType.PROCESS_FAILED,
                DiagnosticEmitter.failureAttributes(publicationFailures.get(0)));
        Outcome publishedOutcome = new Outcome(null, publicationFailures);
        complete(publishedOutcome);
    }

    private void complete(Outcome terminalOutcome) {
        if (!completion.complete(Objects.requireNonNull(terminalOutcome, "terminalOutcome"))) {
            throw new IllegalStateException("Terminal outcome has already been completed");
        }
    }

    private Selected requireSelectedLocked() {
        if (phase instanceof Selected selected) {
            return selected;
        }
        throw new IllegalStateException("Terminal publication has already started");
    }

    private static List<Throwable> appendDistinct(List<Throwable> failures, Throwable failure) {
        List<Throwable> updated = new ArrayList<>(
                failures.size() + FailureAggregation.sources(failure).size());
        updated.addAll(failures);
        updated.addAll(FailureAggregation.sources(failure));
        return normalizeFailures(updated);
    }

    static final class Outcome {

        private final SessionExit result;
        private final List<Throwable> failures;
        private final Throwable failure;

        Outcome(SessionExit result, List<? extends Throwable> failures) {
            this.result = result;
            this.failures = normalizeFailures(failures);
            if ((result == null) == this.failures.isEmpty()) {
                throw new IllegalArgumentException("A terminal outcome must contain either a result or failures");
            }
            failure = FailureAggregation.combine(this.failures, "Session termination or diagnostics failed");
        }

        SessionExit result() {
            return result;
        }

        List<Throwable> failures() {
            return failures;
        }

        Throwable failure() {
            return failure;
        }
    }

    private List<Throwable> emit(
            List<Throwable> publicationFailures, DiagnosticEventType type, Map<String, String> attributes) {
        try {
            diagnostics.emit(type, attributes);
            return publicationFailures;
        } catch (RuntimeException | Error diagnosticFailure) {
            return appendDistinct(publicationFailures, diagnosticFailure);
        }
    }

    private static List<Throwable> normalizeFailures(List<? extends Throwable> failures) {
        Objects.requireNonNull(failures, "failures");
        if (failures.isEmpty()) {
            return List.of();
        }
        List<Throwable> normalized = new ArrayList<>(failures.size());
        IdentityHashMap<Throwable, Boolean> identities = new IdentityHashMap<>();
        Throwable primary = FailureAggregation.primary(Objects.requireNonNull(failures.get(0), "failure"));
        identities.put(primary, Boolean.TRUE);
        normalized.add(primary);
        for (Throwable failure : failures) {
            for (Throwable source : FailureAggregation.sources(Objects.requireNonNull(failure, "failure"))) {
                if (identities.put(source, Boolean.TRUE) == null) {
                    normalized.add(source);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private sealed interface Phase permits Running, Closing, Selected, Publishing {}

    private enum Running implements Phase {
        INSTANCE
    }

    private enum Closing implements Phase {
        INSTANCE
    }

    private record Selected(
            Publication owner, PublicationRequest request, List<Throwable> failures, int pendingFailureCleanups)
            implements Phase {

        private Selected {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(request, "request");
            failures = List.copyOf(failures);
            if (pendingFailureCleanups < 0) {
                throw new IllegalArgumentException("pendingFailureCleanups must not be negative");
            }
        }

        private Selected withRequest(PublicationRequest newRequest) {
            return new Selected(owner, newRequest, failures, pendingFailureCleanups);
        }

        private Selected withFailure(Throwable failure) {
            return new Selected(owner, request, appendDistinct(failures, failure), pendingFailureCleanups);
        }

        private Selected withAdditionalCleanup() {
            return new Selected(owner, request, failures, pendingFailureCleanups + 1);
        }

        private Selected withFinishedCleanup() {
            if (pendingFailureCleanups == 0) {
                throw new IllegalStateException("Terminal failure cleanup accounting underflow");
            }
            return new Selected(owner, request, failures, pendingFailureCleanups - 1);
        }
    }

    private record Publishing(PublicationRequest request, List<Throwable> failures) implements Phase {

        private Publishing {
            Objects.requireNonNull(request, "request");
            failures = List.copyOf(failures);
        }
    }

    private sealed interface PublicationRequest permits AwaitingRequest, SuccessRequest, FailureRequest {}

    private enum AwaitingRequest implements PublicationRequest {
        INSTANCE
    }

    private record SuccessRequest(SessionExit result) implements PublicationRequest {

        private SuccessRequest {
            Objects.requireNonNull(result, "result");
        }
    }

    private enum FailureRequest implements PublicationRequest {
        INSTANCE
    }
}

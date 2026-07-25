/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.ArrayList;
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
    private final CompletableFuture<SessionExit> terminal = new CompletableFuture<>();
    private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
    private final Object lock = new Object();
    private List<Throwable> failures;
    private State state = State.RUNNING;
    private Publication publicationOwner;
    private int pendingFailureCleanups;
    private boolean publicationStarted;

    SessionTermination(DiagnosticEmitter diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void ensureOpenForOutputClaim() {
        synchronized (lock) {
            if (state == State.CLOSING || state == State.CLOSED) {
                throw new IllegalStateException("Session is closed");
            }
        }
    }

    boolean beginClosing() {
        synchronized (lock) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return false;
            }
            state = State.CLOSING;
            return true;
        }
    }

    boolean closedAndPublished() {
        synchronized (lock) {
            return state == State.CLOSED && terminal.isDone();
        }
    }

    boolean published() {
        return terminal.isDone();
    }

    void observe(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        terminal.whenComplete(Objects.requireNonNull(observer, "observer"));
    }

    CompletableFuture<SessionExit> completion() {
        return terminal;
    }

    CompletableFuture<Outcome> outcome() {
        return outcome;
    }

    Publication claimNaturalSuccess() {
        synchronized (lock) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return null;
            }
            state = State.CLOSED;
            return createPublicationLocked();
        }
    }

    Publication claimCloseSuccess() {
        synchronized (lock) {
            if (state != State.CLOSING) {
                return null;
            }
            state = State.CLOSED;
            return createPublicationLocked();
        }
    }

    FailureClaim claimFailure(Throwable terminalFailure) {
        Objects.requireNonNull(terminalFailure, "terminalFailure");
        FailureClaim claim;
        synchronized (lock) {
            if (publicationStarted) {
                return null;
            }
            recordFailureLocked(terminalFailure);
            pendingFailureCleanups++;
            boolean ownsPublication = state != State.CLOSED;
            if (ownsPublication) {
                state = State.CLOSED;
                createPublicationLocked();
            }
            claim = new FailureClaim(ownsPublication);
        }
        return claim;
    }

    final class Publication {

        private boolean requested;
        private SessionExit result;

        void publishSuccess(SessionExit sessionExit) {
            request(Objects.requireNonNull(sessionExit, "sessionExit"), null);
        }

        void recordFailure(Throwable terminalFailure) {
            Objects.requireNonNull(terminalFailure, "terminalFailure");
            synchronized (lock) {
                if (publicationStarted) {
                    throw new IllegalStateException("Terminal publication has already started");
                }
                recordFailureLocked(terminalFailure);
            }
        }

        void publishFailure() {
            request(null, null);
        }

        private void request(SessionExit sessionExit, Throwable terminalFailure) {
            PendingPublication pending;
            synchronized (lock) {
                if (requested) {
                    throw new IllegalStateException("Terminal outcome has already been published");
                }
                if (terminalFailure != null) {
                    if (publicationStarted) {
                        throw new IllegalStateException("Terminal publication has already started");
                    }
                    recordFailureLocked(terminalFailure);
                }
                if (sessionExit == null && failures == null) {
                    throw new IllegalStateException("Failure publication has no recorded failure");
                }
                requested = true;
                result = sessionExit;
                pending = claimPublicationLocked();
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
                recordFailureLocked(cleanupFailure);
            }
        }

        void finishCleanup() {
            PendingPublication pending;
            synchronized (lock) {
                if (cleanupFinished) {
                    throw new IllegalStateException("Terminal failure cleanup has already finished");
                }
                cleanupFinished = true;
                pendingFailureCleanups--;
                if (pendingFailureCleanups < 0) {
                    throw new IllegalStateException("Terminal failure cleanup accounting underflow");
                }
                if (ownsPublication) {
                    if (publicationOwner.requested) {
                        throw new IllegalStateException("Failure publication has already been requested");
                    }
                    publicationOwner.requested = true;
                    publicationOwner.result = null;
                }
                pending = claimPublicationLocked();
            }
            publish(pending);
        }
    }

    private Publication createPublicationLocked() {
        if (publicationOwner != null) {
            throw new IllegalStateException("Terminal publication already has an owner");
        }
        publicationOwner = new Publication();
        return publicationOwner;
    }

    private PendingPublication claimPublicationLocked() {
        if (publicationStarted
                || pendingFailureCleanups != 0
                || publicationOwner == null
                || !publicationOwner.requested) {
            return null;
        }
        publicationStarted = true;
        return new PendingPublication(publicationOwner.result, failures == null ? List.of() : List.copyOf(failures));
    }

    private void publish(PendingPublication pending) {
        if (pending == null) {
            return;
        }
        if (!pending.failures().isEmpty()) {
            publishCanonicalFailure(pending.failures());
            return;
        }
        SessionExit sessionExit = Objects.requireNonNull(pending.result(), "successful session exit");
        try {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_EXITED, exitAttributes(sessionExit.exitCode(), sessionExit.timedOut()));
            terminal.complete(sessionExit);
            outcome.complete(new Outcome(sessionExit, List.of()));
        } catch (RuntimeException | Error publicationFailure) {
            synchronized (lock) {
                recordFailureLocked(publicationFailure);
            }
            terminal.completeExceptionally(publicationFailure);
            outcome.complete(new Outcome(null, List.of(publicationFailure)));
            throw publicationFailure;
        }
    }

    private void publishCanonicalFailure(List<Throwable> terminalFailures) {
        List<Throwable> publicationFailures = new ArrayList<>(terminalFailures);
        emit(
                publicationFailures,
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                DiagnosticEmitter.attributes("reason", "failure"));
        emit(
                publicationFailures,
                DiagnosticEventType.PROCESS_FAILED,
                DiagnosticEmitter.failureAttributes(terminalFailures.get(0)));
        Throwable publishedFailure =
                FailureAggregation.combine(publicationFailures, "Session termination or diagnostics failed");
        terminal.completeExceptionally(publishedFailure);
        outcome.complete(new Outcome(null, publicationFailures));
    }

    private record PendingPublication(SessionExit result, List<Throwable> failures) {}

    record Outcome(SessionExit result, List<Throwable> failures) {

        Outcome {
            failures = List.copyOf(failures);
            if ((result == null) == failures.isEmpty()) {
                throw new IllegalArgumentException("A terminal outcome must contain either a result or failures");
            }
        }
    }

    private void recordFailureLocked(Throwable terminalFailure) {
        if (failures != null) {
            for (Throwable failure : failures) {
                if (failure == terminalFailure) {
                    return;
                }
            }
        } else {
            failures = new ArrayList<>(2);
        }
        failures.add(terminalFailure);
    }

    private void emit(List<Throwable> publicationFailures, DiagnosticEventType type, Map<String, String> attributes) {
        try {
            diagnostics.emit(type, attributes);
        } catch (RuntimeException | Error diagnosticFailure) {
            publicationFailures.add(diagnosticFailure);
        }
    }

    private static Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private enum State {
        RUNNING,
        CLOSING,
        CLOSED
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns process terminal selection and the public exit assembled from process and output-mode settlement. */
final class SessionTerminal {

    private final DiagnosticEmitter diagnostics;
    private final CompletableFuture<ProcessOutcome> processCompletion = new CompletableFuture<>();
    private final CompletableFuture<ProcessOutcome> primaryCompletion = new CompletableFuture<>();
    private final CompletableFuture<PublicOutcome> publicCompletion = new CompletableFuture<>();
    private final Object lock = new Object();
    private ProcessClaim selectedProcessClaim;
    private ProcessOutcome processOutcome;
    private ProcessOutcome primaryOutcome;
    private ModeSettlement modeSettlement;
    private boolean publicExitClaimed;

    SessionTerminal(SessionOutputMode outputMode, DiagnosticEmitter diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (Objects.requireNonNull(outputMode, "outputMode").raw()) {
            modeSettlement = ModeSettlement.completed(null);
        }
    }

    ProcessClaim claimClose(boolean timedOut) {
        return claimPrimary(timedOut ? SuccessKind.TIMED_OUT : SuccessKind.CLOSED);
    }

    boolean completeNaturalExit(SessionExit result) {
        Objects.requireNonNull(result, "result");
        PublicOutcome publication;
        synchronized (lock) {
            if (processOutcome != null) {
                return false;
            }
            processOutcome = ProcessOutcome.succeeded(result, SuccessKind.NATURAL);
            publication = claimPublicOutcomeLocked();
        }
        publishCanonicalProcessOutcome(processOutcome);
        publishPublicOutcome(publication);
        return true;
    }

    ProcessClaim claimFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (lock) {
            ProcessClaim claim = claimPrimaryLocked(null);
            if (claim != null) {
                claim.failure = failure;
            }
            return claim;
        }
    }

    boolean processPublished() {
        return processCompletion.isDone();
    }

    boolean publicExitCompleted() {
        return publicCompletion.isDone();
    }

    boolean primaryClaimSelected() {
        synchronized (lock) {
            return selectedProcessClaim != null;
        }
    }

    CompletableFuture<SessionExit> publicExit() {
        CompletableFuture<SessionExit> view = new CompletableFuture<>();
        observePublic(outcome -> {
            if (outcome.failure() == null) {
                view.complete(outcome.result());
            } else {
                view.completeExceptionally(outcome.failure());
            }
        });
        return view;
    }

    void observePublic(Consumer<? super PublicOutcome> observer) {
        Objects.requireNonNull(observer, "observer");
        publicCompletion.whenComplete((outcome, impossibleFailure) -> {
            if (impossibleFailure == null) {
                observer.accept(outcome);
            } else {
                throw new IllegalStateException("Public terminal outcome completed unexpectedly", impossibleFailure);
            }
        });
    }

    void observeProcess(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        observe(processCompletion, observer);
    }

    void observePrimary(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        observe(primaryCompletion, observer);
    }

    private static void observe(
            CompletableFuture<ProcessOutcome> completion, BiConsumer<? super SessionExit, ? super Throwable> observer) {
        Objects.requireNonNull(observer, "observer");
        completion.whenComplete((outcome, impossibleFailure) -> {
            if (impossibleFailure == null) {
                observer.accept(outcome.result(), outcome.failure());
            } else {
                observer.accept(null, impossibleFailure);
            }
        });
    }

    void settleMode(ModeSettlement settlement) {
        Objects.requireNonNull(settlement, "settlement");
        PublicOutcome publication;
        synchronized (lock) {
            if (modeSettlement != null) {
                return;
            }
            modeSettlement = settlement;
            publication = claimPublicOutcomeLocked();
        }
        publishPublicOutcome(publication);
    }

    private ProcessClaim claimPrimary(SuccessKind successKind) {
        synchronized (lock) {
            return claimPrimaryLocked(Objects.requireNonNull(successKind, "successKind"));
        }
    }

    private ProcessClaim claimPrimaryLocked(SuccessKind successKind) {
        if (publicExitClaimed || selectedProcessClaim != null) {
            return null;
        }
        selectedProcessClaim = new ProcessClaim(successKind);
        return selectedProcessClaim;
    }

    final class ProcessClaim {

        private final SuccessKind successKind;
        private Throwable failure;
        private boolean completed;

        private ProcessClaim(SuccessKind successKind) {
            this.successKind = successKind;
        }

        void succeed(SessionExit result) {
            complete(Objects.requireNonNull(result, "result"), false);
        }

        void addFailure(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            Throwable secondary = null;
            synchronized (lock) {
                requireActive();
                if (this.failure == null) {
                    this.failure = failure;
                } else {
                    secondary = failure;
                }
            }
            if (secondary != null) {
                BoundedFailureReporter.reportBestEffort(secondary);
            }
        }

        void fail() {
            complete(null, true);
        }

        private void complete(SessionExit success, boolean failureRequested) {
            ProcessOutcome outcome;
            synchronized (lock) {
                requireActive();
                if (failureRequested && failure == null) {
                    throw new IllegalStateException("Failure completion has no recorded failure");
                }
                completed = true;
                outcome = failure == null
                        ? ProcessOutcome.succeeded(
                                Objects.requireNonNull(success, "success"),
                                Objects.requireNonNull(successKind, "successKind"))
                        : ProcessOutcome.failed(failure);
            }
            publishProcessOutcome(outcome);
        }

        private void requireActive() {
            if (selectedProcessClaim != this || completed) {
                throw new IllegalStateException("Process terminal outcome has already been completed");
            }
        }
    }

    private void publishProcessOutcome(ProcessOutcome selectedOutcome) {
        ProcessPublication publication = completePrimary(selectedOutcome);
        if (!primaryCompletion.complete(selectedOutcome)) {
            throw new IllegalStateException("Primary terminal outcome has already been published");
        }
        if (publication.processOutcome() != null) {
            publishCanonicalProcessOutcome(publication.processOutcome());
        }
        publishPublicOutcome(publication.publicOutcome());
    }

    private ProcessPublication completePrimary(ProcessOutcome outcome) {
        synchronized (lock) {
            if (primaryOutcome != null) {
                throw new IllegalStateException("Primary terminal outcome has already been completed");
            }
            primaryOutcome = Objects.requireNonNull(outcome, "outcome");
            ProcessOutcome unpublishedProcess = null;
            if (processOutcome == null) {
                processOutcome = outcome;
                unpublishedProcess = outcome;
            }
            return new ProcessPublication(unpublishedProcess, claimPublicOutcomeLocked());
        }
    }

    private void publishCanonicalProcessOutcome(ProcessOutcome outcome) {
        if (!processCompletion.complete(outcome)) {
            throw new IllegalStateException("Process terminal outcome has already been published");
        }
    }

    private PublicOutcome claimPublicOutcomeLocked() {
        if (publicExitClaimed || processOutcome == null || modeSettlement == null) {
            return null;
        }
        if (selectedProcessClaim != null && primaryOutcome == null) {
            return null;
        }
        publicExitClaimed = true;
        ProcessOutcome selectedOutcome = primaryOutcome != null ? primaryOutcome : processOutcome;
        if (selectedOutcome.failure() != null) {
            return PublicOutcome.failed(selectedOutcome.failure());
        }
        if (primaryOutcome == null
                && modeSettlement.failure() != null
                && modeSettlement.precedence() == FailurePrecedence.MODE_FAILURE) {
            return PublicOutcome.failed(modeSettlement.failure());
        }
        return PublicOutcome.succeeded(selectedOutcome.result(), selectedOutcome.successKind());
    }

    private void publishPublicOutcome(PublicOutcome publication) {
        if (publication == null) {
            return;
        }
        emitTerminalDiagnostics(publication);
        if (!publicCompletion.complete(publication)) {
            throw new IllegalStateException("Public session exit has already been completed");
        }
    }

    private void emitTerminalDiagnostics(PublicOutcome publication) {
        Throwable failure = publication.failure();
        if (failure == null) {
            SessionExit result = publication.result();
            captureDiagnosticFailure(
                            DiagnosticEventType.PROCESS_EXITED, exitAttributes(result.exitCode(), result.timedOut()))
                    .ifPresent(BoundedFailureReporter::reportBestEffort);
            return;
        }
        captureDiagnosticFailure(
                        DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "failure"))
                .ifPresent(BoundedFailureReporter::reportBestEffort);
        captureDiagnosticFailure(DiagnosticEventType.PROCESS_FAILED, DiagnosticEmitter.failureAttributes(failure))
                .ifPresent(BoundedFailureReporter::reportBestEffort);
    }

    private Optional<Throwable> captureDiagnosticFailure(DiagnosticEventType type, Map<String, String> attributes) {
        try {
            diagnostics.emit(type, attributes);
            return Optional.empty();
        } catch (RuntimeException | Error diagnosticFailure) {
            return Optional.of(diagnosticFailure);
        }
    }

    private static Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    record ModeSettlement(Throwable failure, FailurePrecedence precedence) {

        ModeSettlement {
            Objects.requireNonNull(precedence, "precedence");
        }

        static ModeSettlement completed(Throwable failure) {
            return new ModeSettlement(failure, FailurePrecedence.MODE_FAILURE);
        }

        static ModeSettlement processSuccessPreferred(Throwable failure) {
            return new ModeSettlement(Objects.requireNonNull(failure, "failure"), FailurePrecedence.PROCESS_SUCCESS);
        }
    }

    enum FailurePrecedence {
        MODE_FAILURE,
        PROCESS_SUCCESS
    }

    enum SuccessKind {
        NATURAL,
        CLOSED,
        TIMED_OUT
    }

    private record ProcessOutcome(SessionExit result, Throwable failure, SuccessKind successKind) {

        private ProcessOutcome {
            if ((result == null) == (failure == null) || (result == null) != (successKind == null)) {
                throw new IllegalArgumentException("A process outcome must contain either a result or a failure");
            }
        }

        private static ProcessOutcome succeeded(SessionExit result, SuccessKind successKind) {
            return new ProcessOutcome(
                    Objects.requireNonNull(result, "result"), null, Objects.requireNonNull(successKind, "successKind"));
        }

        private static ProcessOutcome failed(Throwable failure) {
            return new ProcessOutcome(null, Objects.requireNonNull(failure, "failure"), null);
        }
    }

    private record ProcessPublication(ProcessOutcome processOutcome, PublicOutcome publicOutcome) {}

    record PublicOutcome(SessionExit result, Throwable failure, SuccessKind successKind) {

        PublicOutcome {
            if ((result == null) == (failure == null) || (result == null) != (successKind == null)) {
                throw new IllegalArgumentException("A public outcome must contain either a result or a failure");
            }
        }

        private static PublicOutcome succeeded(SessionExit result, SuccessKind successKind) {
            return new PublicOutcome(
                    Objects.requireNonNull(result, "result"), null, Objects.requireNonNull(successKind, "successKind"));
        }

        private static PublicOutcome failed(Throwable failure) {
            return new PublicOutcome(null, Objects.requireNonNull(failure, "failure"), null);
        }
    }
}

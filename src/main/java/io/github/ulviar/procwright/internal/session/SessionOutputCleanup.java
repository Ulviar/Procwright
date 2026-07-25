/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.ProcessStreamResource;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Classifies output close failures and publishes one outcome after both streams physically close. */
final class SessionOutputCleanup {

    private final Object lock = new Object();
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    private List<Throwable> inlineFailures;
    private boolean bound;
    private boolean settled;

    void bind(
            ProcessStreamResource<InputStream> stdout,
            ProcessStreamResource<InputStream> stderr,
            SessionOutputOwnership ownership) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(ownership, "ownership");
        synchronized (lock) {
            if (bound) {
                throw new IllegalStateException("Session output cleanup is already bound");
            }
            bound = true;
        }
        CompletableFuture.allOf(stdout.closeCompletion(), stderr.closeCompletion())
                .whenComplete((ignored, impossible) -> settle(stdout.closeResult(), stderr.closeResult(), ownership));
    }

    void inlineFailed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (lock) {
            if (settled) {
                throw new IllegalStateException("Inline output failure arrived after physical cleanup");
            }
            if (inlineFailures == null) {
                inlineFailures = new ArrayList<>(2);
            }
            inlineFailures.add(failure);
        }
    }

    CompletableFuture<Outcome> completion() {
        return completion;
    }

    CompletableFuture<Void> physicalView() {
        CompletableFuture<Void> view = new CompletableFuture<>();
        completion.whenComplete((outcome, impossibleFailure) -> {
            if (impossibleFailure != null) {
                view.completeExceptionally(impossibleFailure);
                return;
            }
            PhysicalClose physicalClose = outcome.physicalClose();
            if (physicalClose instanceof PhysicalClose.Success) {
                view.complete(null);
            } else if (physicalClose instanceof PhysicalClose.OutputOwnerFailure failure) {
                view.completeExceptionally(failure.failure());
            } else if (physicalClose instanceof PhysicalClose.LifecycleFailure failure) {
                view.completeExceptionally(failure.failure());
            } else {
                view.completeExceptionally(new AssertionError("Unknown physical close outcome: " + physicalClose));
            }
        });
        return view;
    }

    void afterSettlement(Runnable publication) {
        Objects.requireNonNull(publication, "publication");
        completion.whenComplete((ignored, impossible) -> {
            try {
                publication.run();
            } catch (Throwable failure) {
                BoundedFailureReporter.reportBestEffort(failure);
            }
        });
    }

    private void settle(Throwable stdoutFailure, Throwable stderrFailure, SessionOutputOwnership ownership) {
        Throwable physicalFailure = prioritize(stdoutFailure, stderrFailure);
        SessionOutputOwnership.CloseResponsibility closeResponsibility = ownership.settleCloseResponsibility();
        PhysicalClose physicalClose = classifyPhysicalClose(physicalFailure, closeResponsibility);
        Outcome outcome;
        synchronized (lock) {
            if (settled) {
                throw new IllegalStateException("Session output cleanup was already settled");
            }
            settled = true;
            outcome = new Outcome(inlineFailures == null ? List.of() : inlineFailures, physicalClose);
        }
        completion.complete(outcome);
    }

    private static PhysicalClose classifyPhysicalClose(
            Throwable failure, SessionOutputOwnership.CloseResponsibility responsibility) {
        if (failure == null) {
            return PhysicalClose.Success.INSTANCE;
        }
        if (responsibility == SessionOutputOwnership.CloseResponsibility.OUTPUT_OWNER) {
            return new PhysicalClose.OutputOwnerFailure(failure);
        }
        return new PhysicalClose.LifecycleFailure(failure, Optional.ofNullable(captureFailureTarget()));
    }

    private static BoundedFailureReporter.FailureTarget captureFailureTarget() {
        try {
            return BoundedFailureReporter.captureFailureTarget();
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    private static Throwable prioritize(Throwable stdoutFailure, Throwable stderrFailure) {
        if (stdoutFailure == null) {
            return stderrFailure;
        }
        if (stderrFailure == null || stdoutFailure == stderrFailure) {
            return stdoutFailure;
        }
        Throwable primary = priority(stderrFailure) > priority(stdoutFailure) ? stderrFailure : stdoutFailure;
        return FailureAggregation.combineWithPrimary(
                primary,
                java.util.List.of(stdoutFailure, stderrFailure),
                "Both process output streams failed to close");
    }

    private static int priority(Throwable failure) {
        if (failure instanceof Error) {
            return 2;
        }
        return failure instanceof RuntimeException ? 1 : 0;
    }

    record Outcome(List<Throwable> inlineFailures, PhysicalClose physicalClose) {

        Outcome {
            inlineFailures = List.copyOf(inlineFailures);
            Objects.requireNonNull(physicalClose, "physicalClose");
        }
    }

    sealed interface PhysicalClose {

        enum Success implements PhysicalClose {
            INSTANCE
        }

        record OutputOwnerFailure(Throwable failure) implements PhysicalClose {

            public OutputOwnerFailure {
                Objects.requireNonNull(failure, "failure");
            }
        }

        record LifecycleFailure(Throwable failure, Optional<BoundedFailureReporter.FailureTarget> reportingTarget)
                implements PhysicalClose {

            public LifecycleFailure {
                Objects.requireNonNull(failure, "failure");
                Objects.requireNonNull(reportingTarget, "reportingTarget");
            }
        }
    }
}

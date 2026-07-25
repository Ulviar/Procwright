/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.FailureAggregation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Selects the already-observed helper failure and reports every other failure best effort. */
final class OutputCloseFailures {

    private final Object lock = new Object();
    private final Reporter reporter;
    private List<ObservedFailure> observed;
    private Throwable terminalPrimary;
    private Throwable fallbackPrimary;
    private boolean finished;

    OutputCloseFailures() {
        this((target, failure) -> BoundedFailureReporter.shared().report(target, failure));
    }

    OutputCloseFailures(Reporter reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    void retainPrimary(Throwable failure) {
        if (failure == null) {
            return;
        }
        List<ObservedFailure> candidates = observe(failure);
        List<ObservedFailure> reports;
        synchronized (lock) {
            if (terminalPrimary == null) {
                terminalPrimary = failure;
            }
            addLocked(candidates);
            reports = finished ? claimReportsLocked() : List.of();
        }
        report(reports);
    }

    void retainFallback(Throwable failure) {
        if (failure == null) {
            return;
        }
        List<ObservedFailure> candidates = observe(failure);
        List<ObservedFailure> reports;
        synchronized (lock) {
            if (fallbackPrimary == null) {
                fallbackPrimary = failure;
            }
            addLocked(candidates);
            reports = finished ? claimReportsLocked() : List.of();
        }
        report(reports);
    }

    void record(Throwable failure) {
        if (failure == null) {
            return;
        }
        List<ObservedFailure> candidates = observe(failure);
        List<ObservedFailure> reports;
        synchronized (lock) {
            addLocked(candidates);
            reports = finished ? claimReportsLocked() : List.of();
        }
        report(reports);
    }

    void finish() {
        List<ObservedFailure> reports;
        synchronized (lock) {
            if (finished) {
                return;
            }
            finished = true;
            reports = claimReportsLocked();
        }
        report(reports);
    }

    private static BoundedFailureReporter.FailureTarget captureFailureTarget() {
        try {
            return BoundedFailureReporter.captureFailureTarget();
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    private static List<ObservedFailure> observe(Throwable failure) {
        BoundedFailureReporter.FailureTarget target = captureFailureTarget();
        return FailureAggregation.sources(failure).stream()
                .map(source -> new ObservedFailure(source, target))
                .toList();
    }

    private void addLocked(List<ObservedFailure> candidates) {
        for (ObservedFailure candidate : candidates) {
            if (observed != null) {
                boolean alreadyObserved = false;
                for (ObservedFailure existing : observed) {
                    if (existing.failure == candidate.failure) {
                        alreadyObserved = true;
                        break;
                    }
                }
                if (alreadyObserved) {
                    continue;
                }
            } else {
                observed = new ArrayList<>(3);
            }
            observed.add(candidate);
        }
    }

    private List<ObservedFailure> claimReportsLocked() {
        if (observed == null) {
            return List.of();
        }
        Throwable selectedPrimary = terminalPrimary != null ? terminalPrimary : fallbackPrimary;
        List<Throwable> representedSources =
                selectedPrimary == null ? List.of() : FailureAggregation.sources(selectedPrimary);
        List<ObservedFailure> reports = null;
        for (ObservedFailure failure : observed) {
            if (!failure.reportClaimed && !containsIdentity(representedSources, failure.failure)) {
                failure.reportClaimed = true;
                if (reports == null) {
                    reports = new ArrayList<>(observed.size());
                }
                reports.add(failure);
            }
        }
        return reports == null ? List.of() : List.copyOf(reports);
    }

    private static boolean containsIdentity(List<Throwable> failures, Throwable candidate) {
        for (Throwable failure : failures) {
            if (failure == candidate) {
                return true;
            }
        }
        return false;
    }

    private void report(List<ObservedFailure> reports) {
        for (ObservedFailure observedFailure : reports) {
            if (observedFailure.reportingTarget == null) {
                continue;
            }
            try {
                reporter.report(observedFailure.reportingTarget, observedFailure.failure);
            } catch (RuntimeException | Error ignored) {
                // Reporting is optional and cannot retain helper cleanup ownership.
            }
        }
    }

    private static final class ObservedFailure {

        private final Throwable failure;
        private final BoundedFailureReporter.FailureTarget reportingTarget;
        private boolean reportClaimed;

        private ObservedFailure(Throwable failure, BoundedFailureReporter.FailureTarget reportingTarget) {
            this.failure = failure;
            this.reportingTarget = reportingTarget;
        }
    }

    @FunctionalInterface
    interface Reporter {

        void report(BoundedFailureReporter.FailureTarget target, Throwable failure);
    }
}

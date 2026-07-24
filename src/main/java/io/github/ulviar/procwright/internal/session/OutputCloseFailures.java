/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Classifies output-close failures for attachment to a terminal failure or bounded late reporting. */
final class OutputCloseFailures {

    private final Object lock = new Object();
    private final Set<Throwable> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Throwable> fallbacks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<CleanupFailure> cleanupFailures = new ArrayList<>(2);
    private final List<Throwable> fallbackFailures = new ArrayList<>();
    private Throwable terminalPrimary;
    private Throwable fallbackPrimary;
    private boolean finished;
    private boolean reportFutureCleanupFailures;

    void retainPrimary(Throwable failure) {
        if (failure == null) {
            return;
        }
        synchronized (lock) {
            if (terminalPrimary == null) {
                terminalPrimary = failure;
                reportFutureCleanupFailures = false;
                for (Throwable fallbackFailure : fallbackFailures) {
                    SuppressionSupport.attach(failure, fallbackFailure);
                }
                for (CleanupFailure cleanupFailure : cleanupFailures) {
                    if (cleanupFailure.publication == Publication.PENDING) {
                        cleanupFailure.publication = Publication.ATTACHED;
                        SuppressionSupport.attachDirect(failure, cleanupFailure.failure);
                    }
                }
            } else if (terminalPrimary != failure) {
                SuppressionSupport.attach(terminalPrimary, failure);
            }
        }
    }

    void retainFallback(Throwable failure) {
        if (failure == null) {
            return;
        }
        synchronized (lock) {
            if (!fallbacks.add(failure)) {
                return;
            }
            fallbackFailures.add(failure);
            if (terminalPrimary != null) {
                SuppressionSupport.attach(terminalPrimary, failure);
            } else if (fallbackPrimary == null) {
                fallbackPrimary = failure;
            } else {
                SuppressionSupport.attach(fallbackPrimary, failure);
            }
        }
    }

    void record(Throwable failure) {
        if (failure == null) {
            return;
        }
        BoundedFailureReporter.FailureTarget failureTarget = BoundedFailureReporter.captureFailureTarget();
        Throwable target;
        boolean reportUncaught;
        CleanupFailure cleanupFailure;
        synchronized (lock) {
            if (!recorded.add(failure)) {
                return;
            }
            cleanupFailure = new CleanupFailure(failure, failureTarget);
            cleanupFailures.add(cleanupFailure);
            if (terminalPrimary != null) {
                cleanupFailure.publication = Publication.ATTACHED;
                target = terminalPrimary;
                reportUncaught = false;
            } else if (reportFutureCleanupFailures) {
                cleanupFailure.publication = Publication.REPORT_CLAIMED;
                target = null;
                reportUncaught = true;
            } else if (finished && fallbackPrimary != null) {
                cleanupFailure.publication = Publication.ATTACHED;
                target = fallbackPrimary;
                reportUncaught = false;
            } else if (finished) {
                cleanupFailure.publication = Publication.REPORT_CLAIMED;
                target = null;
                reportUncaught = true;
            } else {
                target = null;
                reportUncaught = false;
            }
        }
        if (target != null) {
            SuppressionSupport.attachDirect(target, failure);
        }
        if (reportUncaught) {
            BoundedFailureReporter.shared().report(cleanupFailure.failureTarget, failure);
        }
    }

    void finish() {
        Throwable target;
        List<Throwable> attachments = new ArrayList<>(2);
        List<CleanupFailure> reports = new ArrayList<>(2);
        synchronized (lock) {
            if (finished) {
                return;
            }
            finished = true;
            target = terminalPrimary != null ? terminalPrimary : fallbackPrimary;
            reportFutureCleanupFailures = target == null;
            for (CleanupFailure cleanupFailure : cleanupFailures) {
                if (cleanupFailure.publication != Publication.PENDING) {
                    continue;
                }
                if (target == null) {
                    cleanupFailure.publication = Publication.REPORT_CLAIMED;
                    reports.add(cleanupFailure);
                } else {
                    cleanupFailure.publication = Publication.ATTACHED;
                    attachments.add(cleanupFailure.failure);
                }
            }
        }
        attachAll(target, attachments);
        for (CleanupFailure cleanupFailure : reports) {
            BoundedFailureReporter.shared().report(cleanupFailure.failureTarget, cleanupFailure.failure);
        }
    }

    private static void attachAll(Throwable primary, List<Throwable> failures) {
        if (primary == null) {
            if (!failures.isEmpty()) {
                throw new IllegalStateException("Output close failures have no attachment target");
            }
            return;
        }
        for (Throwable failure : failures) {
            SuppressionSupport.attachDirect(primary, failure);
        }
    }

    private static final class CleanupFailure {

        private final Throwable failure;
        private final BoundedFailureReporter.FailureTarget failureTarget;
        private Publication publication = Publication.PENDING;

        private CleanupFailure(Throwable failure, BoundedFailureReporter.FailureTarget failureTarget) {
            this.failure = failure;
            this.failureTarget = failureTarget;
        }
    }

    private enum Publication {
        PENDING,
        ATTACHED,
        REPORT_CLAIMED
    }
}

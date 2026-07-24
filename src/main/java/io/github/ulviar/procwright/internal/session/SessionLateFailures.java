/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Defers cleanup-failure attachment or reporting until the terminal outcome is known. */
final class SessionLateFailures {

    private final Map<Throwable, PendingFailure> pending = new IdentityHashMap<>();
    private final Set<Throwable> decided = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean terminalCompleted;
    private Throwable terminalFailure;

    void record(Throwable cleanupFailure, boolean reportWhenSuccessful) {
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");
        BoundedFailureReporter.FailureTarget failureTarget = BoundedFailureReporter.captureFailureTarget();
        Decision decision;
        synchronized (this) {
            if (decided.contains(cleanupFailure)) {
                return;
            }
            if (!terminalCompleted) {
                pending.compute(
                        cleanupFailure,
                        (ignored, existing) -> existing == null
                                ? new PendingFailure(cleanupFailure, reportWhenSuccessful, failureTarget)
                                : existing.withReportWhenSuccessful(reportWhenSuccessful));
                return;
            }
            decided.add(cleanupFailure);
            decision = new Decision(cleanupFailure, reportWhenSuccessful, failureTarget, terminalFailure);
        }
        publish(decision);
    }

    void terminalCompleted(Throwable failure) {
        List<Decision> decisions;
        synchronized (this) {
            if (terminalCompleted) {
                throw new IllegalStateException("Session terminal outcome was already observed");
            }
            terminalCompleted = true;
            terminalFailure = failure;
            decisions = new ArrayList<>(pending.size());
            pending.forEach((cleanupFailure, pendingFailure) -> {
                decided.add(cleanupFailure);
                decisions.add(new Decision(
                        cleanupFailure,
                        pendingFailure.reportWhenSuccessful(),
                        pendingFailure.failureTarget(),
                        failure));
            });
            pending.clear();
        }
        decisions.forEach(SessionLateFailures::publish);
    }

    private static void publish(Decision decision) {
        if (decision.terminalFailure() != null) {
            SuppressionSupport.attachDirect(decision.terminalFailure(), decision.cleanupFailure());
            return;
        }
        if (!decision.reportWhenSuccessful()) {
            return;
        }
        BoundedFailureReporter.shared().report(decision.failureTarget(), decision.cleanupFailure());
    }

    private record PendingFailure(
            Throwable cleanupFailure,
            boolean reportWhenSuccessful,
            BoundedFailureReporter.FailureTarget failureTarget) {

        private PendingFailure withReportWhenSuccessful(boolean report) {
            return report && !reportWhenSuccessful ? new PendingFailure(cleanupFailure, true, failureTarget) : this;
        }
    }

    private record Decision(
            Throwable cleanupFailure,
            boolean reportWhenSuccessful,
            BoundedFailureReporter.FailureTarget failureTarget,
            Throwable terminalFailure) {}
}

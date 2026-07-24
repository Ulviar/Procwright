/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns the one-way construction decision and routing of failures observed before that decision. */
final class ConstructionLedger {

    private final ArrayDeque<FailureReport> pending = new ArrayDeque<>();
    private Phase phase = Phase.CONSTRUCTING;

    List<FailureReport> commit() {
        requirePhase(Phase.CONSTRUCTING);
        phase = Phase.COMMITTED;
        return drainPending();
    }

    List<FailureReport> fail() {
        requirePhase(Phase.CONSTRUCTING);
        phase = Phase.FAILED;
        return drainPending();
    }

    void record(FailureReport report) {
        if (!constructing()) {
            throw new IllegalStateException("construction failure can only be recorded before resolution");
        }
        pending.addLast(Objects.requireNonNull(report, "report"));
    }

    FailureReport route(FailureReport report) {
        if (constructing()) {
            record(report);
            return null;
        }
        return report;
    }

    boolean constructing() {
        return phase == Phase.CONSTRUCTING;
    }

    boolean failed() {
        return phase == Phase.FAILED;
    }

    private List<FailureReport> drainPending() {
        List<FailureReport> reports = new ArrayList<>(pending);
        pending.clear();
        return List.copyOf(reports);
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("construction phase must be " + expected);
        }
    }

    private enum Phase {
        CONSTRUCTING,
        COMMITTED,
        FAILED
    }
}

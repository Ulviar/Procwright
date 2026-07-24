/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Owns construction resolution, closing state, terminal failure precedence, and the pool drain outcome.
 *
 * <p>Mutable transition methods are serialized by the pool state monitor. Drain publication remains outside that
 * monitor so user continuations cannot run while pool state is locked.
 */
final class PoolTermination {

    private final ConstructionLedger construction = new ConstructionLedger();
    private final WorkerCloseFailureAccumulator failures = new WorkerCloseFailureAccumulator();
    private final Set<Throwable> observedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
    private final PoolDrain drain;
    private boolean closing;
    private boolean drainClaimed;

    PoolTermination(PoolTerminalPublisher publisher) {
        drain = new PoolDrain(Objects.requireNonNull(publisher, "publisher"));
    }

    boolean closing() {
        return closing;
    }

    FailureDisposition beginClosing(Throwable failure) {
        closing = true;
        if (failure == null || !observedFailures.add(failure)) {
            return FailureDisposition.NONE;
        }
        if (drainClaimed) {
            return FailureDisposition.LATE;
        }
        failures.add(failure);
        return FailureDisposition.TERMINAL;
    }

    ConstructionResult finishConstruction() {
        if (closing) {
            return new ConstructionResult(false, List.of(), failures.failure());
        }
        return new ConstructionResult(true, construction.commit(), null);
    }

    List<FailureReport> failConstruction() {
        return construction.fail();
    }

    FailureReport routeLateFailure(FailureReport report) {
        return construction.route(Objects.requireNonNull(report, "report"));
    }

    FailureReport routeWorkerCloseFailure(FailureReport report) {
        FailureReport observed = Objects.requireNonNull(report, "report");
        if (construction.constructing()) {
            construction.record(observed);
            return null;
        }
        return construction.failed() ? observed : null;
    }

    Publication claimDrainIfReady(int liveWorkers) {
        if (liveWorkers < 0) {
            throw new IllegalArgumentException("liveWorkers must not be negative");
        }
        if (!closing || liveWorkers != 0 || !drain.tryClaim()) {
            return null;
        }
        drainClaimed = true;
        return new Publication(failures.failure());
    }

    CompletableFuture<Void> view() {
        return drain.view();
    }

    void publish(Publication publication) {
        PoolTermination.Publication claimed = Objects.requireNonNull(publication, "publication");
        drain.publish(claimed.failure());
    }

    record ConstructionResult(boolean successful, List<FailureReport> reports, Throwable failure) {

        ConstructionResult {
            reports = List.copyOf(reports);
            if (successful && failure != null) {
                throw new IllegalArgumentException("successful construction cannot carry a failure");
            }
        }
    }

    record Publication(Throwable failure) {}

    enum FailureDisposition {
        NONE,
        TERMINAL,
        LATE
    }
}

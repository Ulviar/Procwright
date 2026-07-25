/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Owns construction resolution, closing state, terminal failure precedence, and the pool drain outcome.
 *
 * <p>{@link WorkerPoolState} serializes mutable transition methods with its monitor. Drain publication remains outside
 * that monitor so user continuations cannot run while pool state is locked.
 */
final class PoolTermination {

    private final ArrayDeque<FailureReport> constructionReports = new ArrayDeque<>();
    private final FailureAccumulator failures = new FailureAccumulator();
    private final PoolDrain drain;
    private ConstructionPhase construction = ConstructionPhase.CONSTRUCTING;
    private boolean closing;

    PoolTermination(PoolTerminalPublisher publisher) {
        drain = new PoolDrain(Objects.requireNonNull(publisher, "publisher"));
    }

    boolean closing() {
        return closing;
    }

    FailureDisposition beginClosing(Throwable failure) {
        closing = true;
        if (!failures.add(failure)) {
            return FailureDisposition.NONE;
        }
        if (drain.claimed()) {
            return FailureDisposition.LATE;
        }
        return FailureDisposition.TERMINAL;
    }

    ConstructionResult finishConstruction() {
        requireConstructing();
        if (closing) {
            construction = ConstructionPhase.FAILED;
            return new ConstructionFailed(
                    failures.aggregateErrorFirst("Multiple failures occurred while constructing the pool"),
                    drainConstructionReports());
        }
        construction = ConstructionPhase.COMMITTED;
        return new ConstructionSucceeded(drainConstructionReports());
    }

    List<FailureReport> failConstruction() {
        if (construction == ConstructionPhase.CONSTRUCTING) {
            construction = ConstructionPhase.FAILED;
            return drainConstructionReports();
        }
        if (construction == ConstructionPhase.FAILED) {
            return List.of();
        }
        throw new IllegalStateException("committed construction cannot fail");
    }

    FailureReport routeLateFailure(FailureReport report) {
        FailureReport observed = Objects.requireNonNull(report, "report");
        if (construction == ConstructionPhase.CONSTRUCTING) {
            constructionReports.addLast(observed);
            return null;
        }
        return observed;
    }

    FailureReport routeWorkerCloseFailure(FailureReport report) {
        FailureReport observed = Objects.requireNonNull(report, "report");
        if (construction == ConstructionPhase.CONSTRUCTING) {
            constructionReports.addLast(observed);
            return null;
        }
        return construction == ConstructionPhase.FAILED ? observed : null;
    }

    PoolDrain.Publication claimDrainIfReady(int liveWorkers) {
        if (liveWorkers < 0) {
            throw new IllegalArgumentException("liveWorkers must not be negative");
        }
        if (!closing || liveWorkers != 0) {
            return null;
        }
        return drain.claim(failures.aggregateErrorFirst("Multiple failures occurred while closing the pool"));
    }

    CompletableFuture<Void> view() {
        return drain.view();
    }

    void publish(PoolDrain.Publication publication) {
        Objects.requireNonNull(publication, "publication").publish();
    }

    private List<FailureReport> drainConstructionReports() {
        List<FailureReport> reports = new ArrayList<>(constructionReports);
        constructionReports.clear();
        return List.copyOf(reports);
    }

    private void requireConstructing() {
        if (construction != ConstructionPhase.CONSTRUCTING) {
            throw new IllegalStateException("pool construction is already resolved");
        }
    }

    sealed interface ConstructionResult permits ConstructionSucceeded, ConstructionFailed {}

    record ConstructionSucceeded(List<FailureReport> reports) implements ConstructionResult {

        ConstructionSucceeded {
            reports = List.copyOf(reports);
        }
    }

    record ConstructionFailed(Throwable failure, List<FailureReport> reports) implements ConstructionResult {

        ConstructionFailed {
            reports = List.copyOf(reports);
        }
    }

    enum FailureDisposition {
        NONE,
        TERMINAL,
        LATE
    }

    private enum ConstructionPhase {
        CONSTRUCTING,
        COMMITTED,
        FAILED
    }
}

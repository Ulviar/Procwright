/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns construction resolution, closing state, terminal failure precedence, and the pool drain outcome.
 *
 * <p>{@link WorkerPoolState} serializes mutable transition methods with its monitor. Drain publication remains outside
 * that monitor so user continuations cannot run while pool state is locked.
 */
final class PoolTermination {

    private final ArrayDeque<FailureReport> constructionReports = new ArrayDeque<>();
    private final CompletableFuture<Void> terminal = new CompletableFuture<>();
    private ConstructionPhase construction = ConstructionPhase.CONSTRUCTING;
    private boolean closing;
    private boolean drainClaimed;
    private Throwable terminalFailure;

    boolean closing() {
        return closing;
    }

    FailureDisposition beginClosing(Throwable failure) {
        closing = true;
        if (failure == null || failure == terminalFailure) {
            return FailureDisposition.NONE;
        }
        if (terminalFailure != null || drainClaimed) {
            return FailureDisposition.REPORT;
        }
        terminalFailure = failure;
        return FailureDisposition.TERMINAL;
    }

    ConstructionResult finishConstruction() {
        requireConstructing();
        if (closing) {
            construction = ConstructionPhase.FAILED;
            return new ConstructionFailed(terminalFailure, drainConstructionReports());
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
        if (construction == ConstructionPhase.FAILED) {
            return observed;
        }
        return observed.failure() == terminalFailure ? null : observed;
    }

    Publication claimDrainIfReady(int liveWorkers) {
        if (liveWorkers < 0) {
            throw new IllegalArgumentException("liveWorkers must not be negative");
        }
        if (!closing || liveWorkers != 0 || drainClaimed) {
            return null;
        }
        drainClaimed = true;
        return new Publication(this, terminalFailure);
    }

    CompletableFuture<Void> view() {
        return terminal.copy();
    }

    private void publish(Publication publication) {
        if (publication.owner != this) {
            throw new IllegalArgumentException("pool termination publication belongs to another pool");
        }
        if (publication.failure == null) {
            terminal.complete(null);
        } else {
            terminal.completeExceptionally(publication.failure);
        }
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
        REPORT
    }

    static final class Publication {

        private final PoolTermination owner;
        private final Throwable failure;
        private final AtomicBoolean published = new AtomicBoolean();

        private Publication(PoolTermination owner, Throwable failure) {
            this.owner = owner;
            this.failure = failure;
        }

        Throwable failure() {
            return failure;
        }

        void publish() {
            if (!published.compareAndSet(false, true)) {
                throw new IllegalStateException("pool termination outcome is already published");
            }
            owner.publish(this);
        }
    }

    private enum ConstructionPhase {
        CONSTRUCTING,
        COMMITTED,
        FAILED
    }
}

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
 * <p>{@link WorkerPoolState} serializes mutable transition methods with its monitor. Drain publication remains outside
 * that monitor so user continuations cannot run while pool state is locked.
 */
final class PoolTermination {

    private final ConstructionLedger construction = new ConstructionLedger();
    private final WorkerCloseFailureAccumulator failures = new WorkerCloseFailureAccumulator();
    private final Set<Throwable> observedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
    private final PoolDrain drain;
    private final Publication publication;
    private boolean closing;
    private boolean drainClaimed;

    PoolTermination(PoolTerminalPublisher publisher) {
        drain = new PoolDrain(Objects.requireNonNull(publisher, "publisher"));
        publication = new Publication(drain);
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
        publication.prepare(failures.failure());
        return publication;
    }

    CompletableFuture<Void> view() {
        return drain.view();
    }

    void publish(Publication publication) {
        PoolTermination.Publication claimed = Objects.requireNonNull(publication, "publication");
        if (claimed != this.publication) {
            throw new IllegalArgumentException("terminal publication belongs to another pool");
        }
        claimed.publish();
    }

    record ConstructionResult(boolean successful, List<FailureReport> reports, Throwable failure) {

        ConstructionResult {
            reports = List.copyOf(reports);
            if (successful && failure != null) {
                throw new IllegalArgumentException("successful construction cannot carry a failure");
            }
        }
    }

    static final class Publication {

        private final PoolDrain drain;
        private Throwable failure;
        private boolean prepared;

        private Publication(PoolDrain drain) {
            this.drain = Objects.requireNonNull(drain, "drain");
        }

        Throwable failure() {
            if (!prepared) {
                throw new IllegalStateException("terminal publication is not prepared");
            }
            return failure;
        }

        private void prepare(Throwable selectedFailure) {
            if (prepared) {
                throw new IllegalStateException("terminal publication is already prepared");
            }
            failure = selectedFailure;
            prepared = true;
        }

        private void publish() {
            drain.publish(failure());
        }
    }

    enum FailureDisposition {
        NONE,
        TERMINAL,
        LATE
    }
}

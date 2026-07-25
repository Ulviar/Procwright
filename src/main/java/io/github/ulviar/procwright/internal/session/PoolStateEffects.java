/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Owns the post-monitor effects selected by one pool-state transaction.
 *
 * <p>Closing the effects owner attempts every physical effect and the selected terminal publication before propagating
 * a failure.
 */
final class PoolStateEffects<S> implements AutoCloseable {

    private final WorkerPoolState<S> state;
    private final WorkerRetirementCoordinator<S> retirements;
    private FailureAccumulator failures;
    private ArrayList<PoolWorker<S>> workersToRetire;
    private ArrayList<PoolLifecycleDispatcher.Admission> admissionsToRelease;
    private PoolDrain.Publication publication;
    private boolean closed;

    PoolStateEffects(WorkerPoolState<S> state, WorkerRetirementCoordinator<S> retirements) {
        this.state = Objects.requireNonNull(state, "state");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
    }

    void requireOwner(WorkerPoolState<S> expected) {
        requireOpen();
        if (state != expected) {
            throw new IllegalArgumentException("pool state effects belong to another pool");
        }
    }

    void retire(PoolWorker<S> worker) {
        requireOpen();
        if (workersToRetire == null) {
            workersToRetire = new ArrayList<>();
        }
        workersToRetire.add(Objects.requireNonNull(worker, "worker"));
    }

    void release(PoolLifecycleDispatcher.Admission admission) {
        requireOpen();
        if (admission == null) {
            return;
        }
        if (admissionsToRelease == null) {
            admissionsToRelease = new ArrayList<>();
        }
        admissionsToRelease.add(admission);
    }

    void publish(PoolDrain.Publication selected) {
        requireOpen();
        if (selected == null) {
            return;
        }
        if (publication != null) {
            throw new IllegalStateException("pool state transaction selected more than one terminal publication");
        }
        publication = selected;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeAdmissions();
        if (workersToRetire != null) {
            try {
                retirements.dispatch(workersToRetire);
            } catch (RuntimeException | Error failure) {
                record(failure);
            }
        }
        try {
            state.publish(publication);
        } catch (RuntimeException | Error failure) {
            record(failure);
        }
        throwRecordedFailure();
    }

    private void closeAdmissions() {
        if (admissionsToRelease != null) {
            for (PoolLifecycleDispatcher.Admission admission : admissionsToRelease) {
                try {
                    admission.close();
                } catch (RuntimeException | Error failure) {
                    record(failure);
                }
            }
        }
    }

    private void record(Throwable failure) {
        if (failures == null) {
            failures = new FailureAccumulator();
        }
        failures.add(failure);
    }

    private void throwRecordedFailure() {
        if (failures == null) {
            return;
        }
        Throwable failure = failures.aggregateErrorFirst("Multiple pool state effects failed");
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("pool state effects are already closed");
        }
    }
}

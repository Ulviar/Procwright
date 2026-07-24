/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.SuppressionSupport;
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
    private ArrayList<PoolWorker<S>> workersToRetire;
    private ArrayList<PoolLifecycleDispatcher.Admission> admissionsToRelease;
    private PoolTermination.Publication publication;
    private RuntimeException runtimeFailure;
    private Error fatalFailure;
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

    void publish(PoolTermination.Publication selected) {
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
        if (failure instanceof Error error) {
            if (fatalFailure == null) {
                fatalFailure = error;
            } else {
                suppress(fatalFailure, error);
            }
        } else if (runtimeFailure == null) {
            runtimeFailure = (RuntimeException) failure;
        } else {
            suppress(runtimeFailure, failure);
        }
    }

    private void throwRecordedFailure() {
        if (fatalFailure != null) {
            suppress(fatalFailure, runtimeFailure);
            throw fatalFailure;
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("pool state effects are already closed");
        }
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (secondary == null || primary == secondary) {
            return;
        }
        try {
            SuppressionSupport.attach(primary, secondary);
        } catch (RuntimeException | Error ignored) {
            // Cleanup must continue even when optional failure bookkeeping is unavailable.
        }
    }
}

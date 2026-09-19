/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Owns pool-state preflight, launch, waiting, and failure mapping for one worker startup. */
final class WorkerStartupCoordinator<S> {

    private final WorkerPoolController.FailureFactory failures;
    private final String workerLabel;
    private final PoolState<S> poolState;

    WorkerStartupCoordinator(WorkerPoolController.FailureFactory failures, String workerLabel, PoolState<S> poolState) {
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.poolState = Objects.requireNonNull(poolState, "poolState");
    }

    WorkerStartup.CreatedWorker<S> start(PoolWorker<S> worker, long deadlineNanos) {
        Objects.requireNonNull(worker, "worker");
        PoolWorker.StartupPurpose purpose = worker.startupPurpose();

        WorkerStartup<S> owner = launch(worker, deadlineNanos, purpose);
        return await(owner, worker, deadlineNanos, purpose);
    }

    private WorkerStartup<S> launch(PoolWorker<S> worker, long deadlineNanos, PoolWorker.StartupPurpose purpose) {
        WorkerStartup<S> owner = worker.startup();
        StartupDecision decision;
        try {
            decision = poolState.preflight(worker, deadlineNanos);
        } catch (RuntimeException | Error failure) {
            discardAndRethrow(worker, failure);
            throw new AssertionError("unreachable");
        }
        if (decision == StartupDecision.CLOSED) {
            throw discardFailure(worker, failures.closed("Pool is closed"));
        }
        if (decision == StartupDecision.TIMED_OUT) {
            throw discardFailure(
                    worker,
                    startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed before launch")));
        }
        try {
            owner.start();
        } catch (RuntimeException | Error failure) {
            Throwable terminalFailure = failure;
            try {
                poolState.discardStartingWorker(worker);
            } catch (RuntimeException | Error cleanupFailure) {
                terminalFailure = FailureAggregation.combine(
                        terminalFailure, cleanupFailure, "Worker startup launch and pool cleanup both failed");
            }
            rethrow(terminalFailure);
            throw new AssertionError("unreachable");
        }
        return owner;
    }

    private WorkerStartup.CreatedWorker<S> await(
            WorkerStartup<S> owner, PoolWorker<S> worker, long deadlineNanos, PoolWorker.StartupPurpose purpose) {
        return switch (owner.await(deadlineNanos)) {
            case WorkerStartup.CreatedWorker<S> created -> created;
            case WorkerStartup.Failed<S> failed -> {
                Throwable cause = failed.failure();
                poolState.factoryFailed(worker, cause);
                if (cause instanceof Error error) {
                    throw error;
                }
                throw failures.startupFailed("Could not start " + workerLabel, cause);
            }
            case WorkerStartup.Stopped<S> stopped ->
                throw switch (stopped.reason()) {
                    case CLOSED -> failures.closed("Pool is closed");
                    case TIMED_OUT -> startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed"));
                    case INTERRUPTED ->
                        failures.acquireInterrupted(
                                "Interrupted while starting " + workerLabel,
                                new InterruptedException("worker startup was interrupted"));
                };
        };
    }

    private RuntimeException startupTimeout(PoolWorker.StartupPurpose purpose, TimeoutException cause) {
        if (purpose == PoolWorker.StartupPurpose.WARMUP) {
            return failures.startupFailed("Timed out warming " + workerLabel, cause);
        }
        return failures.acquireTimeout("Timed out starting " + workerLabel);
    }

    private RuntimeException discardFailure(PoolWorker<S> worker, RuntimeException primary) {
        try {
            poolState.discardStartingWorker(worker);
            return primary;
        } catch (RuntimeException | Error cleanupFailure) {
            Throwable combined = FailureAggregation.combine(
                    primary, cleanupFailure, "Worker startup failure and pool discard both failed");
            if (combined instanceof Error error) {
                throw error;
            }
            return (RuntimeException) combined;
        }
    }

    private void discardAndRethrow(PoolWorker<S> worker, Throwable primary) {
        Throwable combined = primary;
        try {
            poolState.discardStartingWorker(worker);
        } catch (RuntimeException | Error cleanupFailure) {
            combined = FailureAggregation.combine(
                    primary, cleanupFailure, "Worker startup failure and pool discard both failed");
        }
        rethrow(combined);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Worker startup produced a checked failure", failure);
    }

    interface PoolState<S> {

        StartupDecision preflight(PoolWorker<S> worker, long deadlineNanos);

        void factoryFailed(PoolWorker<S> worker, Throwable failure);

        void discardStartingWorker(PoolWorker<S> worker);
    }

    enum StartupDecision {
        RUN,
        CLOSED,
        TIMED_OUT
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Owns admission, launch, waiting, and failure mapping for one worker startup. */
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

        BoundedTaskPermit startupPermit = acquireStartupPermit(worker, deadlineNanos, purpose);
        WorkerStartup<S> owner = launch(worker, startupPermit, deadlineNanos, purpose);
        return await(owner, worker, deadlineNanos, purpose);
    }

    private BoundedTaskPermit acquireStartupPermit(
            PoolWorker<S> worker, long deadlineNanos, PoolWorker.StartupPurpose purpose) {
        try {
            return worker.startup().acquirePermit(BoundedTaskLimits.WORKER_STARTUPS, deadlineNanos);
        } catch (BoundedTaskRunner.TaskCancelledException failure) {
            throw discardFailure(worker, failures.closed("Pool is closed"));
        } catch (TimeoutException failure) {
            throw discardFailure(worker, preLaunchTimeout(worker, purpose, failure));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw discardFailure(worker, preLaunchInterruption(worker, purpose, failure));
        }
    }

    private WorkerStartup<S> launch(
            PoolWorker<S> worker,
            BoundedTaskPermit startupPermit,
            long deadlineNanos,
            PoolWorker.StartupPurpose purpose) {
        boolean transferred = false;
        WorkerStartup<S> owner = worker.startup();
        try {
            StartupClaim claim;
            try {
                claim = poolState.claimLaunch(worker, deadlineNanos);
            } catch (RuntimeException | Error failure) {
                discardAndRethrow(worker, failure);
                throw new AssertionError("unreachable");
            }
            if (claim == StartupClaim.CLOSED) {
                throw discardFailure(worker, failures.closed("Pool is closed"));
            }
            if (claim == StartupClaim.TIMED_OUT) {
                throw discardFailure(
                        worker,
                        startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed before launch")));
            }
            transferred = true;
            try {
                owner.start(startupPermit);
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
        } finally {
            if (!transferred) {
                startupPermit.close();
            }
        }
    }

    private WorkerStartup.CreatedWorker<S> await(
            WorkerStartup<S> owner, PoolWorker<S> worker, long deadlineNanos, PoolWorker.StartupPurpose purpose) {
        try {
            return owner.await(deadlineNanos);
        } catch (TimeoutException failure) {
            WorkerStartup.TerminalDecision decision = owner.terminalDecision();
            if (decision == WorkerStartup.TerminalDecision.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            if (decision != WorkerStartup.TerminalDecision.TIMED_OUT) {
                throw new IllegalStateException("worker startup timeout has incompatible decision: " + decision);
            }
            throw startupTimeout(purpose, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            WorkerStartup.TerminalDecision decision = owner.terminalDecision();
            if (decision == WorkerStartup.TerminalDecision.CLOSED) {
                throw failures.closed("Pool is closed");
            }
            if (decision == WorkerStartup.TerminalDecision.TIMED_OUT) {
                throw startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed"));
            }
            if (decision != WorkerStartup.TerminalDecision.INTERRUPTED) {
                throw new IllegalStateException("worker startup interruption has incompatible decision: " + decision);
            }
            throw failures.acquireInterrupted("Interrupted while starting " + workerLabel, failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            boolean closed = poolState.factoryFailed(worker, cause);
            if (cause instanceof Error error) {
                throw error;
            }
            if (closed) {
                RuntimeException closedFailure = failures.closed("Pool is closed");
                throw (RuntimeException) failures.expose(FailureAggregation.combine(
                        closedFailure, cause, "Pool closed while its worker factory failed"));
            }
            throw failures.startupFailed("Could not start " + workerLabel, cause);
        }
    }

    private RuntimeException preLaunchTimeout(
            PoolWorker<S> worker, PoolWorker.StartupPurpose purpose, TimeoutException failure) {
        WorkerStartup.TerminalDecision decision = worker.startup().signalTimeout();
        if (decision == WorkerStartup.TerminalDecision.CLOSED) {
            return failures.closed("Pool is closed");
        }
        if (decision != WorkerStartup.TerminalDecision.TIMED_OUT) {
            return new IllegalStateException("queued worker timeout has incompatible terminal decision: " + decision);
        }
        return startupTimeout(purpose, failure);
    }

    private RuntimeException preLaunchInterruption(
            PoolWorker<S> worker, PoolWorker.StartupPurpose purpose, InterruptedException failure) {
        WorkerStartup.TerminalDecision decision = worker.startup().signalInterrupted();
        return switch (decision) {
            case CLOSED -> failures.closed("Pool is closed");
            case TIMED_OUT ->
                startupTimeout(purpose, new TimeoutException("worker startup deadline elapsed before launch"));
            case INTERRUPTED -> failures.acquireInterrupted("Interrupted while starting " + workerLabel, failure);
            case FACTORY_COMPLETED, UNDECIDED ->
                new IllegalStateException("pre-launch interruption has incompatible terminal decision: " + decision);
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

        StartupClaim claimLaunch(PoolWorker<S> worker, long deadlineNanos);

        boolean factoryFailed(PoolWorker<S> worker, Throwable failure);

        void discardStartingWorker(PoolWorker<S> worker);
    }

    enum StartupClaim {
        RUN,
        CLOSED,
        TIMED_OUT
    }
}

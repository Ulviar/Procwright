/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Owns warmup, commit, and bounded rollback of one worker-pool construction. */
final class WorkerPoolConstruction<S> {

    private final WorkerPoolState<S> state;
    private final Duration closeTimeout;
    private final WorkerPoolController.FailureFactory failures;
    private final Supplier<List<FailureReport>> rollbackTransition;
    private final Runnable warmup;
    private final Runnable startReplenishment;

    WorkerPoolConstruction(
            WorkerPoolState<S> state,
            Duration closeTimeout,
            WorkerPoolController.FailureFactory failures,
            Supplier<List<FailureReport>> rollbackTransition,
            Runnable warmup,
            Runnable startReplenishment) {
        this.state = Objects.requireNonNull(state, "state");
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.rollbackTransition = Objects.requireNonNull(rollbackTransition, "rollbackTransition");
        this.warmup = Objects.requireNonNull(warmup, "warmup");
        this.startReplenishment = Objects.requireNonNull(startReplenishment, "startReplenishment");
    }

    List<FailureReport> commit() {
        try {
            warmup.run();
            startReplenishment.run();
            return expose(state.finishConstruction());
        } catch (RuntimeException | Error failure) {
            rethrow(failures.expose(rollback(failure)));
            throw new AssertionError("unreachable");
        }
    }

    private List<FailureReport> expose(PoolTermination.ConstructionResult result) {
        if (result instanceof PoolTermination.ConstructionSucceeded success) {
            return success.reports();
        }
        PoolTermination.ConstructionFailed failed = (PoolTermination.ConstructionFailed) result;
        FailureAccumulator constructionFailures = new FailureAccumulator();
        constructionFailures.add(failed.failure());
        failed.reports().forEach(report -> constructionFailures.add(report.failure()));
        Throwable aggregate =
                constructionFailures.aggregateErrorFirst("Multiple failures occurred while constructing the pool");
        rethrow(
                aggregate instanceof Error
                        ? aggregate
                        : failures.retirementFailed("Pool failed during construction", aggregate));
        throw new AssertionError("unreachable");
    }

    private Throwable rollback(Throwable primary) {
        List<FailureReport> completedFailures = List.of();
        Throwable transitionFailure = null;
        try {
            completedFailures = rollbackTransition.get();
        } catch (RuntimeException | Error cleanupFailure) {
            transitionFailure = cleanupFailure;
        }
        return awaitRollback(primary, completedFailures, transitionFailure);
    }

    private Throwable awaitRollback(
            Throwable primary, List<FailureReport> completedFailures, Throwable transitionFailure) {
        FailureAccumulator cleanupFailures = new FailureAccumulator();
        cleanupFailures.add(transitionFailure);
        completedFailures.forEach(report -> cleanupFailures.add(report.failure()));
        long deadlineNanos = io.github.ulviar.procwright.internal.DurationSupport.deadlineFromNow(closeTimeout);
        boolean restoreInterrupt = false;
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("pool construction cleanup deadline elapsed");
            }
            state.terminationView().get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            cleanupFailures.add(failure);
        } catch (InterruptedException failure) {
            restoreInterrupt = true;
            cleanupFailures.add(failure);
        } catch (ExecutionException failure) {
            cleanupFailures.add(PoolFailurePublisher.unwrap(failure.getCause()));
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
        Throwable cleanupFailure =
                cleanupFailures.aggregateErrorFirst("Multiple failures occurred while cleaning up pool construction");
        if (cleanupFailure == null || cleanupFailure == primary) {
            return primary;
        }
        Throwable exposedCleanup = cleanupFailure instanceof Error
                ? cleanupFailure
                : failures.retirementFailed("Pool construction cleanup did not complete cleanly", cleanupFailure);
        return combineErrorFirst(primary, exposedCleanup, "Pool construction and cleanup both failed");
    }

    private static Throwable combineErrorFirst(Throwable first, Throwable second, String message) {
        FailureAccumulator failures = new FailureAccumulator();
        failures.add(first);
        failures.add(second);
        return failures.aggregateErrorFirst(message);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Pool construction produced a checked failure", failure);
    }
}

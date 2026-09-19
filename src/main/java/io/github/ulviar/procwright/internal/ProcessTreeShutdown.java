/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/** Runs one graceful-to-forceful or force-only process-tree shutdown state machine. */
final class ProcessTreeShutdown {

    private static final ProcessTreeScanner PROCESS_TREE_SCANNER = ProcessTreeScanner.shared();
    private static final DestroyFallbackDispatcher DEFAULT_DESTROY_FALLBACK = BoundedDestroyDispatcher::dispatch;
    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final Process process;
    private final KnownDescendants knownDescendants;
    private final ShutdownFailureLedger failures = new ShutdownFailureLedger();
    private final ShutdownTreeState tree;
    private final ProcessShutdownSignals signals;

    private ProcessTreeShutdown(
            Process process, KnownDescendants knownDescendants, DestroyFallbackDispatcher destroyFallback) {
        this.process = Objects.requireNonNull(process, "process");
        this.knownDescendants = Objects.requireNonNull(knownDescendants, "knownDescendants");
        tree = new ShutdownTreeState(process, failures);
        signals = new ProcessShutdownSignals(
                process, tree, failures, Objects.requireNonNull(destroyFallback, "destroyFallback"));
    }

    static OptionalInt stop(Process process, ShutdownPolicy shutdownPolicy) {
        return stop(process, KnownDescendants.empty(), shutdownPolicy);
    }

    static OptionalInt stop(Process process, KnownDescendants knownDescendants, ShutdownPolicy shutdownPolicy) {
        return stop(process, knownDescendants, shutdownPolicy, DEFAULT_DESTROY_FALLBACK);
    }

    static OptionalInt stop(
            Process process,
            KnownDescendants knownDescendants,
            ShutdownPolicy shutdownPolicy,
            DestroyFallbackDispatcher destroyFallback) {
        return new ProcessTreeShutdown(process, knownDescendants, destroyFallback)
                .stop(Objects.requireNonNull(shutdownPolicy, "shutdownPolicy"));
    }

    static void forceStop(Process process, Duration timeout) {
        forceStop(process, KnownDescendants.empty(), timeout);
    }

    static void forceStop(Process process, KnownDescendants knownDescendants, Duration timeout) {
        forceStop(process, knownDescendants, timeout, DEFAULT_DESTROY_FALLBACK);
    }

    static void forceStop(
            Process process,
            KnownDescendants knownDescendants,
            Duration timeout,
            DestroyFallbackDispatcher destroyFallback) {
        new ProcessTreeShutdown(process, knownDescendants, destroyFallback)
                .forceStop(Objects.requireNonNull(timeout, "timeout"));
    }

    private OptionalInt stop(ShutdownPolicy shutdownPolicy) {
        long gracefulOperationDeadline =
                DurationSupport.deadlineFromNow(operationPhaseBudget(shutdownPolicy.interruptGrace()));
        failures.interruptionBoundary();
        try {
            tree.initialize(knownDescendants, remainingBudget(gracefulOperationDeadline));
            failures.interruptionBoundary();
            boolean rootAlive = mayStillBeAlive();
            failures.interruptionBoundary();
            if (rootAlive) {
                signals.destroyRoot(ShutdownPhase.GRACEFUL);
            }
            failures.interruptionBoundary();
            signals.destroyDescendants(ShutdownPhase.GRACEFUL);
            failures.interruptionBoundary();
            WaitPhase gracefulWait = WaitPhase.afterSignals(ShutdownPhase.GRACEFUL, shutdownPolicy.interruptGrace());
            boolean exited = !failures.wasInterrupted() && waitForTree(gracefulWait);
            failures.interruptionBoundary();
            if (exited && !failures.hasFailure()) {
                OptionalInt exitCode = exitCode();
                failures.interruptionBoundary();
                if (!failures.hasFailure()) {
                    return exitCode;
                }
            }

            long forcefulOperationDeadline =
                    DurationSupport.deadlineFromNow(operationPhaseBudget(shutdownPolicy.killGrace()));
            tree.startForcePhase(remainingBudget(forcefulOperationDeadline));
            failures.interruptionBoundary();
            destroyTreeForcibly();
            failures.interruptionBoundary();
            WaitPhase forcefulWait = WaitPhase.afterSignals(ShutdownPhase.FORCEFUL, shutdownPolicy.killGrace());
            exited = waitForTree(forcefulWait);
            failures.interruptionBoundary();
            if (!exited) {
                failures.record(new CommandExecutionException("Command did not exit after forceful termination"));
            }
            OptionalInt exitCode = exited ? exitCode() : OptionalInt.empty();
            failures.rethrowIfPresent();
            return exitCode;
        } finally {
            failures.restoreInterrupt();
        }
    }

    private void forceStop(Duration timeout) {
        long operationDeadline = DurationSupport.deadlineFromNow(operationPhaseBudget(timeout));
        failures.interruptionBoundary();
        try {
            tree.initialize(knownDescendants, remainingBudget(operationDeadline));
            failures.interruptionBoundary();
            boolean rootAlive = mayStillBeAlive();
            failures.interruptionBoundary();
            if (!rootAlive && tree.hasNoDescendantsAndCompleteDiscovery() && !failures.hasFailure()) {
                failures.rethrowIfPresent();
                return;
            }
            destroyTreeForcibly();
            failures.interruptionBoundary();
            if (!waitForTree(WaitPhase.withinOperation(ShutdownPhase.FORCEFUL, timeout, operationDeadline))) {
                failures.record(new CommandExecutionException("Command did not exit during forceful cleanup"));
            }
            failures.rethrowIfPresent();
        } finally {
            failures.restoreInterrupt();
        }
    }

    private void destroyTreeForcibly() {
        signals.destroyDescendants(ShutdownPhase.FORCEFUL);
        failures.interruptionBoundary();
        boolean rootAlive = mayStillBeAlive();
        failures.interruptionBoundary();
        if (rootAlive) {
            signals.destroyRoot(ShutdownPhase.FORCEFUL);
        }
    }

    private boolean waitForTree(WaitPhase wait) {
        while (true) {
            failures.interruptionBoundary();
            if (failures.wasInterrupted() && wait.phase() == ShutdownPhase.GRACEFUL) {
                return false;
            }
            Duration discoveryBudget = pollingDiscoveryBudget(wait);
            if (!discoveryBudget.isZero()) {
                tree.discoverPending(discoveryBudget);
            }
            failures.interruptionBoundary();
            if (failures.wasInterrupted() && wait.phase() == ShutdownPhase.GRACEFUL) {
                return false;
            }
            ProcessLiveness.Observation rootObservation = observeRootExit();
            boolean rootExited = rootObservation == ProcessLiveness.Observation.EXITED;
            failures.interruptionBoundary();
            // A shutdown hook may still be inside ProcessBuilder.start when its child first becomes visible. Retain
            // that handle while the root is alive; signalling it in the spawn window can make the hook's start fail.
            if (wait.phase() == ShutdownPhase.FORCEFUL || rootExited) {
                signals.signalPendingDescendants(wait.phase());
            }
            failures.interruptionBoundary();
            ShutdownTreeState.DescendantState descendantState = tree.observeDescendants();
            boolean descendantsExited = descendantState == ShutdownTreeState.DescendantState.EXITED;
            failures.interruptionBoundary();
            if (failures.wasInterrupted() && wait.phase() == ShutdownPhase.GRACEFUL) {
                return false;
            }
            if (rootExited && descendantsExited) {
                // Close the observable race between the liveness check and returning success. Descendants
                // reparented before any refresh remain outside ProcessHandle's guarantees.
                discoveryBudget = stabilizationDiscoveryBudget(wait);
                boolean discovered = false;
                if (wait.requiresBudgetedRefresh()) {
                    if (discoveryBudget.isZero()) {
                        return false;
                    }
                    discovered = tree.discoverPending(discoveryBudget);
                }
                failures.interruptionBoundary();
                if (failures.wasInterrupted() && wait.phase() == ShutdownPhase.GRACEFUL) {
                    return false;
                }
                signals.signalPendingDescendants(wait.phase());
                failures.interruptionBoundary();
                rootObservation = observeRootExit();
                rootExited = rootObservation == ProcessLiveness.Observation.EXITED;
                failures.interruptionBoundary();
                descendantState = tree.observeDescendants();
                descendantsExited = descendantState == ShutdownTreeState.DescendantState.EXITED;
                failures.interruptionBoundary();
                if (failures.wasInterrupted() && wait.phase() == ShutdownPhase.GRACEFUL) {
                    return false;
                }
                if (wait.requiresBudgetedRefresh() && wait.waitDeadline() - System.nanoTime() <= 0) {
                    return false;
                }
                if (!discovered && rootExited && descendantsExited) {
                    return true;
                }
                if (wait.waitDeadline() - System.nanoTime() <= 0) {
                    return false;
                }
                continue;
            }
            long remainingNanos = wait.waitDeadline() - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(POLL_INTERVAL_NANOS, remainingNanos));
            } catch (InterruptedException interruption) {
                failures.interrupted(interruption);
                discoveryBudget = pollingDiscoveryBudget(wait);
                if (!discoveryBudget.isZero()) {
                    tree.discoverForForce(discoveryBudget);
                }
                if (wait.phase() == ShutdownPhase.GRACEFUL) {
                    return false;
                }
                destroyTreeForcibly();
            }
        }
    }

    private boolean mayStillBeAlive() {
        try {
            ProcessLiveness.Observation observation = ProcessLiveness.observe(process);
            return observation != ProcessLiveness.Observation.EXITED;
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            return true;
        }
    }

    private OptionalInt exitCode() {
        try {
            return OptionalInt.of(process.exitValue());
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            return OptionalInt.empty();
        }
    }

    private ProcessLiveness.Observation observeRootExit() {
        ProcessLiveness.ExitObservation observation = ProcessLiveness.observeExitForCleanup(process);
        failures.recordObserved(observation.events());
        return observation.state();
    }

    private static Duration remainingBudget(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private static Duration operationPhaseBudget(Duration waitBudget) {
        return waitBudget.isZero() ? PROCESS_TREE_SCANNER.scanTimeout() : waitBudget;
    }

    private static Duration stabilizationDiscoveryBudget(WaitPhase wait) {
        return wait.requiresBudgetedRefresh() ? remainingBudget(wait.waitDeadline()) : Duration.ZERO;
    }

    private static Duration pollingDiscoveryBudget(WaitPhase wait) {
        Duration remaining = stabilizationDiscoveryBudget(wait);
        return remaining.toNanos() >= POLL_INTERVAL_NANOS ? remaining : Duration.ZERO;
    }

    private record WaitPhase(ShutdownPhase phase, long waitDeadline, boolean requiresBudgetedRefresh) {

        private static WaitPhase afterSignals(ShutdownPhase phase, Duration timeout) {
            return create(phase, timeout, DurationSupport.deadlineFromNow(timeout));
        }

        private static WaitPhase withinOperation(ShutdownPhase phase, Duration timeout, long operationDeadline) {
            return create(phase, timeout, operationDeadline);
        }

        private static WaitPhase create(ShutdownPhase phase, Duration timeout, long positiveWaitDeadline) {
            boolean budgeted = !timeout.isZero();
            long waitDeadline = budgeted ? positiveWaitDeadline : DurationSupport.deadlineFromNow(Duration.ZERO);
            return new WaitPhase(phase, waitDeadline, budgeted);
        }
    }
}

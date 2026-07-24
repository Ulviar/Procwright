/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ProcessLifecycle {

    private static final ProcessTreeScanner PROCESS_TREE_SCANNER = ProcessTreeScanner.shared();
    private static final DestroyFallbackDispatcher DEFAULT_DESTROY_FALLBACK = BoundedDestroyDispatcher::dispatch;

    private ProcessLifecycle() {}

    /**
     * Waits for process completion while periodically snapshotting live descendants.
     *
     * <p>Descendants of an exited process are no longer discoverable through {@link Process#descendants()} because the
     * operating system reparents them, so cleanup paths that may run after exit need a snapshot taken while the
     * process was still alive.
     *
     * <p>A {@link Duration#ZERO} timeout disables the deadline: the wait continues until the process exits on its
     * own. {@link ProcessExitWaiter} owns this wait-specific interpretation of a zero timeout.
     *
     * @param process watched process
     * @param timeout maximum wait, or {@link Duration#ZERO} to wait indefinitely
     * @param descendants receives live descendants observed while the process was alive; exited handles are
     *     pruned on later polls
     * @return whether the process exited within the timeout
     * @throws InterruptedException when the waiting thread is interrupted; the caller still owns process shutdown
     */
    public static boolean waitFor(Process process, Duration timeout, LiveDescendantSnapshot descendants)
            throws InterruptedException {
        return ProcessExitWaiter.waitFor(process, timeout, descendants);
    }

    static boolean waitFor(
            Process process, Duration timeout, LiveDescendantSnapshot descendants, ProcessExitWaiter.PollClock clock)
            throws InterruptedException {
        return ProcessExitWaiter.waitFor(process, timeout, descendants, clock);
    }

    public static OptionalInt stop(Process process, ShutdownPolicy shutdownPolicy) {
        return stop(process, Set.of(), shutdownPolicy);
    }

    public static OptionalInt stop(
            Process process, Set<ProcessHandle> knownDescendants, ShutdownPolicy shutdownPolicy) {
        return stop(process, knownDescendants, shutdownPolicy, DEFAULT_DESTROY_FALLBACK);
    }

    static OptionalInt stop(
            Process process,
            Set<ProcessHandle> knownDescendants,
            ShutdownPolicy shutdownPolicy,
            DestroyFallbackDispatcher destroyFallback) {
        return stopTree(process, knownDescendants, shutdownPolicy, destroyFallback);
    }

    private static OptionalInt stopTree(
            Process process,
            Set<ProcessHandle> knownDescendants,
            ShutdownPolicy shutdownPolicy,
            DestroyFallbackDispatcher destroyFallback) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(knownDescendants, "knownDescendants");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        CleanupFailures failures = new CleanupFailures(destroyFallback);
        DescendantScanState scans = new DescendantScanState();
        long gracefulOperationDeadline =
                DurationSupport.deadlineFromNow(operationPhaseBudget(shutdownPolicy.interruptGrace()));
        failures.interruptionBoundary();
        try {
            Set<ProcessHandle> descendants = cleanupDescendants(
                    process, knownDescendants, failures, scans, remainingBudget(gracefulOperationDeadline));
            failures.interruptionBoundary();
            boolean rootAlive = mayStillBeAlive(process, failures, gracefulOperationDeadline);
            failures.interruptionBoundary();
            if (rootAlive) {
                destroyRoot(process, false, failures, gracefulOperationDeadline);
            }
            failures.interruptionBoundary();
            destroyDescendants(descendants, false, failures, gracefulOperationDeadline);
            failures.interruptionBoundary();
            long gracefulWaitDeadline = DurationSupport.deadlineFromNow(shutdownPolicy.interruptGrace());
            long gracefulProviderDeadline =
                    shutdownPolicy.interruptGrace().isZero() ? gracefulOperationDeadline : gracefulWaitDeadline;
            boolean exited = !failures.wasInterrupted()
                    && waitForTree(
                            process,
                            descendants,
                            gracefulWaitDeadline,
                            gracefulProviderDeadline,
                            false,
                            failures,
                            scans);
            failures.interruptionBoundary();
            if (exited && !failures.hasFailure()) {
                OptionalInt exitCode = exitCode(process, failures, gracefulOperationDeadline);
                failures.interruptionBoundary();
                if (!failures.hasFailure()) {
                    return exitCode;
                }
            }

            long forcefulOperationDeadline =
                    DurationSupport.deadlineFromNow(operationPhaseBudget(shutdownPolicy.killGrace()));
            addBounded(
                    descendants,
                    newDescendants(process, descendants, failures, scans, remainingBudget(forcefulOperationDeadline)));
            failures.interruptionBoundary();
            destroyTreeForcibly(process, descendants, failures, forcefulOperationDeadline);
            failures.interruptionBoundary();
            long forcefulWaitDeadline = DurationSupport.deadlineFromNow(shutdownPolicy.killGrace());
            long forcefulProviderDeadline =
                    shutdownPolicy.killGrace().isZero() ? forcefulOperationDeadline : forcefulWaitDeadline;
            exited = waitForTree(
                    process, descendants, forcefulWaitDeadline, forcefulProviderDeadline, true, failures, scans);
            failures.interruptionBoundary();
            if (!exited) {
                failures.record(new CommandExecutionException("Command did not exit after forceful termination"));
            }
            OptionalInt exitCode =
                    exited ? exitCode(process, failures, forcefulOperationDeadline) : OptionalInt.empty();
            failures.rethrowIfPresent();
            return exitCode;
        } finally {
            failures.restoreInterrupt();
        }
    }

    public static void forceStop(Process process, Duration timeout) {
        forceStop(process, Set.of(), timeout);
    }

    /**
     * Forcefully stops the process tree, including known descendants captured while the process was still alive.
     *
     * <p>The known-descendants snapshot covers descendants that survive their parent: once the launched process has
     * exited they are reparented and invisible to {@link Process#descendants()}, yet they can keep inherited output
     * pipes open.
     *
     * @param process launched process
     * @param knownDescendants descendants snapshot taken while the process was alive
     * @param timeout maximum cleanup wait
     */
    public static void forceStop(Process process, Set<ProcessHandle> knownDescendants, Duration timeout) {
        forceStop(process, knownDescendants, timeout, DEFAULT_DESTROY_FALLBACK);
    }

    static void forceStop(
            Process process,
            Set<ProcessHandle> knownDescendants,
            Duration timeout,
            DestroyFallbackDispatcher destroyFallback) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(knownDescendants, "knownDescendants");
        Objects.requireNonNull(timeout, "timeout");
        CleanupFailures failures = new CleanupFailures(destroyFallback);
        DescendantScanState scans = new DescendantScanState();
        long operationDeadline = DurationSupport.deadlineFromNow(operationPhaseBudget(timeout));
        long waitDeadline = timeout.isZero() ? DurationSupport.deadlineFromNow(Duration.ZERO) : operationDeadline;
        failures.interruptionBoundary();
        try {
            Set<ProcessHandle> descendants =
                    cleanupDescendants(process, knownDescendants, failures, scans, remainingBudget(operationDeadline));
            failures.interruptionBoundary();
            boolean rootAlive = mayStillBeAlive(process, failures, operationDeadline);
            failures.interruptionBoundary();
            if (!rootAlive && descendants.isEmpty() && !failures.hasFailure()) {
                failures.rethrowIfPresent();
                return;
            }
            destroyTreeForcibly(process, descendants, failures, operationDeadline);
            failures.interruptionBoundary();
            long providerDeadline = timeout.isZero() ? operationDeadline : waitDeadline;
            if (!waitForTree(process, descendants, waitDeadline, providerDeadline, true, failures, scans)) {
                failures.record(new CommandExecutionException("Command did not exit during forceful cleanup"));
            }
            failures.rethrowIfPresent();
        } finally {
            failures.restoreInterrupt();
        }
    }

    private static void addBounded(Set<ProcessHandle> target, Iterable<ProcessHandle> candidates) {
        for (ProcessHandle candidate : candidates) {
            if (target.size() == PROCESS_TREE_SCANNER.descendantLimit()) {
                return;
            }
            target.add(candidate);
        }
    }

    private static Set<ProcessHandle> cleanupDescendants(
            Process process,
            Set<ProcessHandle> knownDescendants,
            CleanupFailures failures,
            DescendantScanState scans,
            Duration budget) {
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        failures.attempt(() -> addBounded(descendants, knownDescendants));
        addBounded(descendants, scans.discover(process, descendants, failures, budget));
        failures.interruptionBoundary();
        return descendants;
    }

    private static boolean mayStillBeAlive(ProcessHandle handle, CleanupFailures failures, long deadline) {
        try {
            return ProcessLiveness.observe(handle, deadline) != ProcessLiveness.Observation.EXITED;
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
            return true;
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            return true;
        }
    }

    private static boolean mayStillBeAlive(Process process, CleanupFailures failures, long deadline) {
        try {
            return ProcessLiveness.observe(process, deadline) != ProcessLiveness.Observation.EXITED;
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
            return true;
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            return true;
        }
    }

    private static OptionalInt exitCode(Process process, CleanupFailures failures, long deadline) {
        try {
            return OptionalInt.of(
                    process instanceof GuardedProcess guarded
                            ? guarded.exitValueWithin(operationBudget(deadline))
                            : process.exitValue());
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
            return OptionalInt.empty();
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            return OptionalInt.empty();
        }
    }

    private static void destroyDescendants(
            Set<ProcessHandle> descendants, boolean forceful, CleanupFailures failures, long deadline) {
        for (ProcessHandle descendant : reverse(descendants)) {
            destroy(descendant, forceful, failures, deadline);
            failures.interruptionBoundary();
        }
    }

    private static void destroyTreeForcibly(
            Process process, Set<ProcessHandle> descendants, CleanupFailures failures, long deadline) {
        destroyDescendants(descendants, true, failures, deadline);
        failures.interruptionBoundary();
        boolean rootAlive = mayStillBeAlive(process, failures, deadline);
        failures.interruptionBoundary();
        if (rootAlive) {
            destroyRoot(process, true, failures, deadline);
        }
    }

    private static List<ProcessHandle> reverse(Set<ProcessHandle> handles) {
        java.util.ArrayList<ProcessHandle> ordered = new java.util.ArrayList<>(handles);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    private static void destroy(ProcessHandle handle, boolean forceful, CleanupFailures failures, long deadline) {
        boolean alive = true;
        try {
            ProcessLiveness.Observation observation = ProcessLiveness.observe(handle, deadline);
            if (observation == ProcessLiveness.Observation.UNKNOWN) {
                return;
            }
            alive = observation != ProcessLiveness.Observation.EXITED;
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            if (forceful) {
                failures.excludeFromCompletion(handle);
            }
        }
        failures.interruptionBoundary();
        if (!alive) {
            return;
        }
        try {
            if (handle instanceof GuardedProcessHandle guarded) {
                guarded.destroyWithin(operationBudget(deadline), forceful);
            } else if (forceful) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Cleanup remains best-effort when the operating system denies the signal.
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            if (forceful) {
                failures.excludeFromCompletion(handle);
            }
        }
    }

    private static void destroyRoot(Process process, boolean forceful, CleanupFailures failures, long deadline) {
        if (process instanceof GuardedProcess && deadlineExpired(deadline)) {
            return;
        }
        boolean useFallback = false;
        try {
            ProcessHandle handle = process instanceof GuardedProcess guarded
                    ? guarded.toHandleWithin(operationBudget(deadline))
                    : process.toHandle();
            failures.interruptionBoundary();
            boolean alive = true;
            try {
                ProcessLiveness.Observation observation = ProcessLiveness.observe(handle, deadline);
                if (observation == ProcessLiveness.Observation.UNKNOWN) {
                    return;
                }
                alive = observation != ProcessLiveness.Observation.EXITED;
            } catch (InterruptedException interruption) {
                failures.interrupted(interruption);
                useFallback = true;
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
            }
            failures.interruptionBoundary();
            if (!alive) {
                return;
            }
            try {
                boolean signalled = handle instanceof GuardedProcessHandle guarded
                        ? guarded.destroyWithin(operationBudget(deadline), forceful)
                        : forceful ? handle.destroyForcibly() : handle.destroy();
                if (signalled || !forceful) {
                    // Process.destroy() closes all three process streams on Unix JDKs. A false result from a
                    // graceful ProcessHandle signal can race with successful signal delivery, so escalating through
                    // the Process API here can break a running shutdown hook before its output is drained. Observe
                    // the graceful deadline first; the forceful phase may use the fallback if the root stays alive.
                    return;
                }
                useFallback = true;
            } catch (InterruptedException interruption) {
                failures.interrupted(interruption);
                useFallback = true;
            } catch (UnsupportedOperationException | SecurityException ignored) {
                useFallback = true;
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
                useFallback = true;
            }
            failures.interruptionBoundary();
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
            useFallback = true;
        } catch (UnsupportedOperationException | SecurityException exception) {
            // Fall back to the Process API below. It must remain off the lifecycle thread because some JDK
            // implementations close a contended stdin stream before signalling the process.
            useFallback = true;
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            useFallback = true;
        }
        failures.interruptionBoundary();
        if (!useFallback) {
            return;
        }
        if (process instanceof GuardedProcess guarded) {
            try {
                guarded.destroyWithin(operationBudget(deadline), forceful);
            } catch (InterruptedException interruption) {
                failures.interrupted(interruption);
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
            }
            return;
        }
        if (forceful) {
            failures.dispatchDestroyFallback("procwright-process-force-destroy-", process::destroyForcibly);
        } else {
            failures.dispatchDestroyFallback("procwright-process-destroy-", process::destroy);
        }
    }

    private static void dispatchDestroyFallback(
            DestroyFallbackDispatcher destroyFallback, String threadPrefix, Runnable action) {
        try {
            destroyFallback.dispatch(threadPrefix, action);
        } catch (CommandExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }

    private static boolean waitForTree(
            Process process,
            Set<ProcessHandle> descendants,
            long deadline,
            long providerDeadline,
            boolean forceful,
            CleanupFailures failures,
            DescendantScanState scans) {
        Set<ProcessHandle> pendingDescendants = new LinkedHashSet<>();
        while (true) {
            failures.interruptionBoundary();
            if (failures.wasInterrupted() && !forceful) {
                return false;
            }
            discoverDescendants(process, descendants, pendingDescendants, failures, scans, remainingBudget(deadline));
            failures.interruptionBoundary();
            if (failures.wasInterrupted() && !forceful) {
                return false;
            }
            boolean rootExited = hasExited(process, failures, providerDeadline);
            failures.interruptionBoundary();
            // A shutdown hook may still be inside ProcessBuilder.start when its child first becomes visible. Retain
            // that handle while the root is alive; signalling it in the spawn window can make the hook's start fail.
            if (forceful || rootExited) {
                signalPendingDescendants(pendingDescendants, forceful, failures, providerDeadline);
            }
            failures.interruptionBoundary();
            DescendantState descendantState = descendantState(descendants, failures, providerDeadline);
            boolean descendantsExited = descendantState != DescendantState.LIVE;
            failures.interruptionBoundary();
            if (failures.wasInterrupted() && !forceful) {
                return false;
            }
            if (rootExited && descendantsExited) {
                // Close the observable race between the liveness check and returning success. Descendants
                // reparented before any refresh remain outside ProcessHandle's guarantees.
                boolean discovered = discoverDescendants(
                        process, descendants, pendingDescendants, failures, scans, remainingBudget(deadline));
                failures.interruptionBoundary();
                if (failures.wasInterrupted() && !forceful) {
                    return false;
                }
                signalPendingDescendants(pendingDescendants, forceful, failures, providerDeadline);
                failures.interruptionBoundary();
                rootExited = hasExited(process, failures, providerDeadline);
                failures.interruptionBoundary();
                descendantState = descendantState(descendants, failures, providerDeadline);
                descendantsExited = descendantState != DescendantState.LIVE;
                failures.interruptionBoundary();
                if (failures.wasInterrupted() && !forceful) {
                    return false;
                }
                if (!discovered
                        && rootExited
                        && descendantsExited
                        && (descendantState == DescendantState.EXITED
                                || descendants.isEmpty()
                                || deadline - System.nanoTime() > 0)) {
                    return true;
                }
                if (deadline - System.nanoTime() <= 0) {
                    return false;
                }
                continue;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10), remainingNanos));
            } catch (InterruptedException exception) {
                failures.interrupted(exception);
                addBounded(
                        descendants, newDescendants(process, descendants, failures, scans, remainingBudget(deadline)));
                if (!forceful) {
                    return false;
                }
                destroyTreeForcibly(process, descendants, failures, providerDeadline);
            }
        }
    }

    private static boolean discoverDescendants(
            Process process,
            Set<ProcessHandle> descendants,
            Set<ProcessHandle> pendingDescendants,
            CleanupFailures failures,
            DescendantScanState scans,
            Duration budget) {
        Set<ProcessHandle> discovered = newDescendants(process, descendants, failures, scans, budget);
        if (discovered.isEmpty()) {
            return false;
        }
        addBounded(descendants, discovered);
        addBounded(pendingDescendants, discovered);
        return true;
    }

    private static void signalPendingDescendants(
            Set<ProcessHandle> pendingDescendants, boolean forceful, CleanupFailures failures, long deadline) {
        if (pendingDescendants.isEmpty()) {
            return;
        }
        Set<ProcessHandle> toSignal = new LinkedHashSet<>(pendingDescendants);
        pendingDescendants.clear();
        destroyDescendants(toSignal, forceful, failures, deadline);
    }

    private static Set<ProcessHandle> newDescendants(
            Process process,
            Set<ProcessHandle> descendants,
            CleanupFailures failures,
            DescendantScanState scans,
            Duration budget) {
        Set<ProcessHandle> discovered = scans.discover(process, descendants, failures, budget);
        discovered.removeAll(descendants);
        return discovered;
    }

    private static Duration remainingBudget(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private static Duration operationBudget(long deadline) {
        long remaining = deadline - System.nanoTime();
        return Duration.ofNanos(Math.max(1, remaining));
    }

    private static boolean deadlineExpired(long deadline) {
        return deadline - System.nanoTime() <= 0;
    }

    private static Duration operationPhaseBudget(Duration waitBudget) {
        return waitBudget.isZero() ? PROCESS_TREE_SCANNER.scanTimeout() : waitBudget;
    }

    private static final class DescendantScanState {

        private Set<ProcessHandle> discover(
                Process process, Set<ProcessHandle> knownDescendants, CleanupFailures failures, Duration budget) {
            if (budget.isZero()) {
                return new LinkedHashSet<>();
            }
            long deadline = DurationSupport.deadlineFromNow(budget);
            Set<ProcessHandle> discovered;
            try {
                discovered = new LinkedHashSet<>(PROCESS_TREE_SCANNER.descendants(process, budget));
            } catch (Error failure) {
                failures.record(failure);
                discovered = new LinkedHashSet<>();
            }
            failures.interruptionBoundary();
            if (!knownDescendants.isEmpty() && discovered.size() < PROCESS_TREE_SCANNER.descendantLimit()) {
                try {
                    addBounded(
                            discovered,
                            PROCESS_TREE_SCANNER.descendantsOfHandles(knownDescendants, remainingBudget(deadline)));
                } catch (Error failure) {
                    failures.record(failure);
                }
                failures.interruptionBoundary();
            }
            return discovered;
        }
    }

    private static DescendantState descendantState(
            Set<ProcessHandle> handles, CleanupFailures failures, long deadline) {
        boolean observable = true;
        for (ProcessHandle handle : handles) {
            if (failures.isExcludedFromCompletion(handle)) {
                continue;
            }
            boolean alive = false;
            try {
                ProcessLiveness.Observation observation = ProcessLiveness.observe(handle, deadline);
                if (observation == ProcessLiveness.Observation.UNKNOWN) {
                    return DescendantState.LIVE;
                }
                if (observation == ProcessLiveness.Observation.UNOBSERVABLE) {
                    observable = false;
                } else {
                    alive = observation == ProcessLiveness.Observation.LIVE;
                }
            } catch (InterruptedException interruption) {
                failures.interrupted(interruption);
                observable = false;
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
                observable = false;
            }
            failures.interruptionBoundary();
            if (alive) {
                return DescendantState.LIVE;
            }
        }
        return observable ? DescendantState.EXITED : DescendantState.UNOBSERVABLE;
    }

    private enum DescendantState {
        LIVE,
        EXITED,
        UNOBSERVABLE
    }

    private static boolean hasExited(Process process, CleanupFailures failures, long deadline) {
        if (!(process instanceof GuardedProcess guarded)) {
            ProcessLiveness.Observation observation;
            try {
                observation = ProcessLiveness.observe(process, deadline);
            } catch (InterruptedException interruption) {
                failures.interrupted(interruption);
                return exitObserved(process, failures, deadline);
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
                return exitObserved(process, failures, deadline);
            }
            return observation == ProcessLiveness.Observation.UNOBSERVABLE
                    ? exitObserved(process, failures, deadline)
                    : observation == ProcessLiveness.Observation.EXITED;
        }
        try {
            LivenessObservationBudget budget =
                    LivenessObservationBudget.untilLifecycleDeadline(deadline, guarded.providerOperationTimeout());
            return ProcessLiveness.observeExit(guarded, budget) == ProcessLiveness.Observation.EXITED;
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
            return exitObserved(process, failures, deadline);
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            return exitObserved(process, failures, deadline);
        }
    }

    private static boolean exitObserved(Process process, CleanupFailures failures, long deadline) {
        try {
            return ProcessLiveness.exitObserved(process, deadline);
        } catch (InterruptedException interruption) {
            failures.interrupted(interruption);
            return false;
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            return false;
        }
    }

    private static final class CleanupFailures {

        private final Set<ProcessHandle> completionExcluded = Collections.newSetFromMap(new IdentityHashMap<>());
        private final DestroyFallbackDispatcher destroyFallback;
        private Throwable primary;
        private boolean restoreInterrupt;

        private CleanupFailures(DestroyFallbackDispatcher destroyFallback) {
            this.destroyFallback = Objects.requireNonNull(destroyFallback, "destroyFallback");
        }

        private void attempt(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                record(failure);
            } finally {
                interruptionBoundary();
            }
        }

        private void dispatchDestroyFallback(String threadPrefix, Runnable action) {
            attempt(() -> ProcessLifecycle.dispatchDestroyFallback(destroyFallback, threadPrefix, action));
        }

        private void record(Throwable failure) {
            if (failure instanceof CommandExecutionException executionFailure
                    && SuppressionSupport.containsInterruption(executionFailure)) {
                interrupted(executionFailure);
                return;
            }
            primary = SuppressionSupport.combine(primary, failure);
        }

        private void excludeFromCompletion(ProcessHandle handle) {
            completionExcluded.add(handle);
        }

        private boolean isExcludedFromCompletion(ProcessHandle handle) {
            return completionExcluded.contains(handle);
        }

        private void interrupted(InterruptedException interruption) {
            CommandExecutionException interruptionFailure =
                    new CommandExecutionException("Interrupted while waiting for command cleanup", interruption);
            interrupted(interruptionFailure);
        }

        private void interrupted(CommandExecutionException interruptionFailure) {
            if (!restoreInterrupt) {
                Throwable previousPrimary = primary;
                primary = interruptionFailure;
                SuppressionSupport.attach(primary, previousPrimary);
            } else {
                SuppressionSupport.attach(primary, interruptionFailure);
            }
            restoreInterrupt = true;
            Thread.interrupted();
        }

        private void interruptionBoundary() {
            if (Thread.interrupted()) {
                interrupted(new InterruptedException("cleanup boundary observed interrupt status"));
            }
        }

        private boolean hasFailure() {
            return primary != null;
        }

        private boolean wasInterrupted() {
            return restoreInterrupt;
        }

        private void rethrowIfPresent() {
            interruptionBoundary();
            if (primary instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (primary instanceof Error error) {
                throw error;
            }
            if (primary != null) {
                throw new AssertionError("cleanup failure must be unchecked", primary);
            }
        }

        private void restoreInterrupt() {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    interface DestroyFallbackDispatcher {

        void dispatch(String threadPrefix, Runnable action);
    }
}

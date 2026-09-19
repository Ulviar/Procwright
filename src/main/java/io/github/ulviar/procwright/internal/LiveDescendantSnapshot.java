/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Accumulates live process descendants across lifecycle polls for later cleanup.
 *
 * <p>One lifecycle watcher refreshes the snapshot. Cleanup and diagnostics may read the latest immutable value
 * concurrently. Cleanup atomically waits for an active refresh and seals the snapshot, so no later refresh can publish
 * handles or sticky status after the handoff.
 *
 * <p>This type is public only for use by non-exported internal subpackages.
 */
public final class LiveDescendantSnapshot {

    private static final ProcessTreeScanner PROCESS_TREE_SCANNER = ProcessTreeScanner.shared();

    private volatile KnownDescendants observed;
    private boolean sealed;

    /** Creates an empty snapshot. */
    public LiveDescendantSnapshot() {
        this(KnownDescendants.empty());
    }

    LiveDescendantSnapshot(KnownDescendants initial) {
        observed = Objects.requireNonNull(initial, "initial");
    }

    /** Returns the latest immutable set of retained live or unobservable descendants. */
    public Set<ProcessHandle> current() {
        return observed.handles();
    }

    /** Seals observation and returns the final invariant-carrying snapshot for process-tree cleanup. */
    public synchronized KnownDescendants sealForCleanup() {
        sealed = true;
        return observed;
    }

    void refresh(Process process, Duration scanBudget) {
        RefreshFailure failure;
        synchronized (this) {
            if (sealed) {
                return;
            }
            ProcessTreeScanner.DescendantScan current = scan(process, scanBudget);
            if (observationInterrupted(current)) {
                publishWithoutPruning(current);
                return;
            }
            failure = replaceWithMerged(current);
        }
        rethrow(failure);
    }

    private static ProcessTreeScanner.DescendantScan scan(Process process, Duration scanBudget) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(scanBudget, "scanBudget");
        return PROCESS_TREE_SCANNER.scanDescendants(process, scanBudget);
    }

    private RefreshFailure replaceWithMerged(ProcessTreeScanner.DescendantScan current) {
        try {
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> merged = new LinkedHashMap<>();
            boolean mergeOverflow = addLiveBounded(merged, observed.handlesByIdentity());
            mergeOverflow |= addLiveBounded(merged, current.handlesByIdentity());
            publish(current, merged, mergeOverflow);
        } catch (RuntimeException | Error failure) {
            publishWithoutPruning(current);
            if (current.failure() != null) {
                return new RefreshFailure(current.failure(), failure);
            }
            return new RefreshFailure(failure, null);
        }
        return current.failure() == null ? null : new RefreshFailure(current.failure(), null);
    }

    private static void rethrow(RefreshFailure failure) {
        if (failure == null) {
            return;
        }
        Throwable combined = FailureAggregation.combine(
                failure.primary(), failure.secondary(), "Descendant scan and liveness pruning both failed");
        if (combined instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) combined;
    }

    private void publishWithoutPruning(ProcessTreeScanner.DescendantScan current) {
        Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> merged = new LinkedHashMap<>();
        boolean mergeOverflow = addBounded(merged, observed.handlesByIdentity());
        mergeOverflow |= addBounded(merged, current.handlesByIdentity());
        publish(current, merged, mergeOverflow);
    }

    private void publish(
            ProcessTreeScanner.DescendantScan current,
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> merged,
            boolean mergeOverflow) {
        observed = KnownDescendants.copyOf(
                merged,
                observed.truncated() || current.truncated() || mergeOverflow,
                observed.discoveryUnavailable() || observationUnavailable(current));
    }

    private static boolean observationUnavailable(ProcessTreeScanner.DescendantScan scan) {
        return scan.incompleteReason() == ProcessTreeScanner.IncompleteReason.UNAVAILABLE;
    }

    private static boolean observationInterrupted(ProcessTreeScanner.DescendantScan scan) {
        return scan.incompleteReason() == ProcessTreeScanner.IncompleteReason.INTERRUPTED;
    }

    private record RefreshFailure(Throwable primary, Throwable secondary) {}

    private static boolean addBounded(
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> target,
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidates) {
        for (Map.Entry<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidate : candidates.entrySet()) {
            if (target.containsKey(candidate.getKey())) {
                continue;
            }
            if (target.size() == PROCESS_TREE_SCANNER.descendantLimit()) {
                return true;
            }
            target.put(candidate.getKey(), candidate.getValue());
        }
        return false;
    }

    private static boolean addLiveBounded(
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> target,
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidates) {
        for (Map.Entry<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidate : candidates.entrySet()) {
            if (target.containsKey(candidate.getKey())) {
                continue;
            }
            if (target.size() == PROCESS_TREE_SCANNER.descendantLimit()) {
                return true;
            }
            if (ProcessLiveness.observe(candidate.getValue()) != ProcessLiveness.Observation.EXITED) {
                target.put(candidate.getKey(), candidate.getValue());
            }
        }
        return false;
    }
}

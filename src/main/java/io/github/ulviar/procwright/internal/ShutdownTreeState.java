/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the bounded descendant, pending-signal, and completion-proof state of one process-tree shutdown. */
final class ShutdownTreeState {

    private static final ProcessTreeScanner PROCESS_TREE_SCANNER = ProcessTreeScanner.shared();

    private final Process process;
    private final ShutdownFailureLedger failures;
    private final Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> descendants = new LinkedHashMap<>();
    private final Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> pendingDescendants = new LinkedHashMap<>();
    private final Set<ProcessTreeScanner.HandleIdentity> completionExcluded = new LinkedHashSet<>();
    private boolean descendantCapacityExceeded;
    private boolean discoveryUnavailable;
    private boolean latestDiscoveryIncomplete;

    ShutdownTreeState(Process process, ShutdownFailureLedger failures) {
        this.process = process;
        this.failures = failures;
    }

    void initialize(KnownDescendants knownDescendants, Duration budget) {
        Objects.requireNonNull(knownDescendants, "knownDescendants");
        addBounded(descendants, knownDescendants.handlesByIdentity());
        if (knownDescendants.truncated()) {
            recordDescendantCapacityExceeded();
        }
        if (knownDescendants.discoveryUnavailable()) {
            recordUnavailableDiscovery();
        }
        addBounded(descendants, discoverNew(budget));
        failures.interruptionBoundary();
    }

    void startForcePhase(Duration budget) {
        addBounded(descendants, discoverNew(budget));
    }

    void discoverForForce(Duration budget) {
        addBounded(descendants, discoverNew(budget));
    }

    boolean discoverPending(Duration budget) {
        Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> discovered = discoverNew(budget);
        if (discovered.isEmpty()) {
            return false;
        }
        return addPendingBounded(discovered);
    }

    List<ProcessHandle> takeAllDescendants() {
        pendingDescendants.clear();
        return new ArrayList<>(descendants.values());
    }

    Set<ProcessHandle> takePendingDescendants() {
        if (pendingDescendants.isEmpty()) {
            return Set.of();
        }
        Set<ProcessHandle> pending = new LinkedHashSet<>(pendingDescendants.values());
        pendingDescendants.clear();
        return pending;
    }

    boolean hasNoDescendantsAndCompleteDiscovery() {
        return descendants.isEmpty()
                && !descendantCapacityExceeded
                && !discoveryUnavailable
                && !latestDiscoveryIncomplete;
    }

    void excludeFromCompletion(ProcessHandle handle) {
        descendants.forEach((identity, descendant) -> {
            if (descendant == handle) {
                completionExcluded.add(identity);
            }
        });
    }

    DescendantState observeDescendants() {
        boolean observable = true;
        for (Map.Entry<ProcessTreeScanner.HandleIdentity, ProcessHandle> descendant : descendants.entrySet()) {
            if (completionExcluded.contains(descendant.getKey())) {
                continue;
            }
            ProcessHandle handle = descendant.getValue();
            boolean alive = false;
            try {
                ProcessLiveness.Observation observation = ProcessLiveness.observe(handle);
                if (observation == ProcessLiveness.Observation.UNOBSERVABLE) {
                    observable = false;
                } else {
                    alive = observation == ProcessLiveness.Observation.LIVE;
                }
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
                observable = false;
            }
            failures.interruptionBoundary();
            if (alive) {
                return DescendantState.LIVE;
            }
        }
        return observable && !descendantCapacityExceeded && !discoveryUnavailable && !latestDiscoveryIncomplete
                ? DescendantState.EXITED
                : DescendantState.UNOBSERVABLE;
    }

    private Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> discoverNew(Duration budget) {
        Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> discovered = discover(budget);
        discovered.keySet().removeAll(descendants.keySet());
        return discovered;
    }

    private Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> discover(Duration budget) {
        latestDiscoveryIncomplete = false;
        if (budget.isZero()) {
            recordDynamicScanStatus(
                    ProcessTreeScanner.DescendantScan.incomplete(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE));
            return new LinkedHashMap<>();
        }
        long deadline = DurationSupport.deadlineFromNow(budget);
        Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> discovered;
        try {
            ProcessTreeScanner.DescendantScan rootScan = PROCESS_TREE_SCANNER.scanDescendants(process, budget);
            discovered = new LinkedHashMap<>(rootScan.handlesByIdentity());
            recordDynamicScanStatus(rootScan);
        } catch (Error failure) {
            latestDiscoveryIncomplete = true;
            failures.record(failure);
            discovered = new LinkedHashMap<>();
        }
        failures.interruptionBoundary();
        if (!descendants.isEmpty() && discovered.size() < PROCESS_TREE_SCANNER.descendantLimit()) {
            try {
                ProcessTreeScanner.DescendantScan descendantScan =
                        PROCESS_TREE_SCANNER.scanDescendantsOfHandles(descendants.values(), remainingBudget(deadline));
                addBounded(discovered, descendantScan.handlesByIdentity());
                recordDynamicScanStatus(descendantScan);
            } catch (Error failure) {
                latestDiscoveryIncomplete = true;
                failures.record(failure);
            }
            failures.interruptionBoundary();
        }
        return discovered;
    }

    private void addBounded(
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> target,
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidates) {
        for (Map.Entry<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidate : candidates.entrySet()) {
            if (target.containsKey(candidate.getKey())) {
                continue;
            }
            if (target.size() == PROCESS_TREE_SCANNER.descendantLimit()) {
                recordDescendantCapacityExceeded();
                return;
            }
            target.put(candidate.getKey(), candidate.getValue());
        }
    }

    private boolean addPendingBounded(Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidates) {
        boolean added = false;
        for (Map.Entry<ProcessTreeScanner.HandleIdentity, ProcessHandle> candidate : candidates.entrySet()) {
            if (descendants.containsKey(candidate.getKey())) {
                continue;
            }
            if (descendants.size() == PROCESS_TREE_SCANNER.descendantLimit()) {
                recordDescendantCapacityExceeded();
                return added;
            }
            descendants.put(candidate.getKey(), candidate.getValue());
            pendingDescendants.put(candidate.getKey(), candidate.getValue());
            added = true;
        }
        return added;
    }

    private void recordDescendantCapacityExceeded() {
        if (descendantCapacityExceeded) {
            return;
        }
        descendantCapacityExceeded = true;
        failures.record(new CommandExecutionException(
                "Process tree exceeded the bounded descendant limit of " + PROCESS_TREE_SCANNER.descendantLimit()));
    }

    private void recordDynamicScanStatus(ProcessTreeScanner.DescendantScan scan) {
        latestDiscoveryIncomplete |= scan.incomplete();
        if (scan.failure() != null) {
            failures.record(scan.failure());
        }
        recordNonCompletionStatus(scan);
    }

    private void recordNonCompletionStatus(ProcessTreeScanner.DescendantScan scan) {
        if (scan.truncated()) {
            recordDescendantCapacityExceeded();
        } else if (scan.incomplete()) {
            if (scan.incompleteReason() == ProcessTreeScanner.IncompleteReason.UNAVAILABLE) {
                recordUnavailableDiscovery();
            }
        }
    }

    private void recordUnavailableDiscovery() {
        if (discoveryUnavailable) {
            return;
        }
        discoveryUnavailable = true;
        failures.record(new CommandExecutionException("Process tree discovery did not complete"));
    }

    private static Duration remainingBudget(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    enum DescendantState {
        LIVE,
        EXITED,
        UNOBSERVABLE
    }
}

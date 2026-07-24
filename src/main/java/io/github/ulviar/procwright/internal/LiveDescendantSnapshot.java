/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Accumulates live process descendants across lifecycle polls for later cleanup.
 *
 * <p>One lifecycle watcher refreshes the snapshot. Cleanup and diagnostics may read the latest immutable value
 * concurrently.
 *
 * <p>This type is public only for use by non-exported internal subpackages.
 */
public final class LiveDescendantSnapshot {

    private static final ProcessTreeScanner PROCESS_TREE_SCANNER = ProcessTreeScanner.shared();

    private volatile Set<ProcessHandle> observed;

    /** Creates an empty snapshot. */
    public LiveDescendantSnapshot() {
        this(Set.of());
    }

    LiveDescendantSnapshot(Set<ProcessHandle> initial) {
        observed = immutableBounded(Objects.requireNonNull(initial, "initial"));
    }

    /** Returns the latest immutable set of retained live or unobservable descendants. */
    public Set<ProcessHandle> current() {
        return observed;
    }

    void refreshWithFreshLivenessBudget(Process process, Duration scanBudget) throws InterruptedException {
        Set<ProcessHandle> current = scan(process, scanBudget);
        replaceWithMerged(current, DurationSupport.deadlineFromNow(scanBudget));
    }

    void refresh(Process process, Duration scanBudget, long livenessDeadline) throws InterruptedException {
        Set<ProcessHandle> current = scan(process, scanBudget);
        replaceWithMerged(current, livenessDeadline);
    }

    private static Set<ProcessHandle> scan(Process process, Duration scanBudget) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(scanBudget, "scanBudget");
        return new LinkedHashSet<>(PROCESS_TREE_SCANNER.descendants(process, scanBudget));
    }

    private void replaceWithMerged(Set<ProcessHandle> current, long livenessDeadline) throws InterruptedException {
        Set<ProcessHandle> merged = new LinkedHashSet<>();
        addLiveBounded(merged, observed, livenessDeadline);
        addLiveBounded(merged, current, livenessDeadline);
        observed = Collections.unmodifiableSet(merged);
    }

    private static void addLiveBounded(
            Set<ProcessHandle> target, Iterable<ProcessHandle> candidates, long livenessDeadline)
            throws InterruptedException {
        for (ProcessHandle candidate : candidates) {
            if (target.size() == PROCESS_TREE_SCANNER.descendantLimit()) {
                return;
            }
            if (mayStillBeAlive(candidate, livenessDeadline)) {
                target.add(candidate);
            }
        }
    }

    private static boolean mayStillBeAlive(ProcessHandle handle, long livenessDeadline) throws InterruptedException {
        return ProcessLiveness.observe(handle, livenessDeadline) != ProcessLiveness.Observation.EXITED;
    }

    private static Set<ProcessHandle> immutableBounded(Iterable<ProcessHandle> candidates) {
        Set<ProcessHandle> bounded = new LinkedHashSet<>();
        for (ProcessHandle candidate : candidates) {
            if (bounded.size() == PROCESS_TREE_SCANNER.descendantLimit()) {
                break;
            }
            bounded.add(Objects.requireNonNull(candidate, "candidate"));
        }
        return Collections.unmodifiableSet(bounded);
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Discovers bounded process trees and protects provider process objects returned to callers.
 *
 * <p>Potentially blocking provider calls are delegated to {@link ProcessProviderOperationOwner}; this class owns only
 * traversal limits, deduplication, and guarded views.
 */
final class ProcessTreeScanner {

    static final int SHARED_OPERATION_CAPACITY = 32;
    static final int SHARED_DESCENDANT_LIMIT = 4_096;
    static final Duration SHARED_SCAN_TIMEOUT = Duration.ofMillis(250);
    static final Duration PROVIDER_OPERATION_TIMEOUT = Duration.ofSeconds(5);

    private static final ProcessTreeScanner SHARED =
            new ProcessTreeScanner(SHARED_OPERATION_CAPACITY, SHARED_DESCENDANT_LIMIT, SHARED_SCAN_TIMEOUT);

    private final ProcessProviderOperationOwner operations;
    private final int descendantLimit;
    private final Duration scanTimeout;
    private final Duration providerOperationTimeout;

    ProcessTreeScanner(int operationCapacity, int descendantLimit, Duration scanTimeout) {
        this(operationCapacity, descendantLimit, scanTimeout, PROVIDER_OPERATION_TIMEOUT);
    }

    ProcessTreeScanner(
            int operationCapacity, int descendantLimit, Duration scanTimeout, Duration providerOperationTimeout) {
        this(
                ProcessProviderOperationOwner.production(operationCapacity),
                descendantLimit,
                scanTimeout,
                providerOperationTimeout);
    }

    ProcessTreeScanner(
            ProcessProviderOperationOwner operations,
            int descendantLimit,
            Duration scanTimeout,
            Duration providerOperationTimeout) {
        if (descendantLimit <= 0) {
            throw new IllegalArgumentException("descendantLimit must be positive");
        }
        this.operations = Objects.requireNonNull(operations, "operations");
        this.descendantLimit = descendantLimit;
        this.scanTimeout = Objects.requireNonNull(scanTimeout, "scanTimeout");
        this.providerOperationTimeout = Objects.requireNonNull(providerOperationTimeout, "providerOperationTimeout");
        if (scanTimeout.isNegative() || scanTimeout.isZero()) {
            throw new IllegalArgumentException("scanTimeout must be positive");
        }
        if (providerOperationTimeout.isNegative() || providerOperationTimeout.isZero()) {
            throw new IllegalArgumentException("providerOperationTimeout must be positive");
        }
    }

    static ProcessTreeScanner shared() {
        return SHARED;
    }

    Process guard(Process process) {
        Objects.requireNonNull(process, "process");
        return process instanceof GuardedProcess ? process : new GuardedProcess(process, this);
    }

    Set<ProcessHandle> descendants(Process process) {
        return descendants(process, scanTimeout);
    }

    Set<ProcessHandle> descendants(Process process, Duration budget) {
        Objects.requireNonNull(process, "process");
        Duration timeout = scanBudget(budget);
        if (timeout.isZero()) {
            return Set.of();
        }
        Process source = unwrap(process);
        Optional<Set<ProcessHandle>> scanned = operations.bestEffort("procwright-process-scan-", timeout, () -> {
            Set<ProcessHandle> handles = collectDescendants(source, timeout);
            return guardIfRequired(process, handles);
        });
        return scanned.orElseGet(Set::of);
    }

    Set<ProcessHandle> descendantsOfHandles(Iterable<ProcessHandle> handles) {
        return descendantsOfHandles(handles, scanTimeout);
    }

    Set<ProcessHandle> descendantsOfHandles(Iterable<ProcessHandle> handles, Duration budget) {
        Objects.requireNonNull(handles, "handles");
        Duration timeout = scanBudget(budget);
        if (timeout.isZero()) {
            return Set.of();
        }
        List<ProcessHandle> roots = new ArrayList<>();
        boolean guarded = false;
        for (ProcessHandle handle : handles) {
            if (roots.size() == descendantLimit) {
                break;
            }
            Objects.requireNonNull(handle, "handle");
            guarded |= handle instanceof GuardedProcessHandle;
            roots.add(unwrap(handle));
        }
        boolean guardResults = guarded;
        Optional<Set<ProcessHandle>> scanned = operations.bestEffort("procwright-handle-scan-", timeout, () -> {
            Set<ProcessHandle> result = collectChildren(roots, timeout);
            return guardResults ? guardHandles(result) : result;
        });
        return scanned.orElseGet(Set::of);
    }

    Set<ProcessHandle> childrenOfHandle(ProcessHandle handle) {
        Objects.requireNonNull(handle, "handle");
        ProcessHandle source = unwrap(handle);
        boolean guardResults = handle instanceof GuardedProcessHandle;
        Optional<Set<ProcessHandle>> scanned =
                operations.bestEffort("procwright-handle-children-scan-", scanTimeout, () -> {
                    Set<ProcessHandle> result = collectStream(source.children(), scanTimeout);
                    return guardResults ? guardHandles(result) : result;
                });
        return scanned.orElseGet(Set::of);
    }

    <T> T required(String operationName, Duration timeout, Callable<T> operation) throws InterruptedException {
        return operations.required(operationName, timeout, operation);
    }

    int availableOperationPermits() {
        return operations.availablePermits();
    }

    boolean awaitReportingSettlement(Duration timeout) throws InterruptedException {
        return operations.awaitReportingSettlement(timeout);
    }

    static boolean causedByOperationDeadline(CommandExecutionException failure) {
        return ProcessProviderOperationOwner.causedByOperationDeadline(failure);
    }

    static CommandExecutionException operationDeadlineExceeded(String operation) {
        return ProcessProviderOperationOwner.operationDeadlineExceeded(operation);
    }

    int descendantLimit() {
        return descendantLimit;
    }

    Duration providerOperationTimeout() {
        return providerOperationTimeout;
    }

    Duration scanTimeout() {
        return scanTimeout;
    }

    private Set<ProcessHandle> collectDescendants(Process process, Duration timeout) {
        return collectStream(process.descendants(), timeout);
    }

    private Set<ProcessHandle> collectChildren(List<ProcessHandle> roots, Duration timeout) {
        long deadline = DurationSupport.deadlineFromNow(timeout);
        ArrayDeque<ProcessHandle> pending = new ArrayDeque<>(roots);
        LinkedHashSet<HandleIdentity> expanded = new LinkedHashSet<>();
        LinkedHashMap<HandleIdentity, ProcessHandle> observed = new LinkedHashMap<>();
        while (!pending.isEmpty() && observed.size() < descendantLimit && deadline - System.nanoTime() > 0) {
            ProcessHandle parent = pending.removeFirst();
            if (!expanded.add(identity(parent))) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            Set<ProcessHandle> children = collectStream(parent.children(), Duration.ofNanos(remaining));
            for (ProcessHandle child : children) {
                HandleIdentity childIdentity = identity(child);
                if (observed.putIfAbsent(childIdentity, child) == null) {
                    pending.addLast(child);
                }
                if (observed.size() == descendantLimit) {
                    break;
                }
            }
        }
        return new LinkedHashSet<>(observed.values());
    }

    private Set<ProcessHandle> collectStream(Stream<ProcessHandle> stream, Duration timeout) {
        Objects.requireNonNull(stream, "process handle stream");
        long deadline = DurationSupport.deadlineFromNow(timeout);
        LinkedHashMap<HandleIdentity, ProcessHandle> observed = new LinkedHashMap<>();
        Throwable traversalFailure = null;
        try {
            Iterator<ProcessHandle> iterator = stream.iterator();
            while (observed.size() < descendantLimit && deadline - System.nanoTime() > 0) {
                if (!iterator.hasNext()) {
                    break;
                }
                ProcessHandle handle = Objects.requireNonNull(iterator.next(), "process descendant");
                observed.putIfAbsent(identity(handle), handle);
            }
        } catch (RuntimeException | Error failure) {
            traversalFailure = failure;
        }
        Error closeFailure = null;
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // Traversal is best effort; ordinary close failures degrade to the collected prefix.
        } catch (Error failure) {
            closeFailure = failure;
        }
        if (traversalFailure instanceof Error fatal) {
            SuppressionSupport.attach(fatal, closeFailure);
            throw fatal;
        }
        if (closeFailure != null) {
            SuppressionSupport.attach(closeFailure, traversalFailure);
            throw closeFailure;
        }
        if (traversalFailure instanceof RuntimeException unavailable) {
            throw unavailable;
        }
        return new LinkedHashSet<>(observed.values());
    }

    private Duration scanBudget(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative()) {
            throw new IllegalArgumentException("budget must not be negative");
        }
        return budget.compareTo(scanTimeout) < 0 ? budget : scanTimeout;
    }

    private static HandleIdentity identity(ProcessHandle handle) {
        long pid = handle.pid();
        Instant started = null;
        try {
            started = handle.info().startInstant().orElse(null);
        } catch (RuntimeException ignored) {
            // PID identity is the bounded fallback when process metadata is unavailable.
        }
        return new HandleIdentity(pid, started);
    }

    private Set<ProcessHandle> guardIfRequired(Process root, Set<ProcessHandle> handles) {
        return root instanceof GuardedProcess ? guardHandles(handles) : handles;
    }

    private Set<ProcessHandle> guardHandles(Set<ProcessHandle> handles) {
        LinkedHashSet<ProcessHandle> guarded = new LinkedHashSet<>(handles.size());
        for (ProcessHandle handle : handles) {
            guarded.add(guardObserved(handle));
        }
        return guarded;
    }

    ProcessHandle guardObserved(ProcessHandle handle) {
        return handle instanceof GuardedProcessHandle
                ? handle
                : new GuardedProcessHandle(handle, this, identity(handle));
    }

    private static Process unwrap(Process process) {
        return process instanceof GuardedProcess guarded ? guarded.delegate() : process;
    }

    private static ProcessHandle unwrap(ProcessHandle handle) {
        return handle instanceof GuardedProcessHandle guarded ? guarded.delegate() : handle;
    }

    record HandleIdentity(long pid, Instant startInstant) {}
}

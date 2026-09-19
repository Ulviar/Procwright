/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Discovers process trees with bounded traversal time, admission, and retained descendants. */
final class ProcessTreeScanner {

    static final int SHARED_OPERATION_CAPACITY = 32;
    static final int SHARED_DESCENDANT_LIMIT = 4_096;
    static final Duration SHARED_SCAN_TIMEOUT = Duration.ofMillis(250);

    private static final ProcessTreeScanner SHARED =
            new ProcessTreeScanner(SHARED_OPERATION_CAPACITY, SHARED_DESCENDANT_LIMIT, SHARED_SCAN_TIMEOUT);

    private final ProcessScanOperationOwner operations;
    private final int descendantLimit;
    private final Duration scanTimeout;

    ProcessTreeScanner(int operationCapacity, int descendantLimit, Duration scanTimeout) {
        this(ProcessScanOperationOwner.production(operationCapacity), descendantLimit, scanTimeout);
    }

    ProcessTreeScanner(ProcessScanOperationOwner operations, int descendantLimit, Duration scanTimeout) {
        if (descendantLimit <= 0) {
            throw new IllegalArgumentException("descendantLimit must be positive");
        }
        this.operations = Objects.requireNonNull(operations, "operations");
        this.descendantLimit = descendantLimit;
        this.scanTimeout = Objects.requireNonNull(scanTimeout, "scanTimeout");
        if (scanTimeout.isNegative() || scanTimeout.isZero()) {
            throw new IllegalArgumentException("scanTimeout must be positive");
        }
    }

    static ProcessTreeScanner shared() {
        return SHARED;
    }

    Set<ProcessHandle> descendants(Process process) {
        return descendants(process, scanTimeout);
    }

    Set<ProcessHandle> descendants(Process process, Duration budget) {
        DescendantScan scan = scanDescendants(process, budget);
        scan.rethrowFailure();
        return scan.handles();
    }

    DescendantScan scanDescendants(Process process, Duration budget) {
        Objects.requireNonNull(process, "process");
        ScanBudget scanBudget = scanBudget(budget);
        if (scanBudget.timeout().isZero()) {
            return DescendantScan.incomplete(scanBudget.deadlineReason());
        }
        ProcessScanOperationOwner.Result<DescendantScan> scanned = operations.scan(
                "procwright-process-scan-",
                scanBudget.timeout(),
                () -> collectDescendants(process, scanBudget.timeout(), scanBudget.deadlineReason()));
        return scanned.completed()
                ? scanned.value()
                : DescendantScan.incomplete(incompleteReason(scanned.failure(), scanBudget));
    }

    Set<ProcessHandle> descendantsOfHandles(Iterable<ProcessHandle> handles) {
        return descendantsOfHandles(handles, scanTimeout);
    }

    Set<ProcessHandle> descendantsOfHandles(Iterable<ProcessHandle> handles, Duration budget) {
        DescendantScan scan = scanDescendantsOfHandles(handles, budget);
        scan.rethrowFailure();
        return scan.handles();
    }

    DescendantScan scanDescendantsOfHandles(Iterable<ProcessHandle> handles, Duration budget) {
        Objects.requireNonNull(handles, "handles");
        ScanBudget scanBudget = scanBudget(budget);
        if (scanBudget.timeout().isZero()) {
            return DescendantScan.incomplete(scanBudget.deadlineReason());
        }
        long deadline = DurationSupport.deadlineFromNow(scanBudget.timeout());
        ProcessScanOperationOwner.Result<RootHandles> observedRoots =
                operations.scan("procwright-handle-roots-", scanBudget.timeout(), () -> collectRoots(handles));
        if (!observedRoots.completed()) {
            return DescendantScan.incomplete(incompleteReason(observedRoots.failure(), scanBudget));
        }
        RootHandles roots = observedRoots.value();
        if (roots.failure() != null) {
            return DescendantScan.incomplete(IncompleteReason.UNAVAILABLE, roots.failure());
        }
        Duration remaining = remainingBudget(deadline);
        if (remaining.isZero()) {
            return roots.truncated()
                    ? DescendantScan.limitReached(roots.failure())
                    : DescendantScan.incomplete(
                            combine(roots.incompleteReason(), scanBudget.deadlineReason()), roots.failure());
        }
        ProcessScanOperationOwner.Result<DescendantScan> scanned = operations.scan(
                "procwright-handle-scan-",
                remaining,
                () -> collectChildren(roots, remaining, scanBudget.deadlineReason()));
        if (scanned.completed()) {
            return scanned.value();
        }
        return roots.truncated()
                ? DescendantScan.limitReached(roots.failure())
                : DescendantScan.incomplete(
                        combine(roots.incompleteReason(), incompleteReason(scanned.failure(), scanBudget)),
                        roots.failure());
    }

    int availableOperationPermits() {
        return operations.availablePermits();
    }

    int descendantLimit() {
        return descendantLimit;
    }

    Duration scanTimeout() {
        return scanTimeout;
    }

    private DescendantScan collectDescendants(Process process, Duration timeout, IncompleteReason deadlineReason) {
        return collectStream(process.descendants(), timeout, deadlineReason);
    }

    private RootHandles collectRoots(Iterable<ProcessHandle> handles) {
        LinkedHashMap<HandleIdentity, ProcessHandle> roots = new LinkedHashMap<>();
        boolean truncated = false;
        IncompleteReason incompleteReason = null;
        Error failure = null;
        try {
            for (ProcessHandle handle : handles) {
                Objects.requireNonNull(handle, "handle");
                HandleIdentity identity;
                try {
                    identity = identity(handle);
                } catch (RuntimeException unavailable) {
                    incompleteReason = IncompleteReason.UNAVAILABLE;
                    continue;
                } catch (Error fatal) {
                    incompleteReason = IncompleteReason.UNAVAILABLE;
                    failure = appendFailure(failure, fatal);
                    break;
                }
                if (roots.containsKey(identity)) {
                    continue;
                }
                if (roots.size() == descendantLimit) {
                    truncated = true;
                    break;
                }
                roots.put(identity, handle);
            }
        } catch (RuntimeException unavailable) {
            incompleteReason = IncompleteReason.UNAVAILABLE;
        } catch (Error fatal) {
            incompleteReason = IncompleteReason.UNAVAILABLE;
            failure = appendFailure(failure, fatal);
        }
        return new RootHandles(roots, truncated, incompleteReason, failure);
    }

    private DescendantScan collectChildren(RootHandles roots, Duration timeout, IncompleteReason deadlineReason) {
        long deadline = DurationSupport.deadlineFromNow(timeout);
        ArrayDeque<IndexedHandle> pending = new ArrayDeque<>();
        roots.handlesByIdentity().forEach((identity, handle) -> pending.addLast(new IndexedHandle(identity, handle)));
        LinkedHashSet<HandleIdentity> expanded = new LinkedHashSet<>();
        LinkedHashMap<HandleIdentity, ProcessHandle> observed = new LinkedHashMap<>();
        boolean limitReached = roots.truncated();
        IncompleteReason incompleteReason = roots.incompleteReason();
        Error failure = roots.failure();
        while (!pending.isEmpty() && deadline - System.nanoTime() > 0) {
            IndexedHandle parent = pending.removeFirst();
            if (!expanded.add(parent.identity())) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            DescendantScan children;
            try {
                children = collectStream(parent.handle().children(), Duration.ofNanos(remaining), deadlineReason);
            } catch (RuntimeException unavailable) {
                incompleteReason = IncompleteReason.UNAVAILABLE;
                continue;
            } catch (Error fatal) {
                incompleteReason = IncompleteReason.UNAVAILABLE;
                failure = appendFailure(failure, fatal);
                break;
            }
            limitReached |= children.truncated();
            incompleteReason = combine(incompleteReason, children.incompleteReason());
            failure = appendFailure(failure, children.failure());
            for (Map.Entry<HandleIdentity, ProcessHandle> child :
                    children.handlesByIdentity().entrySet()) {
                HandleIdentity childIdentity = child.getKey();
                if (observed.containsKey(childIdentity)) {
                    continue;
                }
                if (observed.size() == descendantLimit) {
                    limitReached = true;
                    break;
                }
                observed.put(childIdentity, child.getValue());
                pending.addLast(new IndexedHandle(childIdentity, child.getValue()));
            }
            if (children.failure() != null) {
                break;
            }
        }
        if (!pending.isEmpty()) {
            incompleteReason = combine(incompleteReason, deadlineReason);
        }
        return DescendantScan.observed(observed, limitReached, incompleteReason, failure);
    }

    private DescendantScan collectStream(
            Stream<ProcessHandle> stream, Duration timeout, IncompleteReason deadlineReason) {
        Objects.requireNonNull(stream, "process handle stream");
        long deadline = DurationSupport.deadlineFromNow(timeout);
        LinkedHashMap<HandleIdentity, ProcessHandle> observed = new LinkedHashMap<>();
        boolean exhausted = false;
        boolean limitReached = false;
        Throwable traversalFailure = null;
        try {
            Iterator<ProcessHandle> iterator = stream.iterator();
            while (deadline - System.nanoTime() > 0) {
                if (!iterator.hasNext()) {
                    exhausted = true;
                    break;
                }
                ProcessHandle handle = Objects.requireNonNull(iterator.next(), "process descendant");
                HandleIdentity identity = identity(handle);
                if (observed.containsKey(identity)) {
                    continue;
                }
                if (observed.size() == descendantLimit) {
                    limitReached = true;
                    break;
                }
                observed.put(identity, handle);
            }
        } catch (RuntimeException | Error failure) {
            traversalFailure = failure;
        }
        RuntimeException closeUnavailable = null;
        Error closeFailure = null;
        try {
            stream.close();
        } catch (RuntimeException failure) {
            closeUnavailable = failure;
        } catch (Error failure) {
            closeFailure = failure;
        }
        Error fatalFailure = traversalFailure instanceof Error error ? error : closeFailure;
        if (fatalFailure != null) {
            List<Throwable> failures = new ArrayList<>(3);
            addIfPresent(failures, traversalFailure);
            addIfPresent(failures, closeUnavailable);
            addIfPresent(failures, closeFailure);
            fatalFailure = (Error) FailureAggregation.combineWithPrimary(
                    fatalFailure, failures, "Process descendant stream traversal and cleanup failed");
        }
        IncompleteReason incompleteReason = traversalFailure != null || closeUnavailable != null || fatalFailure != null
                ? IncompleteReason.UNAVAILABLE
                : exhausted ? null : deadlineReason;
        return DescendantScan.observed(observed, limitReached, incompleteReason, fatalFailure);
    }

    private ScanBudget scanBudget(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative()) {
            throw new IllegalArgumentException("budget must not be negative");
        }
        boolean callerLimited = budget.compareTo(scanTimeout) <= 0;
        return new ScanBudget(callerLimited ? budget : scanTimeout, callerLimited);
    }

    private static Duration remainingBudget(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private static IncompleteReason incompleteReason(
            ProcessScanOperationOwner.Result.Failure failure, ScanBudget budget) {
        return switch (failure) {
            case DEADLINE -> budget.deadlineReason();
            case INTERRUPTED -> IncompleteReason.INTERRUPTED;
            case UNAVAILABLE -> IncompleteReason.UNAVAILABLE;
            case NONE -> throw new IllegalArgumentException("completed operation has no incomplete reason");
        };
    }

    private static IncompleteReason combine(IncompleteReason first, IncompleteReason second) {
        if (first == IncompleteReason.UNAVAILABLE || second == IncompleteReason.UNAVAILABLE) {
            return IncompleteReason.UNAVAILABLE;
        }
        if (first == IncompleteReason.INTERRUPTED || second == IncompleteReason.INTERRUPTED) {
            return IncompleteReason.INTERRUPTED;
        }
        return first != null ? first : second;
    }

    private static Error appendFailure(Error primary, Error next) {
        if (primary == null) {
            return next;
        }
        return (Error) FailureAggregation.combine(primary, next, "Multiple process-tree provider failures");
    }

    private static void addIfPresent(List<Throwable> failures, Throwable failure) {
        if (failure != null) {
            failures.add(failure);
        }
    }

    static HandleIdentity identity(ProcessHandle handle) {
        long pid = handle.pid();
        Instant started = null;
        try {
            started = handle.info().startInstant().orElse(null);
        } catch (RuntimeException ignored) {
            // PID identity is the bounded fallback when process metadata is unavailable.
        }
        return new HandleIdentity(pid, started);
    }

    private record RootHandles(
            Map<HandleIdentity, ProcessHandle> handlesByIdentity,
            boolean truncated,
            IncompleteReason incompleteReason,
            Error failure) {

        private RootHandles {
            handlesByIdentity = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(handlesByIdentity));
        }
    }

    private record IndexedHandle(HandleIdentity identity, ProcessHandle handle) {}

    private record ScanBudget(Duration timeout, boolean callerLimited) {

        private IncompleteReason deadlineReason() {
            return callerLimited ? IncompleteReason.CALLER_DEADLINE : IncompleteReason.UNAVAILABLE;
        }
    }

    record DescendantScan(
            Map<HandleIdentity, ProcessHandle> handlesByIdentity,
            Status status,
            IncompleteReason incompleteReason,
            Error failure) {

        DescendantScan {
            handlesByIdentity = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(handlesByIdentity));
            status = Objects.requireNonNull(status, "status");
            if ((status == Status.INCOMPLETE) != (incompleteReason != null)) {
                throw new IllegalArgumentException("incompleteReason must be present exactly for INCOMPLETE scans");
            }
            if (status == Status.COMPLETE && failure != null) {
                throw new IllegalArgumentException("complete scan must not carry a failure");
            }
        }

        Set<ProcessHandle> handles() {
            return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(handlesByIdentity.values()));
        }

        boolean complete() {
            return status == Status.COMPLETE;
        }

        boolean truncated() {
            return status == Status.LIMIT_REACHED;
        }

        boolean incomplete() {
            return status == Status.INCOMPLETE;
        }

        void rethrowFailure() {
            if (failure != null) {
                throw failure;
            }
        }

        private static DescendantScan observed(
                Map<HandleIdentity, ProcessHandle> handles, boolean limitReached, IncompleteReason incompleteReason) {
            return observed(handles, limitReached, incompleteReason, null);
        }

        private static DescendantScan observed(
                Map<HandleIdentity, ProcessHandle> handles,
                boolean limitReached,
                IncompleteReason incompleteReason,
                Error failure) {
            if (failure != null) {
                incompleteReason = combine(incompleteReason, IncompleteReason.UNAVAILABLE);
            }
            Status status = limitReached
                    ? Status.LIMIT_REACHED
                    : incompleteReason != null ? Status.INCOMPLETE : Status.COMPLETE;
            return new DescendantScan(handles, status, status == Status.INCOMPLETE ? incompleteReason : null, failure);
        }

        static DescendantScan incomplete(IncompleteReason reason) {
            return incomplete(reason, null);
        }

        private static DescendantScan incomplete(IncompleteReason reason, Error failure) {
            return new DescendantScan(Map.of(), Status.INCOMPLETE, Objects.requireNonNull(reason, "reason"), failure);
        }

        private static DescendantScan limitReached() {
            return limitReached(null);
        }

        private static DescendantScan limitReached(Error failure) {
            return new DescendantScan(Map.of(), Status.LIMIT_REACHED, null, failure);
        }

        enum Status {
            COMPLETE,
            LIMIT_REACHED,
            INCOMPLETE
        }
    }

    enum IncompleteReason {
        CALLER_DEADLINE,
        INTERRUPTED,
        UNAVAILABLE
    }

    record HandleIdentity(long pid, Instant startInstant) {}
}

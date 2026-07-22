/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class PtyProcessCleanup {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofMillis(250);
    private static final Duration NORMAL_TERMINATION_GRACE = Duration.ofMillis(50);
    private static final int MAX_DESCENDANTS = 256;
    private static final int DISCOVERY_PASSES = 2;

    private PtyProcessCleanup() {}

    static CleanupResult terminate(Process process) {
        return terminate(process, Process::descendants, CLEANUP_TIMEOUT, MAX_DESCENDANTS);
    }

    static CleanupResult terminate(
            Process process, DescendantSource descendantSource, Duration timeout, int maxDescendants) {
        if (process == null) {
            return new CleanupResult(0, false);
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(CLEANUP_TIMEOUT) > 0) {
            throw new IllegalArgumentException("cleanup timeout must be between 1 ns and 250 ms");
        }
        if (maxDescendants <= 0 || maxDescendants > MAX_DESCENDANTS) {
            throw new IllegalArgumentException("maxDescendants must be between 1 and 256");
        }

        long timeoutNanos = timeout.toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        long forceReserve = Math.min(NORMAL_TERMINATION_GRACE.toNanos(), Math.max(1L, timeoutNanos / 4L));
        long discoveryDeadline = deadline - forceReserve;
        Set<ProcessHandle> known = Collections.newSetFromMap(new IdentityHashMap<>());
        DiscoveryState discovery = new DiscoveryState(maxDescendants);
        destroy(process, false);
        try {
            for (int pass = 0;
                    pass < DISCOVERY_PASSES && discovery.hasCapacity() && before(discoveryDeadline);
                    pass++) {
                discover(process, descendantSource, known, discovery, discoveryDeadline);
                if (pass == 0 && discovery.hasCapacity() && before(discoveryDeadline)) {
                    waitFor(process, discoveryDeadline);
                }
            }

            if (process.isAlive() && before(deadline)) {
                destroy(process, true);
            }
            for (ProcessHandle descendant : known) {
                if (!before(deadline)) {
                    discovery.truncate();
                    break;
                }
                destroy(descendant, true);
            }
            if (process.isAlive() && before(deadline)) {
                waitFor(process, deadline);
            }
        } finally {
            closeStreams(process);
        }
        return new CleanupResult(discovery.discoveredCount(), discovery.truncated() || !before(deadline));
    }

    static void closeStreams(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process, StreamKind.STDIN);
        closeQuietly(process, StreamKind.STDOUT);
        closeQuietly(process, StreamKind.STDERR);
    }

    private static void discover(
            Process process,
            DescendantSource descendantSource,
            Set<ProcessHandle> known,
            DiscoveryState discovery,
            long deadline) {
        try (Stream<ProcessHandle> descendants = descendantSource.descendants(process)) {
            Iterator<ProcessHandle> iterator = descendants.iterator();
            while (discovery.hasCapacity() && before(deadline) && iterator.hasNext()) {
                ProcessHandle descendant = iterator.next();
                discovery.recordObservation();
                if (descendant != null && known.add(descendant)) {
                    destroy(descendant, false);
                }
            }
            if (!discovery.hasCapacity() || !before(deadline)) {
                discovery.truncate();
            }
        } catch (RuntimeException ignored) {
            discovery.truncate();
        }
    }

    private static void destroy(Process process, boolean forcibly) {
        try {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup after a launch or capability-probe failure.
        }
    }

    private static void destroy(ProcessHandle handle, boolean forcibly) {
        try {
            if (forcibly && handle.isAlive()) {
                handle.destroyForcibly();
            } else if (!forcibly) {
                handle.destroy();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup after a launch or capability-probe failure.
        }
    }

    private static void waitFor(Process process, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        long waitNanos = Math.min(remaining, NORMAL_TERMINATION_GRACE.toNanos());
        try {
            process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean before(long deadline) {
        return deadline - System.nanoTime() > 0;
    }

    private static void closeQuietly(Process process, StreamKind streamKind) {
        try {
            Closeable stream =
                    switch (streamKind) {
                        case STDIN -> process.getOutputStream();
                        case STDOUT -> process.getInputStream();
                        case STDERR -> process.getErrorStream();
                    };
            stream.close();
        } catch (IOException | RuntimeException ignored) {
            // A primary launch/probe failure already owns the outcome.
        }
    }

    @FunctionalInterface
    interface DescendantSource {

        Stream<ProcessHandle> descendants(Process process);
    }

    record CleanupResult(int discoveredCount, boolean truncated) {}

    private enum StreamKind {
        STDIN,
        STDOUT,
        STDERR
    }

    private static final class DiscoveryState {

        private final int maximum;
        private int discoveredCount;
        private boolean truncated;

        private DiscoveryState(int maximum) {
            this.maximum = maximum;
        }

        private void recordObservation() {
            discoveredCount++;
        }

        private boolean hasCapacity() {
            return discoveredCount < maximum;
        }

        private int discoveredCount() {
            return discoveredCount;
        }

        private void truncate() {
            truncated = true;
        }

        private boolean truncated() {
            return truncated;
        }
    }
}

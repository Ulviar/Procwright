/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class ProcessLifecycle {

    private ProcessLifecycle() {}

    public static Process start(LaunchPlan plan) {
        return start(plan, StdioConfig.pipes());
    }

    public static Process start(LaunchPlan plan, StdioConfig stdio) {
        Objects.requireNonNull(stdio, "stdio");
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        plan.workingDirectory().ifPresent(path -> builder.directory(path.toFile()));
        if (plan.environmentPolicy() == EnvironmentPolicy.CLEAN) {
            builder.environment().clear();
        }
        builder.environment().putAll(plan.environment());
        builder.redirectErrorStream(plan.outputMode() == OutputMode.MERGED);
        builder.redirectInput(stdio.stdin());
        builder.redirectOutput(stdio.stdout());
        builder.redirectError(stdio.stderr());

        try {
            return builder.start();
        } catch (IOException exception) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.LAUNCH_FAILED,
                    "Could not start command: " + CommandEchoSupport.redactedSummary(plan),
                    exception);
        }
    }

    /**
     * Waits for process completion.
     *
     * @param process watched process
     * @param timeout maximum wait, or {@link Duration#ZERO} to wait indefinitely
     * @return whether the process exited within the timeout
     */
    public static boolean waitFor(Process process, Duration timeout) {
        try {
            if (timeout.isZero()) {
                process.waitFor();
                return true;
            }
            return process.waitFor(DurationSupport.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyTreeForcibly(process, descendantsOf(process));
            throw new CommandExecutionException("Interrupted while waiting for command completion", exception);
        }
    }

    /**
     * Waits for process completion while periodically snapshotting live descendants.
     *
     * <p>Descendants of an exited process are no longer discoverable through {@link Process#descendants()} because the
     * operating system reparents them, so cleanup paths that may run after exit need a snapshot taken while the
     * process was still alive.
     *
     * <p>A {@link Duration#ZERO} timeout disables the deadline: the wait continues until the process exits on its
     * own. This is the single owner of the "zero means no timeout" semantics shared by run, idle, and stream
     * timeouts.
     *
     * @param process watched process
     * @param timeout maximum wait, or {@link Duration#ZERO} to wait indefinitely
     * @param descendantsSnapshot receives the most recent descendants snapshot taken while the process was alive
     * @return whether the process exited within the timeout
     */
    public static boolean waitFor(
            Process process, Duration timeout, AtomicReference<Set<ProcessHandle>> descendantsSnapshot) {
        Objects.requireNonNull(descendantsSnapshot, "descendantsSnapshot");
        boolean unbounded = timeout.isZero();
        long deadlineNanos = unbounded ? 0 : DurationSupport.deadlineFromNow(timeout);
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(100);
        try {
            while (true) {
                if (process.isAlive()) {
                    descendantsSnapshot.set(descendantsOf(process));
                }
                long remainingNanos = unbounded ? pollNanos : deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                if (process.waitFor(Math.min(remainingNanos, pollNanos), TimeUnit.NANOSECONDS)) {
                    return true;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Set<ProcessHandle> descendants = snapshotOrEmpty(descendantsSnapshot);
            descendants.addAll(descendantsOf(process));
            destroyTreeForcibly(process, descendants);
            throw new CommandExecutionException("Interrupted while waiting for command completion", exception);
        }
    }

    public static OptionalInt stop(Process process, ShutdownPolicy shutdownPolicy) {
        if (!process.isAlive()) {
            return OptionalInt.of(process.exitValue());
        }

        Set<ProcessHandle> descendants = descendantsOf(process);
        process.destroy();
        destroyDescendants(descendants);
        closeStdinQuietly(process);
        if (waitForTree(process, descendants, shutdownPolicy.interruptGrace())) {
            return OptionalInt.of(process.exitValue());
        }

        descendants.addAll(descendantsOf(process));
        destroyTreeForcibly(process, descendants);
        closeStdinQuietly(process);
        if (waitForTree(process, descendants, shutdownPolicy.killGrace())) {
            return OptionalInt.of(process.exitValue());
        }

        throw new CommandExecutionException("Command did not exit after forceful termination");
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
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        for (ProcessHandle known : knownDescendants) {
            if (known.isAlive()) {
                descendants.add(known);
                descendants.addAll(descendantsOf(known));
            }
        }
        descendants.addAll(descendantsOf(process));
        if (!process.isAlive() && descendants.isEmpty()) {
            return;
        }
        destroyTreeForcibly(process, descendants);
        closeStdinQuietly(process);
        if (!waitForTree(process, descendants, timeout)) {
            throw new CommandExecutionException("Command did not exit during forceful cleanup");
        }
    }

    public static void closeStdinQuietly(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Process cleanup is already on a terminal path.
        }
    }

    public static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Process cleanup is already on a terminal path.
        }
    }

    private static Set<ProcessHandle> descendantsOf(Process process) {
        try {
            return process.descendants().collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (UnsupportedOperationException exception) {
            return new LinkedHashSet<>();
        }
    }

    private static Set<ProcessHandle> descendantsOf(ProcessHandle handle) {
        try {
            return handle.descendants().collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (UnsupportedOperationException exception) {
            return new LinkedHashSet<>();
        }
    }

    private static Set<ProcessHandle> snapshotOrEmpty(AtomicReference<Set<ProcessHandle>> snapshot) {
        Set<ProcessHandle> handles = snapshot.get();
        return handles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(handles);
    }

    private static void destroyDescendants(Set<ProcessHandle> descendants) {
        for (ProcessHandle descendant : reverse(descendants)) {
            destroyQuietly(descendant);
        }
    }

    private static void destroyTreeForcibly(Process process, Set<ProcessHandle> descendants) {
        for (ProcessHandle descendant : reverse(descendants)) {
            destroyForciblyQuietly(descendant);
        }
        process.destroyForcibly();
    }

    private static List<ProcessHandle> reverse(Set<ProcessHandle> handles) {
        java.util.ArrayList<ProcessHandle> ordered = new java.util.ArrayList<>(handles);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) {
                handle.destroy();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // The final liveness check reports cleanup failure if the process remains alive.
        }
    }

    private static void destroyForciblyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // The final liveness check reports cleanup failure if the process remains alive.
        }
    }

    private static boolean waitForTree(Process process, Set<ProcessHandle> descendants, Duration timeout) {
        long deadline = DurationSupport.deadlineFromNow(timeout);
        while (process.isAlive() || alive(descendants)) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10), remainingNanos));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                destroyTreeForcibly(process, descendants);
                throw new CommandExecutionException("Interrupted while waiting for command cleanup", exception);
            }
        }
        return true;
    }

    private static boolean alive(Set<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                return true;
            }
        }
        return false;
    }
}

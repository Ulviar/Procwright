package com.github.ulviar.icli.internal;

import com.github.ulviar.icli.command.CommandExecutionException;
import com.github.ulviar.icli.command.EnvironmentPolicy;
import com.github.ulviar.icli.command.OutputMode;
import com.github.ulviar.icli.command.ShutdownPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class ProcessLifecycle {

    private ProcessLifecycle() {}

    public static Process start(LaunchPlan plan) {
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        plan.workingDirectory().ifPresent(path -> builder.directory(path.toFile()));
        if (plan.environmentPolicy() == EnvironmentPolicy.CLEAN) {
            builder.environment().clear();
        }
        builder.environment().putAll(plan.environment());
        builder.redirectErrorStream(plan.outputMode() == OutputMode.MERGED);

        try {
            return builder.start();
        } catch (IOException exception) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.LAUNCH_FAILED,
                    "Could not start command: " + CommandEchoSupport.redactedSummary(plan),
                    exception);
        }
    }

    public static boolean waitFor(Process process, Duration timeout) {
        try {
            return process.waitFor(DurationSupport.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyTreeForcibly(process, descendantsOf(process));
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
        if (!process.isAlive()) {
            return;
        }
        Set<ProcessHandle> descendants = descendantsOf(process);
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

package com.github.ulviar.icli;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

final class ProcessLifecycle {

    private ProcessLifecycle() {}

    static Process start(LaunchPlan plan) {
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        plan.workingDirectory().ifPresent(path -> builder.directory(path.toFile()));
        builder.environment().putAll(plan.environment());
        builder.redirectErrorStream(plan.outputMode() == OutputMode.MERGED);

        try {
            return builder.start();
        } catch (IOException exception) {
            throw new CommandExecutionException(
                    "Could not start command: " + String.join(" ", plan.command()), exception);
        }
    }

    static boolean waitFor(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CommandExecutionException("Interrupted while waiting for command completion", exception);
        }
    }

    static OptionalInt stop(Process process, ShutdownPolicy shutdownPolicy) {
        if (!process.isAlive()) {
            return OptionalInt.of(process.exitValue());
        }

        process.destroy();
        closeStdinQuietly(process);
        if (waitFor(process, shutdownPolicy.interruptGrace())) {
            return OptionalInt.of(process.exitValue());
        }

        process.destroyForcibly();
        closeStdinQuietly(process);
        if (waitFor(process, shutdownPolicy.killGrace())) {
            return OptionalInt.of(process.exitValue());
        }

        throw new CommandExecutionException("Command did not exit after forceful termination");
    }

    static void closeStdinQuietly(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Process cleanup is already on a terminal path.
        }
    }

    static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Process cleanup is already on a terminal path.
        }
    }
}

package com.github.ulviar.icli;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ProcessKernel {

    private ProcessKernel() {}

    static CommandResult run(ExecutionPlan plan) {
        Instant started = Instant.now();
        Process process = start(plan);

        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("icli-output-pump-", 0).factory());
        try {
            Future<CapturedOutput> stdout =
                    executor.submit(() -> CapturedOutput.capture(process.getInputStream(), plan.capturePolicy()));
            Future<CapturedOutput> stderr = plan.outputMode() == OutputMode.MERGED
                    ? null
                    : executor.submit(() -> CapturedOutput.capture(process.getErrorStream(), plan.capturePolicy()));

            try {
                closeStdin(process);

                boolean timedOut = !waitFor(process, plan.timeout());
                OptionalInt exitCode =
                        timedOut ? stopTimedOut(process, plan.shutdownPolicy()) : OptionalInt.of(process.exitValue());

                CapturedOutput stdoutOutput = await(stdout);
                CapturedOutput stderrOutput = stderr == null ? CapturedOutput.empty() : await(stderr);

                return new CommandResult(
                        exitCode,
                        decode(stdoutOutput, plan),
                        decode(stderrOutput, plan),
                        stdoutOutput.truncated(),
                        stderrOutput.truncated(),
                        timedOut,
                        Duration.between(started, Instant.now()));
            } catch (RuntimeException exception) {
                process.destroyForcibly();
                closeStreams(process);
                throw exception;
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Process start(ExecutionPlan plan) {
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

    private static void closeStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException exception) {
            throw new CommandExecutionException("Could not close command stdin", exception);
        }
    }

    private static boolean waitFor(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CommandExecutionException("Interrupted while waiting for command completion", exception);
        }
    }

    private static OptionalInt stopTimedOut(Process process, ShutdownPolicy shutdownPolicy) {
        process.destroy();
        if (waitFor(process, shutdownPolicy.interruptGrace())) {
            return OptionalInt.of(process.exitValue());
        }

        process.destroyForcibly();
        if (waitFor(process, shutdownPolicy.killGrace())) {
            return OptionalInt.of(process.exitValue());
        }

        throw new CommandExecutionException("Command did not exit after forceful termination");
    }

    private static CapturedOutput await(Future<CapturedOutput> output) {
        try {
            return output.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while capturing command output", exception);
        } catch (ExecutionException exception) {
            throw new CommandExecutionException("Could not capture command output", exception.getCause());
        }
    }

    private static String decode(CapturedOutput output, ExecutionPlan plan) {
        return new String(output.bytes(), plan.charset());
    }

    private static void closeStreams(Process process) {
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Process cleanup is already on an exceptional path.
        }
    }
}

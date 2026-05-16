package com.github.ulviar.icli;

import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ProcessKernel {

    private ProcessKernel() {}

    static CommandResult run(ExecutionPlan plan) {
        Instant started = Instant.now();
        Process process = ProcessLifecycle.start(plan.launchPlan());

        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("icli-output-pump-", 0).factory());
        try {
            Future<CapturedOutput> stdout =
                    executor.submit(() -> CapturedOutput.capture(process.getInputStream(), plan.capturePolicy()));
            Future<CapturedOutput> stderr = plan.outputMode() == OutputMode.MERGED
                    ? null
                    : executor.submit(() -> CapturedOutput.capture(process.getErrorStream(), plan.capturePolicy()));
            Future<?> stdin = executor.submit(() -> writeStdin(process, plan.stdin()));

            try {
                boolean timedOut = !waitFor(process, plan.timeout());
                OptionalInt exitCode =
                        timedOut ? stopTimedOut(process, plan.shutdownPolicy()) : OptionalInt.of(process.exitValue());

                if (timedOut) {
                    stdin.cancel(true);
                } else {
                    awaitStdin(stdin);
                }

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

    private static void writeStdin(Process process, StdinPolicy stdin) {
        if (stdin.mode() == StdinPolicy.Mode.OPEN) {
            throw new CommandExecutionException("One-shot execution cannot keep stdin open");
        }

        try (OutputStream output = process.getOutputStream()) {
            if (stdin.mode() == StdinPolicy.Mode.INPUT) {
                output.write(stdin.input().copyBytes());
            }
        } catch (java.io.IOException exception) {
            if (process.isAlive()) {
                throw new CommandExecutionException("Could not write command stdin", exception);
            }
        }
    }

    private static boolean waitFor(Process process, Duration timeout) {
        return ProcessLifecycle.waitFor(process, timeout);
    }

    private static OptionalInt stopTimedOut(Process process, ShutdownPolicy shutdownPolicy) {
        return ProcessLifecycle.stop(process, shutdownPolicy);
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

    private static void awaitStdin(Future<?> input) {
        try {
            input.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while writing command stdin", exception);
        } catch (ExecutionException exception) {
            throw new CommandExecutionException("Could not write command stdin", exception.getCause());
        }
    }

    private static String decode(CapturedOutput output, ExecutionPlan plan) {
        return new String(output.bytes(), plan.charset());
    }

    private static void closeStreams(Process process) {
        ProcessLifecycle.closeStdinQuietly(process);
        ProcessLifecycle.closeQuietly(process.getInputStream());
        ProcessLifecycle.closeQuietly(process.getErrorStream());
    }
}

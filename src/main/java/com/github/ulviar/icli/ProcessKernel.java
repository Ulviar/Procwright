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
        Diagnostics diagnostics = Diagnostics.of(plan.diagnosticsOptions(), "run", CommandEcho.from(plan.launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        Process process;
        try {
            process = ProcessLifecycle.start(plan.launchPlan());
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_STARTED, Diagnostics.attributes("pid", Long.toString(process.pid())));
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    Diagnostics.attributes("error", exception.getClass().getName()));
            throw exception;
        }

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
                OptionalInt exitCode;
                if (timedOut) {
                    diagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
                    diagnostics.emit(
                            DiagnosticEventType.SHUTDOWN_REQUESTED, Diagnostics.attributes("reason", "timeout"));
                    exitCode = stopTimedOut(process, plan.shutdownPolicy());
                } else {
                    exitCode = OptionalInt.of(process.exitValue());
                }

                if (timedOut) {
                    stdin.cancel(true);
                } else {
                    awaitStdin(stdin);
                }

                CapturedOutput stdoutOutput = await(stdout);
                CapturedOutput stderrOutput = stderr == null ? CapturedOutput.empty() : await(stderr);
                if (stdoutOutput.truncated()) {
                    diagnostics.emit(
                            DiagnosticEventType.OUTPUT_TRUNCATED,
                            Diagnostics.attributes(
                                    "source",
                                    "stdout",
                                    "limitBytes",
                                    Integer.toString(plan.capturePolicy().byteLimit())));
                }
                if (stderrOutput.truncated()) {
                    diagnostics.emit(
                            DiagnosticEventType.OUTPUT_TRUNCATED,
                            Diagnostics.attributes(
                                    "source",
                                    "stderr",
                                    "limitBytes",
                                    Integer.toString(plan.capturePolicy().byteLimit())));
                }
                diagnostics.emit(DiagnosticEventType.PROCESS_EXITED, exitAttributes(exitCode, timedOut));

                return new CommandResult(
                        exitCode,
                        decode(stdoutOutput, plan),
                        decode(stderrOutput, plan),
                        stdoutOutput.truncated(),
                        stderrOutput.truncated(),
                        timedOut,
                        Duration.between(started, Instant.now()));
            } catch (RuntimeException exception) {
                diagnostics.emit(
                        DiagnosticEventType.PROCESS_FAILED,
                        Diagnostics.attributes("error", exception.getClass().getName()));
                if (process.isAlive()) {
                    diagnostics.emit(
                            DiagnosticEventType.SHUTDOWN_REQUESTED, Diagnostics.attributes("reason", "failure"));
                }
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

    private static java.util.Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }
}

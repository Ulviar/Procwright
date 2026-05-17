package com.github.ulviar.icli;

import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class ProcessKernel {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final ProcessKernel STANDARD = new ProcessKernel(process -> {});

    private final Consumer<Process> postStartHook;

    private ProcessKernel(Consumer<Process> postStartHook) {
        this.postStartHook = Objects.requireNonNull(postStartHook, "postStartHook");
    }

    static ProcessKernel standard() {
        return STANDARD;
    }

    static ProcessKernel withPostStartHook(Consumer<Process> hook) {
        return new ProcessKernel(hook);
    }

    CommandResult run(ExecutionPlan plan) {
        Instant started = Instant.now();
        Diagnostics diagnostics = Diagnostics.of(plan.diagnosticsOptions(), "run", CommandEcho.from(plan.launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        Process process;
        try {
            process = ProcessLifecycle.start(plan.launchPlan());
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    Diagnostics.attributes("error", exception.getClass().getName()));
            throw exception;
        }

        ExecutorService executor = null;
        RuntimeException primaryFailure = null;
        PendingResult pendingResult = null;
        try {
            postStartHook.accept(process);
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_STARTED, Diagnostics.attributes("pid", Long.toString(process.pid())));
            executor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("icli-output-pump-", 0).factory());
            Future<CapturedOutput> stdout =
                    executor.submit(() -> CapturedOutput.capture(process.getInputStream(), plan.capturePolicy()));
            Future<CapturedOutput> stderr = plan.outputMode() == OutputMode.MERGED
                    ? null
                    : executor.submit(() -> CapturedOutput.capture(process.getErrorStream(), plan.capturePolicy()));
            Future<?> stdin = executor.submit(() -> writeStdin(process, plan.stdin()));

            boolean timedOut = !waitFor(process, plan.timeout());
            OptionalInt exitCode;
            if (timedOut) {
                diagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
                diagnostics.emit(DiagnosticEventType.SHUTDOWN_REQUESTED, Diagnostics.attributes("reason", "timeout"));
                exitCode = stopTimedOut(process, plan.shutdownPolicy());
            } else {
                exitCode = OptionalInt.of(process.exitValue());
            }

            if (timedOut) {
                stdin.cancel(true);
            } else {
                awaitStdin(stdin);
            }

            CapturedOutput stdoutOutput = await(stdout, timedOut);
            CapturedOutput stderrOutput = stderr == null ? CapturedOutput.empty() : await(stderr, timedOut);
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

            pendingResult = new PendingResult(
                    exitCode,
                    stdoutOutput.bytes(),
                    stderrOutput.bytes(),
                    decode(stdoutOutput, plan),
                    decode(stderrOutput, plan),
                    stdoutOutput.truncated(),
                    stderrOutput.truncated(),
                    timedOut);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            emitSuppressed(
                    diagnostics,
                    DiagnosticEventType.PROCESS_FAILED,
                    Diagnostics.attributes("error", exception.getClass().getName()),
                    exception);
            if (process.isAlive()) {
                emitSuppressed(
                        diagnostics,
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        Diagnostics.attributes("reason", "failure"),
                        exception);
            }
            forceStopAfterFailure(process, exception);
            throw exception;
        } finally {
            if (executor != null) {
                executor.shutdownNow();
                CommandExecutionException cleanupFailure = awaitExecutorTermination(executor);
                if (cleanupFailure != null) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
        return pendingResult.toCommandResult(Duration.between(started, Instant.now()));
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

    private static CapturedOutput await(Future<CapturedOutput> output, boolean terminalShutdown) {
        try {
            return output.get(DurationSupport.saturatedMillis(CLEANUP_TIMEOUT), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while capturing command output", exception);
        } catch (TimeoutException exception) {
            output.cancel(true);
            throw new CommandExecutionException("Timed out while draining command output", exception);
        } catch (ExecutionException exception) {
            if (terminalShutdown) {
                CapturedOutput shutdownOutput = shutdownOutput(exception.getCause());
                if (shutdownOutput != null) {
                    return shutdownOutput;
                }
            }
            throw new CommandExecutionException("Could not capture command output", exception.getCause());
        }
    }

    private static void awaitStdin(Future<?> input) {
        try {
            input.get(DurationSupport.saturatedMillis(CLEANUP_TIMEOUT), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while writing command stdin", exception);
        } catch (TimeoutException exception) {
            input.cancel(true);
            throw new CommandExecutionException("Timed out while writing command stdin", exception);
        } catch (ExecutionException exception) {
            throw new CommandExecutionException("Could not write command stdin", exception.getCause());
        }
    }

    private static CommandExecutionException awaitExecutorTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(DurationSupport.saturatedMillis(CLEANUP_TIMEOUT), TimeUnit.MILLISECONDS)) {
                return new CommandExecutionException("Timed out while stopping command lifecycle tasks");
            }
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CommandExecutionException("Interrupted while stopping command lifecycle tasks", exception);
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

    private static void forceStopAfterFailure(Process process, RuntimeException primaryFailure) {
        try {
            ProcessLifecycle.forceStop(process, CLEANUP_TIMEOUT);
        } catch (RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        } finally {
            closeStreams(process);
        }
    }

    private static boolean isStreamClosed(Throwable throwable) {
        if (!(throwable instanceof java.io.IOException ioException)) {
            return false;
        }
        String message = ioException.getMessage();
        return "Stream Closed".equals(message) || "Stream closed".equals(message);
    }

    private static CapturedOutput shutdownOutput(Throwable throwable) {
        if (throwable instanceof CapturedOutput.PartialCaptureException partial && isStreamClosed(partial.getCause())) {
            return partial.output();
        }
        if (isStreamClosed(throwable)) {
            return CapturedOutput.empty();
        }
        return null;
    }

    private static void emitSuppressed(
            Diagnostics diagnostics,
            DiagnosticEventType type,
            java.util.Map<String, String> attributes,
            RuntimeException primaryFailure) {
        try {
            diagnostics.emit(type, attributes);
        } catch (RuntimeException diagnosticFailure) {
            primaryFailure.addSuppressed(diagnosticFailure);
        }
    }

    private static java.util.Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private record PendingResult(
            OptionalInt exitCode,
            byte[] stdoutBytes,
            byte[] stderrBytes,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean timedOut) {

        private CommandResult toCommandResult(Duration elapsed) {
            return new CommandResult(
                    exitCode,
                    stdoutBytes,
                    stderrBytes,
                    stdout,
                    stderr,
                    stdoutTruncated,
                    stderrTruncated,
                    timedOut,
                    elapsed);
        }
    }
}

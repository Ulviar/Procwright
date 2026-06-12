/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ProcessKernel {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final ProcessKernel STANDARD = new ProcessKernel(process -> {});

    private final Consumer<Process> postStartHook;

    private ProcessKernel(Consumer<Process> postStartHook) {
        this.postStartHook = Objects.requireNonNull(postStartHook, "postStartHook");
    }

    public static ProcessKernel standard() {
        return STANDARD;
    }

    public static ProcessKernel withPostStartHook(Consumer<Process> hook) {
        return new ProcessKernel(hook);
    }

    public CommandResult run(ExecutionPlan plan) {
        Instant started = Instant.now();
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                plan.diagnosticsOptions(), "run", () -> CommandEchoSupport.from(plan.launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        CapturePolicy.Bounded boundedCapture =
                plan.capturePolicy() instanceof CapturePolicy.Bounded bounded ? bounded : null;
        Process process;
        try {
            process = ProcessLifecycle.start(plan.launchPlan(), stdioConfig(plan));
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    DiagnosticEmitter.attributes("error", exception.getClass().getName()));
            throw exception;
        }

        ExecutorService executor = null;
        RuntimeException primaryFailure = null;
        PendingResult pendingResult = null;
        AtomicReference<Set<ProcessHandle>> liveDescendants = new AtomicReference<>();
        try {
            postStartHook.accept(process);
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_STARTED,
                    DiagnosticEmitter.attributes("pid", Long.toString(process.pid())));
            executor = Threading.newTaskExecutor("procwright-output-pump-");
            Future<CapturedOutput> stdout = boundedCapture == null
                    ? null
                    : executor.submit(() -> CapturedOutput.capture(process.getInputStream(), boundedCapture));
            Future<CapturedOutput> stderr = boundedCapture == null || plan.outputMode() == OutputMode.MERGED
                    ? null
                    : executor.submit(() -> CapturedOutput.capture(process.getErrorStream(), boundedCapture));
            Future<?> stdin = startStdinWriter(process, plan.stdin(), executor);

            boolean timedOut = !ProcessLifecycle.waitFor(process, plan.timeout(), liveDescendants);
            OptionalInt exitCode;
            if (timedOut) {
                diagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
                diagnostics.emit(
                        DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "timeout"));
                exitCode = stopTimedOut(process, plan.shutdownPolicy());
            } else {
                exitCode = OptionalInt.of(process.exitValue());
            }

            if (timedOut && stdin != null) {
                stdin.cancel(true);
            } else if (stdin != null) {
                awaitStdin(stdin);
            }

            CapturedOutput stdoutOutput = stdout == null ? CapturedOutput.empty() : await(stdout, timedOut, exitCode);
            CapturedOutput stderrOutput = stderr == null ? CapturedOutput.empty() : await(stderr, timedOut, exitCode);
            if (stdoutOutput.truncated()) {
                diagnostics.emit(
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        DiagnosticEmitter.attributes(
                                "source", "stdout", "limitBytes", Integer.toString(boundedCapture.byteLimit())));
            }
            if (stderrOutput.truncated()) {
                diagnostics.emit(
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        DiagnosticEmitter.attributes(
                                "source", "stderr", "limitBytes", Integer.toString(boundedCapture.byteLimit())));
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
                    DiagnosticEmitter.attributes("error", exception.getClass().getName()),
                    exception);
            if (process.isAlive()) {
                emitSuppressed(
                        diagnostics,
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        DiagnosticEmitter.attributes("reason", "failure"),
                        exception);
            }
            forceStopAfterFailure(process, knownDescendants(liveDescendants), exception);
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

    private static StdioConfig stdioConfig(ExecutionPlan plan) {
        return new StdioConfig(stdinRedirect(plan.stdin()), stdoutRedirect(plan), stderrRedirect(plan));
    }

    private static ProcessBuilder.Redirect stdinRedirect(StdinPolicy stdin) {
        if (stdin.mode() != StdinPolicy.Mode.INPUT) {
            return ProcessBuilder.Redirect.PIPE;
        }
        return stdin.input()
                .path()
                .map(path -> {
                    if (!java.nio.file.Files.isRegularFile(path)) {
                        throw new CommandExecutionException(
                                CommandExecutionException.Reason.LAUNCH_FAILED,
                                "Stdin source file does not exist or is not a regular file: " + path);
                    }
                    return ProcessBuilder.Redirect.from(path.toFile());
                })
                .orElse(ProcessBuilder.Redirect.PIPE);
    }

    private static ProcessBuilder.Redirect stdoutRedirect(ExecutionPlan plan) {
        if (plan.capturePolicy() instanceof CapturePolicy.Discard) {
            return ProcessBuilder.Redirect.DISCARD;
        }
        if (plan.capturePolicy() instanceof CapturePolicy.ToPath toPath) {
            return ProcessBuilder.Redirect.to(toPath.stdout().toFile());
        }
        return ProcessBuilder.Redirect.PIPE;
    }

    private static ProcessBuilder.Redirect stderrRedirect(ExecutionPlan plan) {
        // With OutputMode.MERGED the stderr redirect is ignored by ProcessBuilder.redirectErrorStream(true).
        if (plan.capturePolicy() instanceof CapturePolicy.Discard) {
            return ProcessBuilder.Redirect.DISCARD;
        }
        if (plan.capturePolicy() instanceof CapturePolicy.ToPath toPath) {
            return toPath.stderr()
                    .map(path -> ProcessBuilder.Redirect.to(path.toFile()))
                    .orElse(ProcessBuilder.Redirect.DISCARD);
        }
        return ProcessBuilder.Redirect.PIPE;
    }

    private static Future<?> startStdinWriter(Process process, StdinPolicy stdin, ExecutorService executor) {
        if (stdin.mode() == StdinPolicy.Mode.OPEN) {
            throw new CommandExecutionException("One-shot execution cannot keep stdin open");
        }
        if (stdin.mode() == StdinPolicy.Mode.CLOSED) {
            closeStdin(process);
            return null;
        }
        if (stdin.input().path().isPresent()) {
            // Stdin is redirected from the source file at the operating-system level; there is nothing to write.
            return null;
        }
        return executor.submit(() -> writeStdin(process, stdin));
    }

    private static void closeStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (java.io.IOException exception) {
            if (process.isAlive()) {
                throw new CommandExecutionException("Could not close command stdin", exception);
            }
        }
    }

    private static void writeStdin(Process process, StdinPolicy stdin) {
        try (OutputStream output = process.getOutputStream()) {
            output.write(stdin.input().copyBytes());
        } catch (java.io.IOException exception) {
            if (process.isAlive()) {
                throw new CommandExecutionException("Could not write command stdin", exception);
            }
        }
    }

    private static OptionalInt stopTimedOut(Process process, ShutdownPolicy shutdownPolicy) {
        return ProcessLifecycle.stop(process, shutdownPolicy);
    }

    private static CapturedOutput await(Future<CapturedOutput> output, boolean terminalShutdown, OptionalInt exitCode) {
        try {
            return output.get(DurationSupport.saturatedMillis(CLEANUP_TIMEOUT), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while capturing command output", exception);
        } catch (TimeoutException exception) {
            output.cancel(true);
            throw new CommandExecutionException(drainTimeoutMessage(terminalShutdown, exitCode), exception);
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

    private static String drainTimeoutMessage(boolean terminalShutdown, OptionalInt exitCode) {
        if (terminalShutdown || exitCode.isEmpty()) {
            return "Timed out while draining command output";
        }
        return "Timed out while draining command output: the process exited (code " + exitCode.getAsInt()
                + ") but its output pipe is still open - a descendant process that inherited stdout or stderr"
                + " may be holding it";
    }

    private static Set<ProcessHandle> knownDescendants(AtomicReference<Set<ProcessHandle>> liveDescendants) {
        Set<ProcessHandle> snapshot = liveDescendants.get();
        return snapshot == null ? Set.of() : snapshot;
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
        try {
            return plan.charsetPolicy().decode(output.bytes());
        } catch (CharacterCodingException exception) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.DECODE_ERROR,
                    "Could not decode command output with " + plan.charset().displayName(),
                    exception);
        }
    }

    private static void closeStreams(Process process) {
        ProcessLifecycle.closeStdinQuietly(process);
        ProcessLifecycle.closeQuietly(process.getInputStream());
        ProcessLifecycle.closeQuietly(process.getErrorStream());
    }

    private static void forceStopAfterFailure(
            Process process, Set<ProcessHandle> knownDescendants, RuntimeException primaryFailure) {
        try {
            ProcessLifecycle.forceStop(process, knownDescendants, CLEANUP_TIMEOUT);
        } catch (RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        } finally {
            closeStreams(process);
        }
    }

    private static CapturedOutput shutdownOutput(Throwable throwable) {
        // Called only after the process was force-stopped on timeout, so the shutdown state itself marks any pipe
        // IOException as the expected partial-output path. The JDK used to surface this as the literal "Stream
        // Closed"/"Stream closed" message; matching the state instead of the message text keeps recovery stable
        // across JDK wording changes.
        if (throwable instanceof CapturedOutput.PartialCaptureException partial
                && partial.getCause() instanceof java.io.IOException) {
            return partial.output();
        }
        if (throwable instanceof java.io.IOException) {
            return CapturedOutput.empty();
        }
        return null;
    }

    private static void emitSuppressed(
            DiagnosticEmitter diagnostics,
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

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Owns the mutable lifecycle and resources of one one-shot command execution. */
final class OneShotExecution {

    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutionPlan plan;
    private final ProcessKernel.Dependencies dependencies;
    private final long startedNanos;
    private final DiagnosticEmitter diagnostics;
    private final LiveDescendantSnapshot liveDescendants = new LiveDescendantSnapshot();
    private final FailureCollector asynchronousCloseFailures = new FailureCollector();
    private final Consumer<Throwable> recordCloseFailure = asynchronousCloseFailures::record;

    private OneShotIoPlan ioPlan;
    private OneShotIoTaskOwner.Reservation ioTasks;
    private Process process;
    private ProcessIoResources resources;
    private ExecutorService executor;
    private Future<CapturedOutput> stdoutCapture;
    private Future<CapturedOutput> stderrCapture;
    private OneShotIoTaskOwner.OwnedFuture<Void> stdinWriter;
    private PendingCapture pendingCapture;
    private Throwable primaryFailure;
    private boolean restoreInterrupt;

    OneShotExecution(ExecutionPlan plan, ProcessKernel.Dependencies dependencies) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        startedNanos = dependencies.nanoTime().getAsLong();
        diagnostics = DiagnosticEmitter.of(plan.diagnostics(), "run", () -> CommandEchoSupport.from(plan.launchPlan()));
    }

    CommandResult execute() {
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        prepareIo();
        startProcess();
        acquireResources();
        runStartedProcess();
        return assembleResult();
    }

    private void prepareIo() {
        try {
            ioPlan = OneShotIoPlan.resolve(plan);
            ioTasks = dependencies.ioTaskOwner().reserve(ioPlan.taskCount());
        } catch (RuntimeException | Error failure) {
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }

    private void startProcess() {
        try {
            process = dependencies.processStarter().start(plan.launchPlan(), ioPlan.stdio());
        } catch (RuntimeException | Error failure) {
            ioTasks.close();
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }

    private void acquireResources() {
        try {
            resources = ProcessIoResources.acquire(process, dependencies.closeDispatcher());
        } catch (RuntimeException | Error failure) {
            ioTasks.close();
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }

    private void runStartedProcess() {
        try {
            startLifecycle();
            OneShotTermination.Outcome outcome = awaitTerminalOutcome();
            ProcessCompletion completion = settleProcess(outcome);
            captureOutput(completion);
        } catch (RuntimeException | Error failure) {
            handleExecutionFailure(failure);
        } finally {
            cleanup();
        }
        if (primaryFailure != null) {
            diagnostics.emitProcessFailure(FailureAggregation.primary(primaryFailure));
            rethrow(primaryFailure);
        }
    }

    private void startLifecycle() {
        dependencies.postStartHook().accept(process);
        diagnostics.emit(
                DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", Long.toString(process.pid())));
        if (ioPlan.taskCount() > 0) {
            executor = Threading.newTaskExecutor("procwright-output-pump-");
        }
        stdoutCapture = ioPlan.capturesStdout()
                ? ioTasks.submit(
                        executor, () -> CapturedOutput.capture(resources.stdout().stream(), ioPlan.boundedCapture()))
                : null;
        stderrCapture = ioPlan.capturesStderr()
                ? ioTasks.submit(
                        executor, () -> CapturedOutput.capture(resources.stderr().stream(), ioPlan.boundedCapture()))
                : null;
        stdinWriter =
                startStdinWriter(resources.stdin(), ioPlan.stdinOperation(), executor, ioTasks, recordCloseFailure);
    }

    private OneShotTermination.Outcome awaitTerminalOutcome() {
        OneShotTermination termination = new OneShotTermination(process, plan.timeout(), liveDescendants);
        if (stdinWriter != null) {
            stdinWriter.onFailure(termination.stdinFailureHandler());
        }
        try {
            return termination.await();
        } catch (InterruptedException interruption) {
            restoreInterrupt = true;
            throw interruptedFailure(process, plan, liveDescendants, diagnostics, interruption);
        }
    }

    private ProcessCompletion settleProcess(OneShotTermination.Outcome outcome) {
        if (outcome instanceof OneShotTermination.StdinFailure stdinFailure) {
            throwStdinFailure(stdinFailure.failure());
        }
        boolean timedOut = outcome instanceof OneShotTermination.TimedOut;
        OptionalInt exitCode;
        if (timedOut) {
            diagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
            diagnostics.emit(DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "timeout"));
            resources.stdin().closeAsync("procwright-process-stdin-close-", recordCloseFailure);
            exitCode = stopTimedOutWithoutStdinClose(process, liveDescendants.sealForCleanup(), plan.shutdownPolicy());
        } else {
            exitCode = OptionalInt.of(process.exitValue());
        }
        if (stdinWriter != null) {
            stdinWriter.cancel(true);
        }
        return new ProcessCompletion(exitCode, timedOut);
    }

    private void captureOutput(ProcessCompletion completion) {
        CapturedOutput stdout = stdoutCapture == null
                ? CapturedOutput.empty()
                : awaitCapture(stdoutCapture, completion.timedOut(), completion.exitCode());
        CapturedOutput stderr = stderrCapture == null
                ? CapturedOutput.empty()
                : awaitCapture(stderrCapture, completion.timedOut(), completion.exitCode());
        emitTruncation("stdout", stdout);
        emitTruncation("stderr", stderr);
        pendingCapture = new PendingCapture(completion.exitCode(), stdout, stderr, completion.timedOut());
    }

    private void emitTruncation(String source, CapturedOutput output) {
        if (output.truncated()) {
            diagnostics.emit(
                    DiagnosticEventType.OUTPUT_TRUNCATED,
                    DiagnosticEmitter.attributes(
                            "source",
                            source,
                            "limitBytes",
                            Integer.toString(ioPlan.boundedCapture().byteLimit())));
        }
    }

    private void handleExecutionFailure(Throwable failure) {
        restoreInterrupt |= Thread.interrupted();
        primaryFailure = emitSecondary(
                diagnostics,
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                DiagnosticEmitter.attributes("reason", "failure"),
                failure);
        primaryFailure =
                forceStopAfterFailureWithoutStreamClose(process, liveDescendants.sealForCleanup(), primaryFailure);
    }

    private void cleanup() {
        try {
            try {
                resources.closeAllAsync(recordCloseFailure);
            } catch (RuntimeException | Error closeDispatchFailure) {
                primaryFailure = combineFailures(primaryFailure, closeDispatchFailure);
            }
            if (executor != null) {
                executor.shutdownNow();
                CommandExecutionException cleanupFailure =
                        awaitExecutorTermination(executor, dependencies.cleanupTimeout());
                if (cleanupFailure != null) {
                    primaryFailure = combineFailures(primaryFailure, cleanupFailure);
                }
            }
            Throwable streamCloseFailure = resources.awaitClose(dependencies.cleanupTimeout());
            primaryFailure = combineFailures(primaryFailure, asynchronousCloseFailures.failure());
            primaryFailure = combineFailures(primaryFailure, streamCloseFailure);
        } finally {
            ioTasks.close();
            restoreInterrupt |= Thread.interrupted();
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private CommandResult assembleResult() {
        PendingCapture capture = Objects.requireNonNull(pendingCapture, "completed command capture");
        Duration elapsed =
                DurationSupport.elapsed(startedNanos, dependencies.nanoTime().getAsLong());
        CommandResult result;
        try {
            result = OneShotResultAssembler.assemble(
                    capture.stdout(),
                    capture.stderr(),
                    plan.charsetPolicy(),
                    capture.exitCode(),
                    capture.timedOut(),
                    elapsed);
        } catch (RuntimeException | Error decodeFailure) {
            Throwable failure = emitSecondary(
                    diagnostics,
                    DiagnosticEventType.SHUTDOWN_REQUESTED,
                    DiagnosticEmitter.attributes("reason", "failure"),
                    decodeFailure);
            diagnostics.emitProcessFailure(failure);
            rethrow(failure);
            throw new AssertionError("unreachable");
        }
        try {
            diagnostics.emit(DiagnosticEventType.PROCESS_EXITED, exitAttributes(result.exitCode(), result.timedOut()));
        } catch (RuntimeException | Error diagnosticFailure) {
            diagnostics.emitProcessFailure(diagnosticFailure);
            throw diagnosticFailure;
        }
        return result;
    }

    private static OneShotIoTaskOwner.OwnedFuture<Void> startStdinWriter(
            ProcessStreamResource<OutputStream> output,
            OneShotIoPlan.StdinOperation stdin,
            ExecutorService executor,
            OneShotIoTaskOwner.Reservation ioTasks,
            Consumer<? super Throwable> closeFailureHandler) {
        return switch (stdin.action()) {
            case CLOSE -> {
                output.closeAsync("procwright-process-stdin-close-", closeFailureHandler);
                yield null;
            }
            case REDIRECT -> null;
            case WRITE ->
                ioTasks.submit(executor, () -> {
                    writeStdin(output, stdin, closeFailureHandler);
                    return null;
                });
        };
    }

    private static void writeStdin(
            ProcessStreamResource<OutputStream> output,
            OneShotIoPlan.StdinOperation stdin,
            Consumer<? super Throwable> closeFailureHandler) {
        Throwable primaryFailure = null;
        try {
            output.stream().write(stdin.writeInput().copyBytes());
        } catch (java.io.IOException exception) {
            primaryFailure = new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE, "Could not write command stdin", exception);
            throw (CommandExecutionException) primaryFailure;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                output.closeAsync("procwright-process-stdin-close-", closeFailureHandler);
            } catch (RuntimeException | Error closeDispatchFailure) {
                if (primaryFailure != null) {
                    rethrow(combineFailures(primaryFailure, closeDispatchFailure));
                }
                throw closeDispatchFailure;
            }
        }
    }

    private static OptionalInt stopTimedOutWithoutStdinClose(
            Process process, KnownDescendants knownDescendants, ShutdownPolicy shutdownPolicy) {
        return ProcessLifecycle.stop(process, knownDescendants, shutdownPolicy);
    }

    private static RuntimeException interruptedFailure(
            Process process,
            ExecutionPlan plan,
            LiveDescendantSnapshot liveDescendants,
            DiagnosticEmitter diagnostics,
            InterruptedException interruption) {
        CommandExecutionException failure =
                new CommandExecutionException("Interrupted while waiting for command completion", interruption);
        Throwable outcome = emitSecondary(
                diagnostics,
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                DiagnosticEmitter.attributes("reason", "interrupted"),
                failure);
        try {
            ProcessLifecycle.stop(process, liveDescendants.sealForCleanup(), plan.shutdownPolicy());
        } catch (RuntimeException | Error shutdownFailure) {
            outcome = combineFailures(outcome, shutdownFailure);
        }
        return executionFailure(outcome);
    }

    private static CapturedOutput awaitCapture(
            Future<CapturedOutput> output, boolean terminalShutdown, OptionalInt exitCode) {
        try {
            return output.get(DurationSupport.saturatedMillis(OUTPUT_DRAIN_TIMEOUT), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while capturing command output", exception);
        } catch (TimeoutException exception) {
            output.cancel(true);
            throw new CommandExecutionException(drainTimeoutMessage(terminalShutdown, exitCode), exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
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

    private static void throwStdinFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof CommandExecutionException commandFailure
                && commandFailure.reason() == CommandExecutionException.Reason.RUNTIME_FAILURE) {
            throw commandFailure;
        }
        throw new CommandExecutionException(
                CommandExecutionException.Reason.RUNTIME_FAILURE, "Could not write command stdin", failure);
    }

    private static CommandExecutionException awaitExecutorTermination(
            ExecutorService executor, Duration cleanupTimeout) {
        try {
            if (!executor.awaitTermination(DurationSupport.saturatedMillis(cleanupTimeout), TimeUnit.MILLISECONDS)) {
                return new CommandExecutionException("Timed out while stopping command lifecycle tasks");
            }
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CommandExecutionException("Interrupted while stopping command lifecycle tasks", exception);
        }
    }

    private static Throwable forceStopAfterFailureWithoutStreamClose(
            Process process, KnownDescendants knownDescendants, Throwable primaryFailure) {
        try {
            ProcessLifecycle.forceStop(process, knownDescendants, OUTPUT_DRAIN_TIMEOUT);
            return primaryFailure;
        } catch (RuntimeException | Error cleanupFailure) {
            return combineFailures(primaryFailure, cleanupFailure);
        }
    }

    private static CapturedOutput shutdownOutput(Throwable throwable) {
        // The process was force-stopped, so an I/O failure is an expected partial-output path.
        if (throwable instanceof CapturedOutput.PartialCaptureException partial
                && partial.getCause() instanceof java.io.IOException) {
            return partial.output();
        }
        if (throwable instanceof java.io.IOException) {
            return CapturedOutput.empty();
        }
        return null;
    }

    private static Throwable emitSecondary(
            DiagnosticEmitter diagnostics,
            DiagnosticEventType type,
            java.util.Map<String, String> attributes,
            Throwable primaryFailure) {
        try {
            diagnostics.emit(type, attributes);
            return primaryFailure;
        } catch (RuntimeException | Error diagnosticFailure) {
            return combineFailures(primaryFailure, diagnosticFailure);
        }
    }

    private static Throwable combineFailures(Throwable primary, Throwable secondary) {
        return FailureAggregation.combine(primary, secondary, "Command execution and cleanup both failed");
    }

    private static CommandExecutionException executionFailure(Throwable failure) {
        List<Throwable> sources = FailureAggregation.sources(failure);
        Throwable primary = FailureAggregation.primary(failure);
        if (sources.size() == 1 && primary instanceof CommandExecutionException executionFailure) {
            return executionFailure;
        }
        CommandExecutionException envelope;
        if (primary instanceof CommandExecutionException executionFailure) {
            envelope = executionFailure.reason() == CommandExecutionException.Reason.DECODE_ERROR
                    ? new CommandExecutionException(
                            executionFailure.reason(),
                            executionFailure.getMessage(),
                            executionFailure,
                            executionFailure.result().orElseThrow())
                    : new CommandExecutionException(
                            executionFailure.reason(), executionFailure.getMessage(), executionFailure);
        } else {
            envelope = new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE, "Command execution failed", primary);
        }
        for (Throwable source : sources) {
            if (source != primary) {
                envelope.addSuppressed(source);
            }
        }
        return envelope;
    }

    private static java.util.Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        throw executionFailure(failure);
    }

    private record ProcessCompletion(OptionalInt exitCode, boolean timedOut) {}

    private record PendingCapture(
            OptionalInt exitCode, CapturedOutput stdout, CapturedOutput stderr, boolean timedOut) {}

    private static final class FailureCollector {

        private java.util.ArrayList<Throwable> failures;

        private synchronized void record(Throwable candidate) {
            if (failures == null) {
                failures = new java.util.ArrayList<>(2);
            }
            failures.add(Objects.requireNonNull(candidate, "candidate"));
        }

        private Throwable failure() {
            List<Throwable> snapshot;
            synchronized (this) {
                if (failures == null) {
                    return null;
                }
                snapshot = List.copyOf(failures);
            }
            return FailureAggregation.combine(snapshot, "Multiple asynchronous process stream closes failed");
        }
    }
}

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

/** Owns the mutable lifecycle and resources of one one-shot command execution. */
final class OneShotExecution {

    private final ExecutionPlan plan;
    private final ProcessKernel.Dependencies dependencies;
    private final long startedNanos;
    private final OneShotDeadline deadline;
    private final DiagnosticEmitter diagnostics;
    private final LiveDescendantSnapshot liveDescendants = new LiveDescendantSnapshot();

    private OneShotIoPlan ioPlan;
    private OneShotIoTaskOwner.Reservation ioTasks;
    private Process process;
    private OwnedStreams resources;
    private ExecutorService executor;
    private OneShotIoTaskOwner.OwnedFuture<CapturedOutput> stdoutCapture;
    private OneShotIoTaskOwner.OwnedFuture<CapturedOutput> stderrCapture;
    private OneShotIoTaskOwner.OwnedFuture<Void> stdinWriter;
    private PendingCapture pendingCapture;
    private Throwable primaryFailure;
    private boolean restoreInterrupt;

    OneShotExecution(ExecutionPlan plan, ProcessKernel.Dependencies dependencies) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        startedNanos = dependencies.nanoTime().getAsLong();
        deadline = OneShotDeadline.start(plan.timeout());
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
            resources = OwnedStreams.acquire(process);
        } catch (RuntimeException | Error failure) {
            Throwable outcome = failure;
            try {
                ProcessLifecycle.forceStop(process, dependencies.cleanupTimeout());
            } catch (RuntimeException | Error cleanupFailure) {
                outcome = combineFailures(outcome, cleanupFailure);
            }
            ioTasks.close();
            diagnostics.emitProcessFailure(FailureAggregation.primary(outcome));
            if (outcome instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) outcome;
        }
    }

    private void runStartedProcess() {
        try {
            startLifecycle();
            OneShotSupervision.Signal signal = awaitSupervisionSignal();
            ProcessCompletion completion = settleProcess(signal);
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
        stdinWriter = startStdinWriter(resources.stdin(), ioPlan.stdinOperation(), executor, ioTasks);
    }

    private OneShotSupervision.Signal awaitSupervisionSignal() {
        OneShotSupervision supervision = new OneShotSupervision(process, deadline, liveDescendants);
        if (stdinWriter != null) {
            stdinWriter.onFailure(supervision.stdinFailureHandler());
        }
        if (stdoutCapture != null) {
            stdoutCapture.onFailure(supervision.outputFailureHandler());
        }
        if (stderrCapture != null) {
            stderrCapture.onFailure(supervision.outputFailureHandler());
        }
        try {
            return supervision.await();
        } catch (InterruptedException interruption) {
            restoreInterrupt = true;
            throw interruptedFailure(process, plan, liveDescendants, diagnostics, interruption);
        }
    }

    private ProcessCompletion settleProcess(OneShotSupervision.Signal signal) {
        if (signal instanceof OneShotSupervision.StdinFailure stdinFailure) {
            throwStdinFailure(stdinFailure.failure());
        }
        if (signal instanceof OneShotSupervision.OutputFailure outputFailure) {
            throw outputFailure(outputFailure.failure());
        }
        if (signal instanceof OneShotSupervision.TimedOut) {
            return settleTimeout(OptionalInt.empty());
        }
        OptionalInt exitCode = OptionalInt.of(process.exitValue());
        if (stdinWriter != null) {
            stdinWriter.cancel(true);
        }
        return new ProcessCompletion(exitCode, false);
    }

    private void captureOutput(ProcessCompletion completion) {
        CapturedOutput stdout = CapturedOutput.empty();
        CapturedOutput stderr = CapturedOutput.empty();
        boolean stdoutSettled = stdoutCapture == null;
        boolean stderrSettled = stderrCapture == null;
        try {
            if (!stdoutSettled) {
                stdout = awaitCaptureUntilDeadline(stdoutCapture, completion.timedOut());
                stdoutSettled = true;
            }
            if (!stderrSettled) {
                stderr = awaitCaptureUntilDeadline(stderrCapture, completion.timedOut());
                stderrSettled = true;
            }
        } catch (TimeoutException timeout) {
            completion = completion.timedOut() ? completion : settleTimeout(completion.exitCode());
            if (!stdoutSettled) {
                stdout = awaitCaptureAfterShutdown(stdoutCapture);
            }
            if (!stderrSettled) {
                stderr = awaitCaptureAfterShutdown(stderrCapture);
            }
        }
        emitTruncation("stdout", stdout);
        emitTruncation("stderr", stderr);
        pendingCapture = new PendingCapture(completion.exitCode(), stdout, stderr, completion.timedOut());
    }

    private ProcessCompletion settleTimeout(OptionalInt knownExitCode) {
        diagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
        diagnostics.emit(DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "timeout"));
        resources.stdin().close();
        OptionalInt stoppedExit = stopTimedOutProcess(process, liveDescendants.sealForCleanup(), plan.shutdownPolicy());
        if (stdinWriter != null) {
            stdinWriter.cancel(true);
        }
        return new ProcessCompletion(knownExitCode.isPresent() ? knownExitCode : stoppedExit, true);
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
        primaryFailure = forceStopAfterFailureWithoutStreamClose(
                process, liveDescendants.sealForCleanup(), dependencies.cleanupTimeout(), primaryFailure);
    }

    private void cleanup() {
        try {
            resources.closeAll();
            if (executor != null) {
                executor.shutdownNow();
                CommandExecutionException cleanupFailure =
                        awaitExecutorTermination(executor, dependencies.cleanupTimeout());
                if (cleanupFailure != null) {
                    primaryFailure = combineFailures(primaryFailure, cleanupFailure);
                }
            }
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
            OwnedStream<OutputStream> output,
            OneShotIoPlan.StdinOperation stdin,
            ExecutorService executor,
            OneShotIoTaskOwner.Reservation ioTasks) {
        return switch (stdin.action()) {
            case CLOSE -> {
                output.close();
                yield null;
            }
            case REDIRECT -> null;
            case WRITE ->
                ioTasks.submit(executor, () -> {
                    writeStdin(output, stdin);
                    return null;
                });
        };
    }

    private static void writeStdin(OwnedStream<OutputStream> output, OneShotIoPlan.StdinOperation stdin) {
        try {
            output.stream().write(stdin.writeInput().copyBytes());
        } catch (java.io.IOException exception) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.RUNTIME_FAILURE, "Could not write command stdin", exception);
        } finally {
            output.close();
        }
    }

    private static OptionalInt stopTimedOutProcess(
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

    private CapturedOutput awaitCaptureUntilDeadline(Future<CapturedOutput> output, boolean terminalShutdown)
            throws TimeoutException {
        try {
            return deadline.await(output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while capturing command output", exception);
        } catch (TimeoutException exception) {
            throw exception;
        } catch (ExecutionException exception) {
            return captureOutcome(exception, terminalShutdown);
        }
    }

    private CapturedOutput awaitCaptureAfterShutdown(Future<CapturedOutput> output) {
        try {
            return output.get(DurationSupport.saturatedMillis(dependencies.cleanupTimeout()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while capturing command output", exception);
        } catch (TimeoutException exception) {
            output.cancel(true);
            throw new CommandExecutionException("Timed out while draining command output after shutdown", exception);
        } catch (ExecutionException exception) {
            return captureOutcome(exception, true);
        }
    }

    private static CapturedOutput captureOutcome(ExecutionException exception, boolean terminalShutdown) {
        if (exception.getCause() instanceof Error error) {
            throw error;
        }
        if (terminalShutdown) {
            CapturedOutput shutdownOutput = shutdownOutput(exception.getCause());
            if (shutdownOutput != null) {
                return shutdownOutput;
            }
        }
        throw outputFailure(exception.getCause());
    }

    private static RuntimeException outputFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return new CommandExecutionException("Could not capture command output", failure);
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
            Process process, KnownDescendants knownDescendants, Duration cleanupTimeout, Throwable primaryFailure) {
        try {
            ProcessLifecycle.forceStop(process, knownDescendants, cleanupTimeout);
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
}

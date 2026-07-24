/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ProcessKernel {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final ProcessKernel STANDARD = new ProcessKernel(process -> {});

    private final Consumer<Process> postStartHook;
    private final ProcessStarter processStarter;
    private final BoundedCloseDispatcher closeDispatcher;
    private final Duration cleanupTimeout;
    private final OneShotIoTaskOwner ioTaskOwner;
    private final LongSupplier nanoTime;

    private ProcessKernel(Consumer<Process> postStartHook) {
        this(postStartHook, ProcessLauncher::start);
    }

    ProcessKernel(Consumer<Process> postStartHook, ProcessStarter processStarter) {
        this(postStartHook, processStarter, BoundedCloseDispatcher.shared());
    }

    ProcessKernel(
            Consumer<Process> postStartHook, ProcessStarter processStarter, BoundedCloseDispatcher closeDispatcher) {
        this(postStartHook, processStarter, closeDispatcher, CLEANUP_TIMEOUT);
    }

    ProcessKernel(
            Consumer<Process> postStartHook,
            ProcessStarter processStarter,
            BoundedCloseDispatcher closeDispatcher,
            Duration cleanupTimeout) {
        this(
                postStartHook,
                processStarter,
                closeDispatcher,
                cleanupTimeout,
                OneShotIoTaskOwner.shared(),
                System::nanoTime);
    }

    ProcessKernel(
            Consumer<Process> postStartHook,
            ProcessStarter processStarter,
            BoundedCloseDispatcher closeDispatcher,
            Duration cleanupTimeout,
            LongSupplier nanoTime) {
        this(postStartHook, processStarter, closeDispatcher, cleanupTimeout, OneShotIoTaskOwner.shared(), nanoTime);
    }

    ProcessKernel(
            Consumer<Process> postStartHook,
            ProcessStarter processStarter,
            BoundedCloseDispatcher closeDispatcher,
            Duration cleanupTimeout,
            OneShotIoTaskOwner ioTaskOwner) {
        this(postStartHook, processStarter, closeDispatcher, cleanupTimeout, ioTaskOwner, System::nanoTime);
    }

    ProcessKernel(
            Consumer<Process> postStartHook,
            ProcessStarter processStarter,
            BoundedCloseDispatcher closeDispatcher,
            Duration cleanupTimeout,
            OneShotIoTaskOwner ioTaskOwner,
            LongSupplier nanoTime) {
        this.postStartHook = Objects.requireNonNull(postStartHook, "postStartHook");
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.closeDispatcher = Objects.requireNonNull(closeDispatcher, "closeDispatcher");
        this.cleanupTimeout = Objects.requireNonNull(cleanupTimeout, "cleanupTimeout");
        this.ioTaskOwner = Objects.requireNonNull(ioTaskOwner, "ioTaskOwner");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (cleanupTimeout.isNegative() || cleanupTimeout.isZero()) {
            throw new IllegalArgumentException("cleanupTimeout must be positive");
        }
    }

    public static ProcessKernel standard() {
        return STANDARD;
    }

    public static ProcessKernel withPostStartHook(Consumer<Process> hook) {
        return new ProcessKernel(hook);
    }

    public CommandResult run(ExecutionPlan plan) {
        CaptureTargetValidator.validate(plan.capturePolicy());
        long startedNanos = nanoTime.getAsLong();
        DiagnosticEmitter diagnostics =
                DiagnosticEmitter.of(plan.diagnostics(), "run", () -> CommandEchoSupport.from(plan.launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        OneShotIoPlan ioPlan;
        OneShotIoTaskOwner.Reservation ioTasks;
        try {
            ioPlan = OneShotIoPlan.resolve(plan);
            ioTasks = ioTaskOwner.reserve(ioPlan.taskCount());
        } catch (RuntimeException | Error failure) {
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }
        Process process;
        try {
            process = processStarter.start(plan.launchPlan(), ioPlan.stdio());
        } catch (RuntimeException | Error exception) {
            ioTasks.close();
            diagnostics.emitProcessFailure(exception);
            throw exception;
        }

        ProcessIoResources resources;
        try {
            resources = ProcessIoResources.acquire(process, closeDispatcher);
        } catch (RuntimeException | Error failure) {
            ioTasks.close();
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }

        ExecutorService executor = null;
        Throwable primaryFailure = null;
        PendingCapture pendingCapture = null;
        boolean restoreInterrupt = false;
        LiveDescendantSnapshot liveDescendants = new LiveDescendantSnapshot();
        FailureCollector asynchronousCloseFailures = new FailureCollector();
        Consumer<Throwable> recordCloseFailure = asynchronousCloseFailures::record;
        try {
            postStartHook.accept(process);
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_STARTED,
                    DiagnosticEmitter.attributes("pid", Long.toString(process.pid())));
            if (ioPlan.taskCount() > 0) {
                executor = Threading.newTaskExecutor("procwright-output-pump-");
            }
            Future<CapturedOutput> stdout = !ioPlan.capturesStdout()
                    ? null
                    : ioTasks.submit(
                            executor,
                            () -> CapturedOutput.capture(resources.stdout().stream(), ioPlan.boundedCapture()));
            Future<CapturedOutput> stderr = !ioPlan.capturesStderr()
                    ? null
                    : ioTasks.submit(
                            executor,
                            () -> CapturedOutput.capture(resources.stderr().stream(), ioPlan.boundedCapture()));
            OneShotIoTaskOwner.OwnedFuture<Void> stdin =
                    startStdinWriter(resources.stdin(), ioPlan.stdinOperation(), executor, ioTasks, recordCloseFailure);

            OneShotTermination termination = new OneShotTermination(process, plan.timeout(), liveDescendants);
            if (stdin != null) {
                stdin.onFailure(termination.stdinFailureHandler());
            }
            OneShotTermination.Outcome terminalOutcome;
            try {
                terminalOutcome = termination.await();
            } catch (InterruptedException exception) {
                restoreInterrupt = true;
                throw interruptedFailure(process, plan, liveDescendants, diagnostics, exception);
            }
            if (terminalOutcome instanceof OneShotTermination.StdinFailure stdinFailure) {
                throwStdinFailure(stdinFailure.failure());
            }
            boolean timedOut = terminalOutcome instanceof OneShotTermination.TimedOut;
            OptionalInt exitCode;
            if (timedOut) {
                diagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
                diagnostics.emit(
                        DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "timeout"));
                resources.stdin().closeAsync("procwright-process-stdin-close-", recordCloseFailure);
                exitCode =
                        stopTimedOutWithoutStdinClose(process, liveDescendants.sealForCleanup(), plan.shutdownPolicy());
            } else {
                exitCode = OptionalInt.of(process.exitValue());
            }

            if (stdin != null) {
                stdin.cancel(true);
            }

            CapturedOutput stdoutOutput = stdout == null ? CapturedOutput.empty() : await(stdout, timedOut, exitCode);
            CapturedOutput stderrOutput = stderr == null ? CapturedOutput.empty() : await(stderr, timedOut, exitCode);
            if (stdoutOutput.truncated()) {
                diagnostics.emit(
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        DiagnosticEmitter.attributes(
                                "source",
                                "stdout",
                                "limitBytes",
                                Integer.toString(ioPlan.boundedCapture().byteLimit())));
            }
            if (stderrOutput.truncated()) {
                diagnostics.emit(
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        DiagnosticEmitter.attributes(
                                "source",
                                "stderr",
                                "limitBytes",
                                Integer.toString(ioPlan.boundedCapture().byteLimit())));
            }
            pendingCapture = new PendingCapture(exitCode, stdoutOutput, stderrOutput, timedOut);
        } catch (RuntimeException | Error exception) {
            primaryFailure = exception;
            boolean interruptedOnEntry = Thread.interrupted();
            restoreInterrupt =
                    restoreInterrupt || SuppressionSupport.containsInterruption(exception) || interruptedOnEntry;
            emitSuppressed(
                    diagnostics,
                    DiagnosticEventType.SHUTDOWN_REQUESTED,
                    DiagnosticEmitter.attributes("reason", "failure"),
                    exception);
            forceStopAfterFailureWithoutStreamClose(process, liveDescendants.sealForCleanup(), exception);
        } finally {
            try {
                try {
                    resources.closeAllAsync(recordCloseFailure);
                } catch (RuntimeException | Error closeDispatchFailure) {
                    primaryFailure = SuppressionSupport.combine(primaryFailure, closeDispatchFailure);
                }
                if (executor != null) {
                    executor.shutdownNow();
                    CommandExecutionException cleanupFailure = awaitExecutorTermination(executor, cleanupTimeout);
                    if (cleanupFailure != null) {
                        primaryFailure = SuppressionSupport.combine(primaryFailure, cleanupFailure);
                    }
                }
                Throwable streamCloseFailure = resources.awaitClose(cleanupTimeout);
                primaryFailure = SuppressionSupport.combine(primaryFailure, asynchronousCloseFailures.failure());
                primaryFailure = SuppressionSupport.combine(primaryFailure, streamCloseFailure);
            } finally {
                ioTasks.close();
                if (primaryFailure != null && SuppressionSupport.containsInterruption(primaryFailure)) {
                    restoreInterrupt = true;
                }
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (primaryFailure != null) {
            diagnostics.emitProcessFailure(primaryFailure);
            rethrow(primaryFailure);
        }
        Duration elapsed = DurationSupport.elapsed(startedNanos, nanoTime.getAsLong());
        CommandResult result;
        try {
            result = OneShotResultAssembler.assemble(
                    pendingCapture.stdout(),
                    pendingCapture.stderr(),
                    plan.charsetPolicy(),
                    pendingCapture.exitCode(),
                    pendingCapture.timedOut(),
                    elapsed);
        } catch (RuntimeException | Error decodeFailure) {
            emitSuppressed(
                    diagnostics,
                    DiagnosticEventType.SHUTDOWN_REQUESTED,
                    DiagnosticEmitter.attributes("reason", "failure"),
                    decodeFailure);
            diagnostics.emitProcessFailure(decodeFailure);
            throw decodeFailure;
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
            ProcessIoResources.Resource<OutputStream> output,
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
            ProcessIoResources.Resource<OutputStream> output,
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
                    SuppressionSupport.attach(primaryFailure, closeDispatchFailure);
                } else {
                    throw closeDispatchFailure;
                }
            }
        }
    }

    private static OptionalInt stopTimedOutWithoutStdinClose(
            Process process, KnownDescendants knownDescendants, ShutdownPolicy shutdownPolicy) {
        return ProcessLifecycle.stop(process, knownDescendants, shutdownPolicy);
    }

    private static CommandExecutionException interruptedFailure(
            Process process,
            ExecutionPlan plan,
            LiveDescendantSnapshot liveDescendants,
            DiagnosticEmitter diagnostics,
            InterruptedException interruption) {
        CommandExecutionException failure =
                new CommandExecutionException("Interrupted while waiting for command completion", interruption);
        emitSuppressed(
                diagnostics,
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                DiagnosticEmitter.attributes("reason", "interrupted"),
                failure);
        try {
            ProcessLifecycle.stop(process, liveDescendants.sealForCleanup(), plan.shutdownPolicy());
        } catch (RuntimeException | Error shutdownFailure) {
            SuppressionSupport.attach(failure, shutdownFailure);
        }
        return failure;
    }

    private static CapturedOutput await(Future<CapturedOutput> output, boolean terminalShutdown, OptionalInt exitCode) {
        try {
            return output.get(DurationSupport.saturatedMillis(CLEANUP_TIMEOUT), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
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

    static void forceStopAfterFailure(Process process, KnownDescendants knownDescendants, Throwable primaryFailure) {
        ProcessIoResources resources;
        try {
            resources = ProcessIoResources.acquire(process);
        } catch (RuntimeException | Error acquisitionFailure) {
            SuppressionSupport.attach(primaryFailure, acquisitionFailure);
            return;
        }
        forceStopAfterFailureWithoutStreamClose(process, knownDescendants, primaryFailure);
        try {
            resources.closeAllAsync(failure -> SuppressionSupport.attach(primaryFailure, failure));
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
    }

    private static void forceStopAfterFailureWithoutStreamClose(
            Process process, KnownDescendants knownDescendants, Throwable primaryFailure) {
        try {
            ProcessLifecycle.forceStop(process, knownDescendants, CLEANUP_TIMEOUT);
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
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
            Throwable primaryFailure) {
        try {
            diagnostics.emit(type, attributes);
        } catch (RuntimeException | Error diagnosticFailure) {
            SuppressionSupport.attach(primaryFailure, diagnosticFailure);
        }
    }

    private static java.util.Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("command lifecycle failure must be unchecked", failure);
    }

    private record PendingCapture(
            OptionalInt exitCode, CapturedOutput stdout, CapturedOutput stderr, boolean timedOut) {}

    private static final class FailureCollector {

        private Throwable failure;

        private synchronized void record(Throwable candidate) {
            failure = SuppressionSupport.combine(failure, Objects.requireNonNull(candidate, "candidate"));
        }

        private synchronized Throwable failure() {
            return failure;
        }
    }

    @FunctionalInterface
    interface ProcessStarter {

        Process start(LaunchPlan plan, StdioConfig stdio);
    }
}

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
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
        this(postStartHook, ProcessLifecycle::start);
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
        CapturePolicy.Bounded boundedCapture =
                plan.capturePolicy() instanceof CapturePolicy.Bounded bounded ? bounded : null;
        OneShotIoTaskOwner.Reservation ioTasks;
        try {
            ioTasks = ioTaskOwner.reserve(requiredIoTasks(plan, boundedCapture));
        } catch (RuntimeException | Error failure) {
            diagnostics.emitProcessFailure(failure);
            throw failure;
        }
        Process process;
        try {
            process = processStarter.start(plan.launchPlan(), stdioConfig(plan));
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
        AtomicReference<Set<ProcessHandle>> liveDescendants = new AtomicReference<>();
        FailureCollector asynchronousCloseFailures = new FailureCollector();
        Consumer<Throwable> recordCloseFailure = asynchronousCloseFailures::record;
        try {
            postStartHook.accept(process);
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_STARTED,
                    DiagnosticEmitter.attributes("pid", Long.toString(process.pid())));
            if (requiredIoTasks(plan, boundedCapture) > 0) {
                executor = Threading.newTaskExecutor("procwright-output-pump-");
            }
            Future<CapturedOutput> stdout = boundedCapture == null
                    ? null
                    : ioTasks.submit(
                            executor, () -> CapturedOutput.capture(resources.stdout().stream(), boundedCapture));
            Future<CapturedOutput> stderr = boundedCapture == null || plan.outputMode() == OutputMode.MERGED
                    ? null
                    : ioTasks.submit(
                            executor, () -> CapturedOutput.capture(resources.stderr().stream(), boundedCapture));
            OneShotIoTaskOwner.OwnedFuture<Void> stdin =
                    startStdinWriter(resources.stdin(), plan.stdin(), executor, ioTasks, recordCloseFailure);

            OneShotTerminalArbiter terminalArbiter = new OneShotTerminalArbiter();
            if (stdin != null) {
                stdin.actualCompletion().whenComplete((outcome, impossible) -> {
                    if (outcome != null && outcome.failure() != null) {
                        terminalArbiter.claim(new StdinFailure(outcome.failure()));
                    }
                });
            }
            OneShotOutcome terminalOutcome;
            try {
                terminalOutcome = awaitTerminal(process, plan.timeout(), liveDescendants, terminalArbiter);
            } catch (InterruptedException exception) {
                restoreInterrupt = true;
                throw interruptedFailure(process, plan, liveDescendants, diagnostics, exception);
            }
            if (terminalOutcome instanceof StdinFailure stdinFailure) {
                throwStdinFailure(stdinFailure.failure());
            }
            boolean timedOut = terminalOutcome instanceof TimedOut;
            OptionalInt exitCode;
            if (timedOut) {
                diagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
                diagnostics.emit(
                        DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "timeout"));
                resources.stdin().closeAsync("procwright-process-stdin-close-", recordCloseFailure);
                exitCode = stopTimedOutWithoutStdinClose(
                        process, knownDescendants(liveDescendants), plan.shutdownPolicy());
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
                                "source", "stdout", "limitBytes", Integer.toString(boundedCapture.byteLimit())));
            }
            if (stderrOutput.truncated()) {
                diagnostics.emit(
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        DiagnosticEmitter.attributes(
                                "source", "stderr", "limitBytes", Integer.toString(boundedCapture.byteLimit())));
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
            forceStopAfterFailureWithoutStreamClose(process, knownDescendants(liveDescendants), exception);
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
        PendingResult pendingResult;
        try {
            DecodedOutputs decoded = decodeCapturedOutputs(
                    pendingCapture.stdout(),
                    pendingCapture.stderr(),
                    plan,
                    pendingCapture.exitCode(),
                    pendingCapture.timedOut(),
                    elapsed);
            pendingResult = new PendingResult(
                    pendingCapture.exitCode(),
                    pendingCapture.stdout().bytes(),
                    pendingCapture.stderr().bytes(),
                    decoded.stdout(),
                    decoded.stderr(),
                    pendingCapture.stdout().truncated(),
                    pendingCapture.stderr().truncated(),
                    pendingCapture.timedOut());
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
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_EXITED,
                    exitAttributes(pendingResult.exitCode(), pendingResult.timedOut()));
        } catch (RuntimeException | Error diagnosticFailure) {
            diagnostics.emitProcessFailure(diagnosticFailure);
            throw diagnosticFailure;
        }
        return pendingResult.toCommandResult(elapsed);
    }

    private static int requiredIoTasks(ExecutionPlan plan, CapturePolicy.Bounded boundedCapture) {
        int taskCount = boundedCapture == null ? 0 : 1;
        if (boundedCapture != null && plan.outputMode() != OutputMode.MERGED) {
            taskCount++;
        }
        if (plan.stdin().mode() == StdinPolicy.Mode.INPUT
                && plan.stdin().input().path().isEmpty()) {
            taskCount++;
        }
        return taskCount;
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

    private static OneShotIoTaskOwner.OwnedFuture<Void> startStdinWriter(
            ProcessIoResources.Resource<OutputStream> output,
            StdinPolicy stdin,
            ExecutorService executor,
            OneShotIoTaskOwner.Reservation ioTasks,
            Consumer<? super Throwable> closeFailureHandler) {
        if (stdin.mode() == StdinPolicy.Mode.OPEN) {
            throw new CommandExecutionException("One-shot execution cannot keep stdin open");
        }
        if (stdin.mode() == StdinPolicy.Mode.CLOSED) {
            output.closeAsync("procwright-process-stdin-close-", closeFailureHandler);
            return null;
        }
        if (stdin.input().path().isPresent()) {
            // Stdin is redirected from the source file at the operating-system level; there is nothing to write.
            return null;
        }
        return ioTasks.submit(executor, () -> {
            writeStdin(output, stdin, closeFailureHandler);
            return null;
        });
    }

    private static void writeStdin(
            ProcessIoResources.Resource<OutputStream> output,
            StdinPolicy stdin,
            Consumer<? super Throwable> closeFailureHandler) {
        Throwable primaryFailure = null;
        try {
            output.stream().write(stdin.input().copyBytes());
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
            Process process, Set<ProcessHandle> knownDescendants, ShutdownPolicy shutdownPolicy) {
        return ProcessLifecycle.stopWithoutStdinClose(process, knownDescendants, shutdownPolicy);
    }

    private static CommandExecutionException interruptedFailure(
            Process process,
            ExecutionPlan plan,
            AtomicReference<Set<ProcessHandle>> liveDescendants,
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
            ProcessLifecycle.stopWithoutStdinClose(process, knownDescendants(liveDescendants), plan.shutdownPolicy());
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

    private static Set<ProcessHandle> knownDescendants(AtomicReference<Set<ProcessHandle>> liveDescendants) {
        Set<ProcessHandle> snapshot = liveDescendants.get();
        return snapshot == null ? Set.of() : snapshot;
    }

    private static OneShotOutcome awaitTerminal(
            Process process,
            Duration timeout,
            AtomicReference<Set<ProcessHandle>> liveDescendants,
            OneShotTerminalArbiter arbiter)
            throws InterruptedException {
        boolean unbounded = timeout.isZero();
        long deadlineNanos = unbounded ? 0 : DurationSupport.deadlineFromNow(timeout);
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(10);
        while (true) {
            OneShotOutcome selected = arbiter.outcome();
            if (selected != null) {
                return selected;
            }
            long remainingNanos = unbounded ? pollNanos : deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                arbiter.claim(new TimedOut());
                return arbiter.outcome();
            }
            try {
                boolean exited = ProcessLifecycle.waitFor(
                        process, Duration.ofNanos(Math.min(remainingNanos, pollNanos)), liveDescendants);
                if (exited) {
                    arbiter.claim(new ProcessExited());
                }
            } catch (InterruptedException interruption) {
                if (arbiter.outcome() != null) {
                    Thread.currentThread().interrupt();
                    return arbiter.outcome();
                }
                throw interruption;
            }
        }
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

    private static String decode(CapturedOutput output, ExecutionPlan plan) {
        try {
            if (output.truncated()) {
                return decodeTruncatedPrefix(output.bytes(), plan);
            }
            return OneShotTextDecoder.decode(output.bytes(), plan.charsetPolicy());
        } catch (CharacterCodingException | RuntimeException | CoderMalfunctionError exception) {
            throw new OutputDecodingException("Could not decode command output", exception);
        }
    }

    static DecodedOutputs decodeCapturedOutputs(
            CapturedOutput stdout,
            CapturedOutput stderr,
            ExecutionPlan plan,
            OptionalInt exitCode,
            boolean timedOut,
            Duration elapsed) {
        try {
            return new DecodedOutputs(decode(stdout, plan), decode(stderr, plan));
        } catch (OutputDecodingException decodeFailure) {
            CommandResult diagnosticResult = new CommandResult(
                    exitCode,
                    stdout.bytes(),
                    stderr.bytes(),
                    diagnosticText(stdout.bytes(), plan, decodeFailure.getCause()),
                    diagnosticText(stderr.bytes(), plan, decodeFailure.getCause()),
                    stdout.truncated(),
                    stderr.truncated(),
                    timedOut,
                    elapsed);
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.DECODE_ERROR,
                    decodeFailure.getMessage() + " with " + plan.charset().name(),
                    decodeFailure.getCause(),
                    diagnosticResult);
        }
    }

    private static String decodeTruncatedPrefix(byte[] bytes, ExecutionPlan plan) throws CharacterCodingException {
        int completePrefixLength = OneShotTextDecoder.completePrefixLength(bytes, plan.charsetPolicy());
        byte[] completePrefix =
                completePrefixLength == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, completePrefixLength);
        return OneShotTextDecoder.decode(completePrefix, plan.charsetPolicy());
    }

    static void forceStopAfterFailure(Process process, Set<ProcessHandle> knownDescendants, Throwable primaryFailure) {
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
            Process process, Set<ProcessHandle> knownDescendants, Throwable primaryFailure) {
        try {
            ProcessLifecycle.forceStopWithoutStdinClose(process, knownDescendants, CLEANUP_TIMEOUT);
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

    private static String diagnosticText(byte[] bytes, ExecutionPlan plan, Throwable decodeFailure) {
        if (decodeFailure instanceof CharacterCodingException) {
            try {
                return new String(bytes, plan.charset());
            } catch (RuntimeException | Error diagnosticFailure) {
                SuppressionSupport.attach(decodeFailure, diagnosticFailure);
            }
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
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

    private record PendingCapture(
            OptionalInt exitCode, CapturedOutput stdout, CapturedOutput stderr, boolean timedOut) {}

    record DecodedOutputs(String stdout, String stderr) {}

    private sealed interface OneShotOutcome permits ProcessExited, TimedOut, StdinFailure {}

    private record ProcessExited() implements OneShotOutcome {}

    private record TimedOut() implements OneShotOutcome {}

    private record StdinFailure(Throwable failure) implements OneShotOutcome {

        private StdinFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }

    private static final class OneShotTerminalArbiter {

        private final AtomicReference<OneShotOutcome> outcome = new AtomicReference<>();

        private boolean claim(OneShotOutcome candidate) {
            return outcome.compareAndSet(null, Objects.requireNonNull(candidate, "candidate"));
        }

        private OneShotOutcome outcome() {
            return outcome.get();
        }
    }

    private static final class FailureCollector {

        private Throwable failure;

        private synchronized void record(Throwable candidate) {
            failure = SuppressionSupport.combine(failure, Objects.requireNonNull(candidate, "candidate"));
        }

        private synchronized Throwable failure() {
            return failure;
        }
    }

    @SuppressWarnings("serial")
    private static final class OutputDecodingException extends RuntimeException {

        private OutputDecodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    interface ProcessStarter {

        Process start(LaunchPlan plan, StdioConfig stdio);
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderMalfunctionError;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Generic request/response workflow over an interactive process.
 *
 * <p>The two output streams have asymmetric backlog semantics: stdout is the protocol stream, so
 * unread stdout beyond the backlog limit is an immediate typed session failure. Unread diagnostic
 * stderr remains nonfatal, but if its bounded queue overflows, a later stderr read fails atomically
 * instead of exposing output after dropped bytes.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class DefaultProtocolSession<I extends Object, O extends Object> implements ProtocolSession<I, O> {

    private static final String OUTPUT_OWNER = "ProtocolSession";
    private static final int ZERO_READ_BACKOFF_STEPS = 8;
    private final DefaultSession session;
    private final ProtocolAdapter<I, O> adapter;
    private final ProtocolSessionSettings options;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final ProtocolTranscriptBuffer transcript;
    private final ProtocolOutputQueue stdout;
    private final ProtocolOutputQueue stderr;
    private final ProtocolTextDecoderState stdoutTextDecoder;
    private final ProtocolTextDecoderState stderrTextDecoder;
    private final ProtocolCallbackRunner callbackRunner;
    private final SerializedRequestGate requestGate;
    private final ProtocolSessionState state;
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private final BoundedTaskRunner.CancellationSignal callbackCancellation =
            new BoundedTaskRunner.CancellationSignal();

    public DefaultProtocolSession(
            DefaultSession session, ProtocolAdapter<I, O> adapter, ProtocolSessionSettings options) {
        this(session, adapter, options, ZeroReadBackoff.exponential(), PumpStarter.threading());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff) {
        this(session, adapter, options, zeroReadBackoff, PumpStarter.threading());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter) {
        this(session, adapter, options, zeroReadBackoff, pumpStarter, System::nanoTime);
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            LongSupplier outputNanoTime) {
        this(session, adapter, options, zeroReadBackoff, pumpStarter, outputNanoTime, ProtocolCallbackRunner.bounded());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            LongSupplier outputNanoTime,
            ProtocolCallbackRunner callbackRunner) {
        this(
                session,
                adapter,
                options,
                zeroReadBackoff,
                pumpStarter,
                outputNanoTime,
                callbackRunner,
                SerializedRequestGate.Waiter.timed());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            LongSupplier outputNanoTime,
            ProtocolCallbackRunner callbackRunner,
            SerializedRequestGate.Waiter requestLockWaiter) {
        this.session = Objects.requireNonNull(session, "session");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.options = Objects.requireNonNull(options, "options");
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.callbackRunner = Objects.requireNonNull(callbackRunner, "callbackRunner");
        this.requestGate = new SerializedRequestGate(requestLockWaiter);
        this.outputPumps = new OutputPumpCoordinator(
                session, OUTPUT_OWNER, OutputPumpCoordinator.FailureAttribution.SCENARIO_TERMINAL);
        int responsePendingByteLimit = ProtocolResponseReader.pendingByteLimit(options);
        int responseOutputWithoutInputLimit = ProtocolResponseReader.outputWithoutInputLimit(options);
        int decodedLineSuffixLimit = ProtocolResponseReader.decodedLineSuffixLimit(options);
        ProtocolTranscriptBuffer initializedTranscript;
        ProtocolTextDecoderState stdoutDecoder;
        ProtocolTextDecoderState stderrDecoder;
        try {
            initializedTranscript = new ProtocolTranscriptBuffer(options.transcriptLimit(), options.charsetPolicy());
            stdoutDecoder = new ProtocolTextDecoderState(
                    options.charsetPolicy(),
                    responsePendingByteLimit,
                    responseOutputWithoutInputLimit,
                    decodedLineSuffixLimit);
            stderrDecoder = new ProtocolTextDecoderState(
                    options.charsetPolicy(),
                    responsePendingByteLimit,
                    responseOutputWithoutInputLimit,
                    decodedLineSuffixLimit);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            ProtocolSessionException failure = new ProtocolSessionException(
                    ProtocolSessionException.Reason.DECODE_ERROR,
                    new ProtocolTranscript("", false, false),
                    "Could not initialize protocol output decoders",
                    exception);
            throw failure;
        } catch (Error error) {
            throw error;
        }
        this.transcript = initializedTranscript;
        this.state = new ProtocolSessionState(this::protocolTranscript, this::exitCodeSnapshot);
        this.stdoutTextDecoder = stdoutDecoder;
        this.stderrTextDecoder = stderrDecoder;
        LongSupplier checkedOutputNanoTime = Objects.requireNonNull(outputNanoTime, "outputNanoTime");
        this.stdout = new ProtocolOutputQueue(
                options.outputBacklogLimit(),
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                checkedOutputNanoTime,
                () -> {},
                () -> {},
                this::exitCodeSnapshot,
                null);
        this.stderr = new ProtocolOutputQueue(
                options.outputBacklogLimit(),
                ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ,
                checkedOutputNanoTime,
                () -> {},
                () -> {},
                this::exitCodeSnapshot,
                null);
        BoundedLifecyclePublisher.Reservation publicationReservation =
                BoundedLifecyclePublisher.shared().reserve(1);
        this.exitPublication = publicationReservation.takePermit();
        try {
            startPumps(Objects.requireNonNull(pumpStarter, "pumpStarter"));
            observeExitAfterOutputCleanup();
        } catch (RuntimeException | Error failure) {
            exitPublication.release();
            throw failure;
        }
    }

    @Override
    public O request(I request) {
        return request(request, options.requestTimeout());
    }

    @Override
    public O request(I request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        Duration requestTimeout = DurationSupport.requirePositive(timeout, "timeout");
        long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
        acquireRequestLock(deadlineNanos);
        ProtocolSessionState.RequestOutcome requestOutcome = state.beginRequest();
        try {
            state.ensureOpen();
            writeRequest(request, deadlineNanos, requestOutcome);
            requestOutcome.throwIfFailed();
            O response = readResponse(deadlineNanos, requestOutcome);
            recordDeadlineFailure(deadlineNanos, requestOutcome);
            state.completeRequest(requestOutcome);
            return response;
        } catch (ProtocolSessionException exception) {
            ProtocolSessionException primary = state.primaryFailure(requestOutcome, exception);
            ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
            if (primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                outcome = state.recordTerminalFailure(primary.reason(), primary.getMessage(), primary);
            }
            Error fatalError = outcome == null ? null : outcome.fatalError();
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, primary);
                closePreserving(fatalError);
            } else if (primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                closePreserving(primary);
            }
            throw state.finalizeProtocolFailure(requestOutcome, primary);
        } catch (Error error) {
            ProtocolSessionState.TerminalSnapshot outcome = state.recordFatalError(error);
            Error fatalError = Objects.requireNonNull(outcome.fatalError(), "fatalError");
            closePreserving(fatalError);
            throw state.finalizeFatalFailure(requestOutcome, fatalError);
        } finally {
            endRequest(requestOutcome);
            requestGate.release();
        }
    }

    private void endRequest(ProtocolSessionState.RequestOutcome requestOutcome) {
        if (state.endRequest(requestOutcome)) {
            outputPumps.sealFailureAttribution();
        }
    }

    private void acquireRequestLock(long deadlineNanos) {
        try {
            if (!requestGate.acquireUntil(deadlineNanos)) {
                throw state.arbitrateRequestAdmissionFailure(() -> state.timeout(null));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.arbitrateRequestAdmissionFailure(() -> state.failure(
                    ProtocolSessionException.Reason.FAILURE,
                    "Interrupted while waiting to start protocol request",
                    exception));
        }
    }

    private void recordDeadlineFailure(long deadlineNanos, ProtocolSessionState.RequestOutcome requestOutcome) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            state.recordRequestTimeout(requestOutcome);
        }
    }

    @Override
    public ProtocolTranscript transcript() {
        return protocolTranscript();
    }

    @Override
    public CompletableFuture<SessionExit> onExit() {
        return exit.copy();
    }

    boolean exitCompleted() {
        return exit.isDone();
    }

    CompletableFuture<Void> physicalOutputCleanup() {
        return session.physicalOutputCleanup();
    }

    private void observeExitAfterOutputCleanup() {
        session.observeExit((result, failure) -> outputPumps.publishAfterOutputCleanup(
                () -> session.afterPhysicalOutputCleanup(() -> exitPublication.publish(() -> {
                    if (failure == null) {
                        exit.complete(result);
                    } else {
                        exit.completeExceptionally(failure);
                    }
                }))));
    }

    @Override
    public void close() {
        closeWithEvent(true);
    }

    private void closeWithEvent(boolean publishClosed) {
        closeWithEvent(publishClosed, null);
    }

    private void closeWithEvent(boolean publishClosed, Throwable primary) {
        ProtocolSessionState.CloseDecision close = state.claimClose(publishClosed);
        boolean lifecycleOwner = close.owner();
        if (lifecycleOwner) {
            callbackCancellation.cancel();
        }
        try {
            if (lifecycleOwner && publishClosed) {
                publishTerminalWake(Objects.requireNonNull(close.terminal(), "terminal"));
            }
        } finally {
            if (primary != null) {
                outputPumps.closeSessionPreserving(primary);
            } else if (lifecycleOwner) {
                outputPumps.closeSession();
            }
        }
    }

    private void publishTerminalWake(ProtocolSessionState.TerminalSnapshot outcome) {
        if (outcome.kind() == ProtocolSessionState.TerminalKind.FAILURE) {
            stdout.failAndClear(outcome.reason(), outcome.primary());
            stderr.failAndClear(outcome.reason(), outcome.primary());
        } else if (outcome.kind() == ProtocolSessionState.TerminalKind.FATAL) {
            publishFatalWake(outcome.fatalError());
        } else {
            stdout.close();
            stderr.close();
        }
    }

    private void startPumps(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-protocol-stdout-",
                stream -> runPump("stdout", stream, stdout),
                "procwright-protocol-stderr-",
                stream -> runPump("stderr", stream, stderr),
                state::markClosed);
    }

    private void runPump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        try {
            pump(streamName, stream, output);
        } catch (RuntimeException failure) {
            failRuntimeOutput(failure);
        } catch (Error error) {
            failFatalOutput(error);
        }
    }

    private void pump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        byte[] buffer = new byte[8192];
        try (stream) {
            int consecutiveZeroReads = 0;
            while (!state.isClosed()) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, state::isClosed)) {
                        if (!state.isClosed() && Thread.currentThread().isInterrupted()) {
                            throw new CommandExecutionException("Protocol output pump was interrupted during backoff");
                        }
                        return;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                transcript.appendStream(streamName, buffer, count);
                if (!output.offer(Arrays.copyOf(buffer, count))) {
                    Throwable primary = failOutputBacklogOverflow();
                    closeQuietly(primary);
                    return;
                }
            }
            if (state.isClosed()) {
                return;
            }
            transcript.endStream(streamName);
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            Throwable primary = failTranscriptDecoding(exception);
            closeQuietly(primary);
            return;
        } catch (IOException exception) {
            if (!endTranscript(streamName)) {
                return;
            }
            if (!state.isClosed()) {
                if (output == stdout) {
                    failStdoutIo(exception);
                } else {
                    output.failure(reasonFor(exception), exception);
                }
            }
            return;
        }
        OptionalInt exitCode = exitCodeSnapshot();
        output.eof(exitCode);
        if (output == stdout) {
            recordIdleEofTerminal();
        }
    }

    private void recordIdleEofTerminal() {
        if (state.recordStdoutEof()) {
            outputPumps.sealFailureAttribution();
        }
    }

    private boolean endTranscript(String streamName) {
        try {
            transcript.endStream(streamName);
            return true;
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            if (!state.isClosed()) {
                Throwable primary = failTranscriptDecoding(exception);
                closeQuietly(primary);
            }
            return false;
        }
    }

    private void writeRequest(I request, long deadlineNanos, ProtocolSessionState.RequestOutcome requestOutcome) {
        ProtocolRuntimeFailures trackedFailures = state.trackedFailures(requestOutcome);
        RequestCapabilityScope capabilityScope = new RequestCapabilityScope("ProtocolWriter");
        ProtocolRequestWriter writer =
                new ProtocolRequestWriter(session, options, deadlineNanos, trackedFailures, capabilityScope);
        try {
            callbackRunner.run(
                    "procwright-protocol-stdin-",
                    deadlineNanos,
                    callbackCancellation,
                    (thread, failure) -> failLateCallbackFailure(requestOutcome, thread, failure),
                    failure -> {
                        capabilityScope.invalidate();
                        state.selectCallbackAbandonment(
                                requestOutcome, "Interrupted while writing protocol request", failure);
                    },
                    () -> {
                        state.ensureOpen();
                        capabilityScope.activate();
                        try {
                            adapter.writeRequest(request, writer);
                        } catch (Throwable callbackFailure) {
                            writer.throwIfError();
                            throw callbackFailure;
                        } finally {
                            capabilityScope.invalidate();
                        }
                        writer.throwIfFailed();
                        return null;
                    });
        } catch (TimeoutException exception) {
            throw state.recordRequestTimeout(requestOutcome);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw recordCallbackCancellation(requestOutcome, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.recordRequestInterruption(
                    requestOutcome, "Interrupted while writing protocol request", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
            Error fatalError = outcome == null ? null : outcome.fatalError();
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, cause);
                throw fatalError;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            requestOutcome.throwIfFailed();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            if (cause instanceof IOException ioException) {
                throw state.failure(
                        ProtocolSessionException.Reason.BROKEN_PIPE, "Could not write protocol request", ioException);
            }
            if (cause instanceof ProcessExitedException processExited) {
                throw state.failure(
                        ProtocolSessionException.Reason.PROCESS_EXITED,
                        "Protocol process exited before the request could be written",
                        processExited);
            }
            throw state.failure(ProtocolSessionException.Reason.FAILURE, "Could not write protocol request", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private O readResponse(long deadlineNanos, ProtocolSessionState.RequestOutcome requestOutcome) {
        ProtocolRuntimeFailures trackedFailures = state.trackedFailures(requestOutcome);
        ProtocolResponseBudget budget =
                new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), trackedFailures);
        RequestCapabilityScope capabilityScope = new RequestCapabilityScope("ProtocolReader");
        Readers readers = new Readers(
                new ProtocolResponseReader(
                        stdout, options, deadlineNanos, budget, stdoutTextDecoder, trackedFailures, capabilityScope),
                new ProtocolResponseReader(
                        stderr, options, deadlineNanos, budget, stderrTextDecoder, trackedFailures, capabilityScope));
        try {
            return callbackRunner.run(
                    "procwright-protocol-decoder-",
                    deadlineNanos,
                    callbackCancellation,
                    (thread, failure) -> failLateCallbackFailure(requestOutcome, thread, failure),
                    failure -> {
                        capabilityScope.invalidate();
                        state.selectCallbackAbandonment(
                                requestOutcome, "Interrupted while decoding protocol response", failure);
                    },
                    () -> {
                        capabilityScope.activate();
                        try {
                            return Objects.requireNonNull(
                                    adapter.readResponse(readers), "Protocol response decoder returned null");
                        } finally {
                            capabilityScope.invalidate();
                        }
                    });
        } catch (TimeoutException exception) {
            throw state.recordRequestTimeout(requestOutcome);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw recordCallbackCancellation(requestOutcome, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.recordRequestInterruption(
                    requestOutcome, "Interrupted while decoding protocol response", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
            Error fatalError = outcome == null ? null : outcome.fatalError();
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, cause);
                throw fatalError;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            requestOutcome.throwIfFailed();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            throw state.failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, "Protocol response decoder failed", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private ProtocolSessionException recordCallbackCancellation(
            ProtocolSessionState.RequestOutcome requestOutcome, BoundedTaskRunner.TaskCancelledException cancellation) {
        ProtocolSessionException failure = state.selectCallbackCancellation(requestOutcome, cancellation);
        if (!state.isClosed()) {
            closePreserving(failure);
        }
        return failure;
    }

    private ProtocolTranscript protocolTranscript() {
        return transcript.snapshot();
    }

    private OptionalInt exitCodeSnapshot() {
        return session.processExitCode();
    }

    private void closePreserving(Throwable failure) {
        try {
            closeWithEvent(true, failure);
        } catch (Throwable closeFailure) {
            SuppressionSupport.attach(failure, closeFailure);
        }
    }

    private void closeTerminalPreserving(Throwable failure) {
        try {
            closeWithEvent(false, failure);
        } catch (Throwable closeFailure) {
            SuppressionSupport.attach(failure, closeFailure);
        }
    }

    private void closeQuietly(Throwable candidate) {
        ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
        Throwable primary = outcome == null || outcome.isClosed() ? candidate : outcome.primary();
        try {
            closeWithEvent(false, primary);
        } catch (RuntimeException ignored) {
            // The reader observes the original protocol failure.
        }
    }

    private void failFatalOutput(Error error) {
        ProtocolSessionState.TerminalSnapshot outcome = state.recordFatalError(error);
        Throwable primary = outcome.primary();
        if (outcome.fatalError() != null) {
            publishFatalWake(outcome.fatalError());
        }
        closeTerminalPreserving(primary);
    }

    private void failLateCallbackFailure(
            ProtocolSessionState.RequestOutcome requestOutcome, Thread sourceThread, Throwable failure) {
        try {
            if (failure instanceof Error error) {
                failFatalOutput(error);
                return;
            }
            state.attachLateCallbackFailure(requestOutcome, failure);
        } finally {
            BoundedTaskRunner.reportLateFailure(sourceThread, failure);
        }
    }

    private void failRuntimeOutput(RuntimeException failure) {
        boolean publishFailure = !state.isClosed();
        ProtocolSessionState.TerminalSnapshot outcome = state.recordTerminalFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not read protocol output", failure);
        Throwable primary = outcome.isClosed() ? failure : outcome.primary();
        try {
            if (publishFailure && outcome.kind() == ProtocolSessionState.TerminalKind.FAILURE) {
                stdout.failAndClear(outcome.reason(), outcome.primary());
                stderr.failAndClear(outcome.reason(), outcome.primary());
            }
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(primary, publicationFailure);
        } finally {
            closeTerminalPreserving(primary);
        }
    }

    private void failStdoutIo(IOException failure) {
        ProtocolSessionException.Reason reason = reasonFor(failure);
        ProtocolSessionState.TerminalSnapshot outcome =
                state.recordTerminalFailure(reason, "Could not read protocol stdout", failure);
        Throwable primary = outcome.isClosed() ? failure : outcome.primary();
        try {
            if (outcome.kind() == ProtocolSessionState.TerminalKind.FAILURE) {
                stdout.failAndClear(outcome.reason(), outcome.primary());
                stderr.failAndClear(outcome.reason(), outcome.primary());
            }
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(primary, publicationFailure);
        } finally {
            closeTerminalPreserving(primary);
        }
    }

    private void publishFatalWake(Error error) {
        try {
            stdout.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, error);
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(error, publicationFailure);
        }
        try {
            stderr.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, error);
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(error, publicationFailure);
        }
    }

    private Throwable failOutputBacklogOverflow() {
        // Only strict stdout returns false. Stderr retains a fail-on-read marker without
        // terminating a stdout-only session.
        CommandExecutionException failure = new CommandExecutionException("Protocol stdout backlog overflow");
        ProtocolSessionState.TerminalSnapshot outcome = state.recordTerminalFailure(
                ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "Protocol output backlog overflow", failure);
        stdout.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
        stderr.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
        return outcome.isClosed() ? failure : outcome.primary();
    }

    private Throwable failTranscriptDecoding(ProtocolTranscriptBuffer.TranscriptDecodingException failure) {
        ProtocolSessionState.TerminalSnapshot outcome = state.recordTerminalFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol transcript", failure);
        stdout.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, failure);
        stderr.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, failure);
        return outcome.isClosed() ? failure : outcome.primary();
    }

    private static ProtocolSessionException.Reason reasonFor(IOException exception) {
        return exception instanceof CharacterCodingException
                ? ProtocolSessionException.Reason.DECODE_ERROR
                : ProtocolSessionException.Reason.FAILURE;
    }

    private record Readers(ProtocolReader stdout, ProtocolReader stderr) implements ProtocolReaders {}

    interface ProtocolCallbackRunner {

        <T> T run(
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.CancellationSignal cancellation,
                BoundedTaskRunner.LateFailureHandler lateFailureHandler,
                BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
                BoundedTaskRunner.Task<T> task)
                throws TimeoutException, InterruptedException, ExecutionException,
                        BoundedTaskRunner.TaskCancelledException;

        static ProtocolCallbackRunner bounded() {
            return BoundedProtocolCallbackRunner.INSTANCE;
        }
    }

    private enum BoundedProtocolCallbackRunner implements ProtocolCallbackRunner {
        INSTANCE;

        @Override
        public <T> T run(
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.CancellationSignal cancellation,
                BoundedTaskRunner.LateFailureHandler lateFailureHandler,
                BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
                BoundedTaskRunner.Task<T> task)
                throws TimeoutException, InterruptedException, ExecutionException,
                        BoundedTaskRunner.TaskCancelledException {
            return BoundedTaskRunner.runReportingLateFailure(
                    BoundedTaskRunner.PROTOCOL_CALLBACKS,
                    threadPrefix,
                    deadlineNanos,
                    cancellation,
                    lateFailureHandler,
                    abandonmentHandler,
                    task);
        }
    }
}

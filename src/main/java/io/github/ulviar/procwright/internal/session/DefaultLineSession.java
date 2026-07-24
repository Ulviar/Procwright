/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.IOException;
import java.nio.charset.CoderMalfunctionError;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Line-oriented request/response workflow over an interactive process.
 *
 * <p>Only one request is decoded at a time. Custom response decoders consume stdout lines through a deadline-aware
 * reader, while stderr is drained into the bounded transcript for diagnostics.
 */
public final class DefaultLineSession implements LineSession {

    private static final String OUTPUT_OWNER = "LineSession";

    private final DefaultSession session;
    private final LineSessionSettings options;
    private final OutputPumpCoordinator outputPumps;
    private final LineOutputTransport output;
    private final BoundedTranscriptBuffer transcript;
    private final WriteTaskRunner writeTaskRunner;
    private final LongSupplier nanoTime;
    private final SerializedRequestGate requestGate;
    private final LineSessionState state;
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private final AtomicBoolean malformed = new AtomicBoolean();
    private final BoundedTaskRunner.CancellationSignal callbackCancellation =
            new BoundedTaskRunner.CancellationSignal();

    public DefaultLineSession(DefaultSession session, LineSessionSettings options) {
        this(session, options, ZeroReadBackoff.exponential(), PumpStarter.threading());
    }

    DefaultLineSession(DefaultSession session, LineSessionSettings options, ZeroReadBackoff zeroReadBackoff) {
        this(session, options, zeroReadBackoff, PumpStarter.threading());
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                (limiter, threadPrefix, deadlineNanos, handoff, task) ->
                        BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task),
                System::nanoTime);
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            WriteTaskRunner writeTaskRunner) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                writeTaskRunner,
                System::nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            WriteTaskRunner writeTaskRunner,
            LongSupplier nanoTime) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                writeTaskRunner,
                nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            WriteTaskRunner writeTaskRunner,
            LongSupplier nanoTime,
            SerializedRequestGate.Waiter requestLockWaiter) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        this.writeTaskRunner = Objects.requireNonNull(writeTaskRunner, "writeTaskRunner");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.requestGate = new SerializedRequestGate(requestLockWaiter);
        this.outputPumps = new OutputPumpCoordinator(
                session, OUTPUT_OWNER, OutputPumpCoordinator.FailureAttribution.SCENARIO_TERMINAL);
        this.transcript = new BoundedTranscriptBuffer(options.transcriptLimit());
        this.state = new LineSessionState(this::lineTranscript);
        IncrementalTextDecoder stdoutTextDecoder;
        IncrementalTextDecoder stderrTextDecoder;
        try {
            stdoutTextDecoder = createDecoder(options.maxLineChars());
            stderrTextDecoder = createDecoder(options.transcriptLimit());
        } catch (RuntimeException | CoderMalfunctionError exception) {
            malformed.set(true);
            LineSessionException failure = new LineSessionException(
                    LineSessionException.Reason.DECODE_ERROR,
                    lineTranscript(),
                    "Could not initialize line-session output decoders",
                    exception);
            throw failure;
        } catch (Error error) {
            throw error;
        }
        this.output = new LineOutputTransport(
                options,
                state,
                Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff"),
                outputPumps,
                transcript,
                malformed,
                stdoutTextDecoder,
                stderrTextDecoder,
                new LineOutputTransport.FailureHandler() {
                    @Override
                    public void failRuntime(
                            LineSessionException.Reason reason, String message, RuntimeException failure) {
                        failRuntimeOutput(reason, message, failure);
                    }

                    @Override
                    public void failFatal(Error error) {
                        failFatalOutput(error);
                    }

                    @Override
                    public void closeQuietly(Throwable failure) {
                        DefaultLineSession.this.closeQuietly(failure);
                    }
                });
        BoundedLifecyclePublisher.Reservation publicationReservation =
                BoundedLifecyclePublisher.shared().reserve(1);
        this.exitPublication = publicationReservation.takePermit();
        try {
            output.start(Objects.requireNonNull(pumpStarter, "pumpStarter"));
            observeExitAfterOutputCleanup();
        } catch (RuntimeException | Error failure) {
            exitPublication.release();
            throw failure;
        }
    }

    /**
     * Sends one line and decodes one response with the default request timeout.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     */
    public LineResponse request(String line) {
        return request(line, options.requestTimeout());
    }

    /**
     * Sends one line and decodes one response with an explicit request timeout.
     *
     * @param line request line without the terminating line feed
     * @param timeout request timeout
     * @return decoded response
     */
    public LineResponse request(String line, Duration timeout) {
        Duration requestTimeout = DurationSupport.requirePositive(timeout, "timeout");
        long startedNanos = nanoTime.getAsLong();
        long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
        byte[] encodedLine = encodeLine(line, deadlineNanos);
        return requestEncoded(encodedLine, startedNanos, deadlineNanos);
    }

    LineResponse requestEncoded(byte[] encodedLine, Duration timeout) {
        Objects.requireNonNull(encodedLine, "encodedLine");
        Duration requestTimeout = DurationSupport.requirePositive(timeout, "timeout");
        long startedNanos = nanoTime.getAsLong();
        long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
        return requestEncoded(encodedLine, startedNanos, deadlineNanos);
    }

    private LineResponse requestEncoded(byte[] encodedLine, long startedNanos, long deadlineNanos) {
        acquireRequestLock(deadlineNanos);
        try {
            return requestWhileLocked(encodedLine, startedNanos, deadlineNanos);
        } finally {
            requestGate.release();
        }
    }

    private LineResponse requestWhileLocked(byte[] encodedLine, long startedNanos, long deadlineNanos) {
        RequestFailureTracker<LineSessionException> requestFailures = beginRequest();
        try {
            return executeRequest(encodedLine, startedNanos, deadlineNanos, requestFailures);
        } finally {
            endRequest(requestFailures);
        }
    }

    private LineResponse executeRequest(
            byte[] encodedLine,
            long startedNanos,
            long deadlineNanos,
            RequestFailureTracker<LineSessionException> requestFailures) {
        LineSessionException.Reason errorReason = LineSessionException.Reason.FAILURE;
        String errorMessage = "Line-session request writer failed";
        try {
            state.ensureOpen();
            writeLine(encodedLine, deadlineNanos, requestFailures);

            errorReason = LineSessionException.Reason.DECODER_FAILED;
            errorMessage = "Response decoder failed";
            RequestCapabilityScope capabilityScope = new RequestCapabilityScope("ResponseDecoder.Reader");
            ResponseReader reader = new ResponseReader(deadlineNanos, requestFailures, capabilityScope);
            List<String> lines = decode(reader, capabilityScope, deadlineNanos, requestFailures);
            recordDeadlineFailure(deadlineNanos, requestFailures);
            completeRequest(requestFailures);
            return new LineResponse(
                    lines, lineTranscript(), DurationSupport.elapsed(startedNanos, nanoTime.getAsLong()));
        } catch (RetryablePreWriteFailure failure) {
            throw failure.failure();
        } catch (LineSessionException exception) {
            LineSessionException primary = state.primaryFailure(requestFailures, exception);
            LineSessionState.TerminalSnapshot outcome = state.terminal();
            if (primary.reason() != LineSessionException.Reason.CLOSED) {
                outcome = state.recordTerminalFailure(primary.reason(), primary.getMessage(), primary);
            }
            Error fatalError = outcome == null ? null : outcome.fatalError();
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, primary);
                closePreserving(fatalError);
                throw fatalError;
            }
            if (primary.reason() != LineSessionException.Reason.CLOSED) {
                closePreserving(primary);
            }
            throw primary;
        } catch (Error error) {
            LineSessionException primary = requestFailures.failure();
            LineSessionState.TerminalSnapshot outcome;
            if (primary == null || primary.reason() == LineSessionException.Reason.CLOSED) {
                outcome = state.recordTerminalFailure(errorReason, errorMessage, error);
            } else {
                outcome = state.recordTerminalFailure(primary.reason(), primary.getMessage(), error);
            }
            Error fatalError = outcome.fatalError();
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, error);
                closePreserving(fatalError);
                throw fatalError;
            }
            closePreserving(error);
            throw error;
        }
    }

    private RequestFailureTracker<LineSessionException> beginRequest() {
        return state.beginRequest();
    }

    private void completeRequest(RequestFailureTracker<LineSessionException> requestFailures) {
        state.completeRequest(requestFailures);
    }

    private void endRequest(RequestFailureTracker<LineSessionException> requestFailures) {
        if (state.endRequest(requestFailures)) {
            outputPumps.sealFailureAttribution();
        }
    }

    private byte[] encodeLine(String line, long deadlineNanos) {
        return LineRequestEncoder.encodeUntil(
                line,
                options,
                message -> state.failure(LineSessionException.Reason.REQUEST_TOO_LARGE, message, null),
                state::timeout,
                exception -> state.failure("Interrupted while encoding line request", exception),
                deadlineNanos);
    }

    private void acquireRequestLock(long deadlineNanos) {
        try {
            if (!requestGate.acquireUntil(deadlineNanos)) {
                throw arbitrateRequestLockFailure(state::timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw arbitrateRequestLockFailure(
                    () -> state.failure("Interrupted while waiting to start line request", exception));
        }
    }

    private LineSessionException arbitrateRequestLockFailure(Supplier<LineSessionException> localFailure) {
        return state.arbitrateRequestAdmissionFailure(localFailure);
    }

    private void recordDeadlineFailure(
            long deadlineNanos, RequestFailureTracker<LineSessionException> requestFailures) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            state.recordRequestTimeout(requestFailures);
        }
    }

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    public LineTranscript transcript() {
        return lineTranscript();
    }

    /**
     * Returns the line-session exit future view. It completes after process supervision, both output pumps, and
     * helper-owned physical output cleanup have released their internal ownership.
     *
     * @return line-session exit future
     */
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

    /**
     * Closes the underlying interactive session. Calling this method more than once has no effect.
     */
    @Override
    public void close() {
        closeWithEvent(true);
    }

    private void closeWithEvent(boolean publishClosed) {
        closeWithEvent(publishClosed, null);
    }

    private void closeWithEvent(boolean publishClosed, Throwable primary) {
        boolean lifecycleOwner = state.claimClose();
        if (lifecycleOwner) {
            callbackCancellation.cancel();
        }
        try {
            if (lifecycleOwner && publishClosed) {
                output.closeReaders();
            }
        } finally {
            if (primary != null) {
                outputPumps.closeSessionPreserving(primary);
            } else if (lifecycleOwner) {
                outputPumps.closeSession();
            }
        }
    }

    private IncrementalTextDecoder createDecoder(int configuredLimit) {
        return new IncrementalTextDecoder(
                options.charsetPolicy(),
                IncrementalTextDecoder.pendingByteLimitFor(configuredLimit),
                IncrementalTextDecoder.outputWithoutInputLimitFor(configuredLimit));
    }

    private List<String> decode(
            ResponseReader reader,
            RequestCapabilityScope capabilityScope,
            long deadlineNanos,
            RequestFailureTracker<LineSessionException> requestFailures) {
        try {
            return BoundedTaskRunner.runReportingLateFailure(
                    BoundedTaskLimits.PROTOCOL_CALLBACKS,
                    "procwright-line-decoder-",
                    deadlineNanos,
                    callbackCancellation,
                    (thread, failure) -> {
                        if (failure != requestFailures.failure()) {
                            BoundedTaskRunner.reportLateFailure(thread, failure);
                        }
                    },
                    failure -> {
                        capabilityScope.invalidate();
                        selectCallbackAbandonment(requestFailures, failure);
                    },
                    () -> {
                        capabilityScope.activate();
                        try {
                            return List.copyOf(options.responseDecoder().decode(reader));
                        } finally {
                            capabilityScope.invalidate();
                        }
                    });
        } catch (TimeoutException exception) {
            throw state.selectCallbackFailure(requestFailures, state::timeout);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw state.selectCallbackFailure(requestFailures, () -> state.closed(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.selectCallbackFailure(
                    requestFailures, () -> state.failure("Interrupted while decoding line response", exception));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            LineSessionState.TerminalSnapshot outcome = state.terminal();
            Error fatalError = outcome == null ? null : outcome.fatalError();
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, cause);
                throw fatalError;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            requestFailures.throwIfFailed();
            if (cause instanceof LineSessionException lineSessionException) {
                throw lineSessionException;
            }
            throw state.failure(LineSessionException.Reason.DECODER_FAILED, "Response decoder failed", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private void selectCallbackAbandonment(
            RequestFailureTracker<LineSessionException> requestFailures, Throwable cause) {
        if (cause instanceof TimeoutException) {
            state.recordRequestTimeout(requestFailures);
        } else if (cause instanceof BoundedTaskRunner.TaskCancelledException cancellation) {
            state.recordRequestFailure(requestFailures, () -> state.closed(cancellation));
        } else if (cause instanceof InterruptedException interruption) {
            state.recordRequestFailure(
                    requestFailures, () -> state.failure("Interrupted while decoding line response", interruption));
        } else {
            throw new IllegalArgumentException("Unsupported callback abandonment", cause);
        }
    }

    private void writeLine(
            byte[] encodedLine, long deadlineNanos, RequestFailureTracker<LineSessionException> requestFailures)
            throws RetryablePreWriteFailure {
        BoundedTaskRunner.TaskHandoff handoff = new BoundedTaskRunner.TaskHandoff();
        try {
            writeTaskRunner.run(
                    BoundedTaskLimits.BLOCKING_WRITES, "procwright-line-stdin-", deadlineNanos, handoff, () -> {
                        java.io.OutputStream stdin = session.stdin();
                        stdin.write(encodedLine);
                        stdin.flush();
                        return null;
                    });
        } catch (SessionStdinClosedException exception) {
            throw state.recordRequestFailure(requestFailures, () -> state.closed(exception));
        } catch (IllegalStateException exception) {
            throw state.recordRequestFailure(
                    requestFailures,
                    () -> state.failure(
                            LineSessionException.Reason.FAILURE, "Could not write line-session stdin", exception));
        } catch (TimeoutException exception) {
            if (handoff.retrySafe()) {
                throw retryablePreWriteFailure(requestFailures, state.timeout());
            }
            throw state.recordRequestTimeout(requestFailures);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LineSessionException interrupted = state.failure(
                    LineSessionException.Reason.FAILURE, "Interrupted while writing line-session stdin", exception);
            if (handoff.retrySafe()) {
                throw retryablePreWriteFailure(requestFailures, interrupted);
            }
            throw state.recordRequestFailure(requestFailures, () -> interrupted);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (handoff.retrySafe()) {
                throw retryablePreWriteFailure(
                        requestFailures,
                        state.failure(
                                LineSessionException.Reason.FAILURE,
                                "Could not start line-session stdin writer",
                                cause));
            }
            if (cause instanceof ProcessExitedException processExited) {
                throw state.recordRequestFailure(
                        requestFailures,
                        () -> state.failure(
                                LineSessionException.Reason.PROCESS_EXITED,
                                "Line-session process exited before the request could be written",
                                processExited));
            }
            if (cause instanceof SessionStdinClosedException stdinClosed) {
                throw state.recordRequestFailure(requestFailures, () -> state.closed(stdinClosed));
            }
            if (cause instanceof IOException ioException) {
                throw state.recordRequestFailure(
                        requestFailures,
                        () -> state.failure(
                                LineSessionException.Reason.BROKEN_PIPE,
                                "Could not write line-session stdin",
                                ioException));
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw state.recordRequestFailure(
                        requestFailures,
                        () -> state.failure(
                                LineSessionException.Reason.FAILURE,
                                "Could not write line-session stdin",
                                runtimeException));
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw state.recordRequestFailure(
                    requestFailures,
                    () -> state.failure(
                            LineSessionException.Reason.FAILURE, "Could not write line-session stdin", cause));
        }
    }

    private RetryablePreWriteFailure retryablePreWriteFailure(
            RequestFailureTracker<LineSessionException> requestFailures, LineSessionException candidate) {
        return new RetryablePreWriteFailure(state.releaseRetryablePreWrite(requestFailures, candidate));
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
        LineSessionState.TerminalSnapshot outcome = state.terminal();
        Throwable primary = outcome == null ? candidate : outcome.primary();
        try {
            closeWithEvent(false, primary);
        } catch (RuntimeException ignored) {
            // The caller will observe the original line-session failure.
        }
    }

    private void failFatalOutput(Error error) {
        LineSessionState.TerminalSnapshot outcome = state.recordFatalError(error);
        Throwable primary = outcome.primary();
        if (outcome.fatalError() != null) {
            output.publishFatal(outcome.fatalError());
        }
        closeTerminalPreserving(primary);
    }

    private void failRuntimeOutput(LineSessionException.Reason reason, String message, RuntimeException failure) {
        boolean publishFailure = !state.isClosed();
        LineSessionState.TerminalSnapshot outcome = state.recordTerminalFailure(reason, message, failure);
        Throwable primary = outcome.primary();
        try {
            if (publishFailure && outcome.isFailure()) {
                output.publishFailure(outcome.reason(), message, outcome.primary());
            }
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(primary, publicationFailure);
        } finally {
            closeTerminalPreserving(primary);
        }
    }

    private LineTranscript lineTranscript() {
        BoundedTranscriptBuffer.Snapshot snapshot = transcript.snapshot();
        return new LineTranscript(snapshot.text(), snapshot.truncated(), malformed.get());
    }

    private final class ResponseReader implements ResponseDecoder.Reader {

        private final long deadlineNanos;
        private final RequestFailureTracker<LineSessionException> requestFailures;
        private final RequestCapabilityScope capabilityScope;
        private long linesRead;
        private long charactersRead;

        private ResponseReader(
                long deadlineNanos,
                RequestFailureTracker<LineSessionException> requestFailures,
                RequestCapabilityScope capabilityScope) {
            this.deadlineNanos = deadlineNanos;
            this.requestFailures = requestFailures;
            this.capabilityScope = Objects.requireNonNull(capabilityScope, "capabilityScope");
        }

        @Override
        public String readLine() {
            capabilityScope.verifyAccess();
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw state.recordRequestTimeout(requestFailures);
                }

                LineOutputTransport.Event event = output.take(deadlineNanos, requestFailures);

                switch (event.kind()) {
                    case LINE -> {
                        linesRead++;
                        if (linesRead > options.maxResponseLines()) {
                            throw track(() -> state.failure(
                                    LineSessionException.Reason.RESPONSE_TOO_LARGE,
                                    "Line response exceeds maxResponseLines",
                                    null));
                        }
                        int lineLength = event.line().length();
                        if (lineLength > options.maxResponseChars() - charactersRead) {
                            throw track(() -> state.failure(
                                    LineSessionException.Reason.RESPONSE_TOO_LARGE,
                                    "Line response exceeds maxResponseChars",
                                    null));
                        }
                        charactersRead += lineLength;
                        return event.line();
                    }
                    case EOF -> throw track(state::eof);
                    case CLOSED -> throw track(() -> state.closed(null));
                    case FAILURE -> throw track(() -> state.failure(event.reason(), event.message(), event.failure()));
                    case FATAL -> throw (Error) event.failure();
                }
            }
        }

        private LineSessionException track(Supplier<LineSessionException> failureFactory) {
            return state.recordRequestFailure(requestFailures, failureFactory);
        }
    }

    @FunctionalInterface
    interface WriteTaskRunner {

        void run(
                BoundedTaskLimiter limiter,
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.TaskHandoff handoff,
                BoundedTaskRunner.Task<Void> task)
                throws TimeoutException, InterruptedException, ExecutionException;
    }

    private static final class RetryablePreWriteFailure extends Exception {

        private static final long serialVersionUID = 1L;

        private final LineSessionException failure;

        private RetryablePreWriteFailure(LineSessionException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        private LineSessionException failure() {
            return failure;
        }
    }
}

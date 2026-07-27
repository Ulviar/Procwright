/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import java.nio.charset.CoderMalfunctionError;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Line-oriented request/response workflow over an interactive process.
 *
 * <p>Only one request is decoded at a time. Custom response decoders consume stdout lines through a deadline-aware
 * reader, while stderr is drained into the bounded transcript for diagnostics.
 */
public final class DefaultLineSession implements LineSession {

    private final DefaultSession session;
    private final LineSessionSettings options;
    private final OutputPumpCoordinator outputPumps;
    private final LineOutputTransport output;
    private final BoundedTranscriptBuffer transcript;
    private final LineRequestWriter requestWriter;
    private final LineResponseDecoder responseDecoder;
    private final LongSupplier nanoTime;
    private final SerializedRequestGate requestGate;
    private final LineSessionState state;
    private final AtomicBoolean malformed = new AtomicBoolean();

    public DefaultLineSession(DefaultSession session, LineSessionSettings options) {
        this(session, options, Dependencies.defaults());
    }

    DefaultLineSession(DefaultSession session, LineSessionSettings options, Dependencies dependencies) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        Dependencies runtime = Objects.requireNonNull(dependencies, "dependencies");
        this.nanoTime = runtime.nanoTime();
        this.requestGate = new SerializedRequestGate(runtime.requestLockWaiter());
        this.outputPumps = new OutputPumpCoordinator(session, SessionOutputMode.LINE);
        this.transcript = new BoundedTranscriptBuffer(options.transcriptLimit());
        this.state = new LineSessionState(this::lineTranscript, outputPumps::reportFailure);
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
                runtime.zeroReadBackoff(),
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
        this.requestWriter = new LineRequestWriter(session, state, runtime.writeTaskRunner());
        this.responseDecoder = new LineResponseDecoder(options, state, output, outputPumps);
        output.start(runtime.pumpStarter(), outputPumps);
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
        try (LineSessionState.Request requestFailures = state.beginRequest()) {
            return executeRequest(encodedLine, startedNanos, deadlineNanos, requestFailures);
        }
    }

    private LineResponse executeRequest(
            byte[] encodedLine, long startedNanos, long deadlineNanos, LineSessionState.Request requestFailures) {
        try {
            state.ensureOpen();
            requestWriter.write(encodedLine, deadlineNanos, requestFailures);

            List<String> lines = responseDecoder.decode(deadlineNanos, requestFailures);
            recordDeadlineFailure(deadlineNanos, requestFailures);
            state.completeRequest(requestFailures);
            return new LineResponse(
                    lines, lineTranscript(), DurationSupport.elapsed(startedNanos, nanoTime.getAsLong()));
        } catch (LineRequestWriter.RetryablePreWriteFailure failure) {
            throw failure.failure();
        } catch (LineSessionException exception) {
            LineSessionException primary = state.primaryFailure(requestFailures, exception);
            LineSessionState.TerminalSnapshot outcome = state.terminal();
            if (primary.reason() != LineSessionException.Reason.CLOSED) {
                outcome = state.recordTerminalFailure(primary.reason(), primary.getMessage(), primary);
            }
            if (outcome instanceof LineSessionState.FatalSnapshot fatal) {
                closePreserving(fatal.error());
                throw fatal.error();
            }
            if (primary.reason() != LineSessionException.Reason.CLOSED) {
                closePreserving(primary);
            }
            throw primary;
        } catch (Error error) {
            LineSessionState.TerminalSnapshot outcome = state.recordFatalError(error);
            if (outcome instanceof LineSessionState.FatalSnapshot fatal) {
                closePreserving(fatal.error());
                throw fatal.error();
            }
            LineSessionException selected = state.terminalException((LineSessionState.FailureSnapshot) outcome);
            closePreserving(selected);
            throw selected;
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
                throw state.arbitrateRequestAdmissionFailure(state::timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.arbitrateRequestAdmissionFailure(
                    () -> state.failure("Interrupted while waiting to start line request", exception));
        }
    }

    private void recordDeadlineFailure(long deadlineNanos, LineSessionState.Request requestFailures) {
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
     * Returns the line-session exit future view. It completes after process supervision and both output pumps settle
     * logically; potentially blocking physical stream closes continue independently.
     *
     * @return line-session exit future
     */
    public CompletableFuture<SessionExit> onExit() {
        return session.onExit();
    }

    boolean publicExitCompleted() {
        return session.publicExitCompleted();
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
            responseDecoder.cancel();
        }
        try {
            if (lifecycleOwner && publishClosed) {
                output.closeReaders();
            }
        } finally {
            if (primary != null) {
                outputPumps.closeSessionAfterFailure(primary);
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

    private void closePreserving(Throwable failure) {
        try {
            if (failure instanceof LineSessionException lineFailure
                    && lineFailure.reason() == LineSessionException.Reason.EOF) {
                closeWithObservedEof(lineFailure);
            } else {
                closeWithEvent(true, failure);
            }
        } catch (Throwable closeFailure) {
            outputPumps.reportFailure(closeFailure);
        }
    }

    private void closeWithObservedEof(LineSessionException failure) {
        boolean lifecycleOwner = state.claimClose();
        if (lifecycleOwner) {
            responseDecoder.cancel();
        }
        try {
            if (lifecycleOwner) {
                output.closeReaders();
            }
        } finally {
            if (lifecycleOwner) {
                outputPumps.closeSessionAfterObservedEof(failure);
            }
        }
    }

    private void closeTerminalPreserving(Throwable failure) {
        try {
            closeWithEvent(false, failure);
        } catch (Throwable closeFailure) {
            outputPumps.reportFailure(closeFailure);
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
        LineSessionState.OutputSelection selection = state.recordOutputFatalError(error);
        if (selection.rejectedAfterClose()) {
            return;
        }
        LineSessionState.TerminalSnapshot outcome = selection.selected();
        Throwable primary = Objects.requireNonNull(outcome, "outcome").primary();
        output.publishTerminal(outcome);
        closeTerminalPreserving(primary);
    }

    private void failRuntimeOutput(LineSessionException.Reason reason, String message, RuntimeException failure) {
        LineSessionState.OutputSelection selection = state.recordOutputFailure(reason, message, failure);
        if (selection.rejectedAfterClose()) {
            return;
        }
        LineSessionState.TerminalSnapshot outcome = Objects.requireNonNull(selection.selected(), "outcome");
        Throwable primary = outcome.primary();
        try {
            output.publishTerminal(outcome);
        } catch (Throwable publicationFailure) {
            outputPumps.reportFailure(publicationFailure);
        } finally {
            closeTerminalPreserving(primary);
        }
    }

    private LineTranscript lineTranscript() {
        BoundedTranscriptBuffer.Snapshot snapshot = transcript.snapshot();
        return new LineTranscript(snapshot.text(), snapshot.truncated(), malformed.get());
    }

    record Dependencies(
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            LineRequestWriter.TaskRunner writeTaskRunner,
            LongSupplier nanoTime,
            SerializedRequestGate.Waiter requestLockWaiter) {

        Dependencies {
            Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
            Objects.requireNonNull(pumpStarter, "pumpStarter");
            Objects.requireNonNull(writeTaskRunner, "writeTaskRunner");
            Objects.requireNonNull(nanoTime, "nanoTime");
            Objects.requireNonNull(requestLockWaiter, "requestLockWaiter");
        }

        static Dependencies defaults() {
            return new Dependencies(
                    ZeroReadBackoff.exponential(),
                    PumpStarter.threading(),
                    TimedTaskRunner::runTracked,
                    System::nanoTime,
                    SerializedRequestGate.Waiter.timed());
        }
    }
}

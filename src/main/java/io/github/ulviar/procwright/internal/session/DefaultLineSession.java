/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
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
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderMalfunctionError;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final DefaultSession session;
    private final LineSessionSettings options;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final BoundedTranscriptBuffer transcript;
    private final IncrementalTextDecoder stdoutDecoder;
    private final IncrementalTextDecoder stderrDecoder;
    private final WriteTaskRunner writeTaskRunner;
    private final RequestTransitionProbe requestTransitionProbe;
    private final ResponseReadProbe responseReadProbe;
    private final LongSupplier nanoTime;
    private final SerializedRequestGate requestGate;
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private final ArrayDeque<StdoutEvent> stdoutEvents = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean malformed = new AtomicBoolean();
    private final AtomicReference<TerminalOutcome> terminalOutcome = new AtomicReference<>();
    private final BoundedTaskRunner.CancellationSignal callbackCancellation =
            new BoundedTaskRunner.CancellationSignal();
    private final Object requestOutcomeLock = new Object();
    private RequestFailureTracker<LineSessionException> activeRequestFailures;

    /**
     * Guards stdout event publication against the close transition. Invariant: once {@link StdoutEvent#closed()} has
     * been published, the queue contains exactly that event and the pump publishes nothing afterwards, so a concurrent
     * reader can neither lose the CLOSED event nor observe stale LINE events behind it.
     */
    private final Object stdoutEventLock = new Object();

    private boolean closedEventPublished;
    private int pendingLineEvents;
    private long pendingLineChars;

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
                point -> {},
                System::nanoTime);
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            WriteTaskRunner writeTaskRunner,
            RequestTransitionProbe requestTransitionProbe) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                writeTaskRunner,
                requestTransitionProbe,
                System::nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            WriteTaskRunner writeTaskRunner,
            RequestTransitionProbe requestTransitionProbe,
            LongSupplier nanoTime) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                writeTaskRunner,
                requestTransitionProbe,
                nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            WriteTaskRunner writeTaskRunner,
            RequestTransitionProbe requestTransitionProbe,
            LongSupplier nanoTime,
            SerializedRequestGate.Waiter requestLockWaiter) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                writeTaskRunner,
                requestTransitionProbe,
                nanoTime,
                requestLockWaiter,
                transition -> {});
    }

    DefaultLineSession(
            DefaultSession session,
            LineSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            WriteTaskRunner writeTaskRunner,
            RequestTransitionProbe requestTransitionProbe,
            LongSupplier nanoTime,
            SerializedRequestGate.Waiter requestLockWaiter,
            ResponseReadProbe responseReadProbe) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.writeTaskRunner = Objects.requireNonNull(writeTaskRunner, "writeTaskRunner");
        this.requestTransitionProbe = Objects.requireNonNull(requestTransitionProbe, "requestTransitionProbe");
        this.responseReadProbe = Objects.requireNonNull(responseReadProbe, "responseReadProbe");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.requestGate = new SerializedRequestGate(requestLockWaiter);
        this.outputPumps = new OutputPumpCoordinator(session, OUTPUT_OWNER);
        this.transcript = new BoundedTranscriptBuffer(options.transcriptLimit());
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
        this.stdoutDecoder = stdoutTextDecoder;
        this.stderrDecoder = stderrTextDecoder;
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
            requestTransitionProbe.check(RequestTransition.AFTER_LOCK_ACQUIRED);
            return requestWhileLocked(encodedLine, startedNanos, deadlineNanos);
        } finally {
            requestGate.release();
        }
    }

    private LineResponse requestWhileLocked(byte[] encodedLine, long startedNanos, long deadlineNanos) {
        RequestFailureTracker<LineSessionException> requestFailures = beginRequest();
        try {
            requestTransitionProbe.check(RequestTransition.AFTER_REQUEST_BEGUN);
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
            ensureOpen();
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
            LineSessionException primary = primaryFailure(requestFailures, exception);
            TerminalOutcome outcome = terminalOutcome.get();
            if (primary.reason() != LineSessionException.Reason.CLOSED) {
                outcome = recordTerminalFailure(primary.reason(), primary.getMessage(), primary);
            }
            Error fatalError = fatalError(outcome);
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
            TerminalOutcome outcome;
            if (primary == null || primary.reason() == LineSessionException.Reason.CLOSED) {
                outcome = recordTerminalFailure(errorReason, errorMessage, error);
            } else {
                outcome = recordTerminalFailure(primary.reason(), primary.getMessage(), error);
            }
            Error fatalError = fatalError(outcome);
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
        synchronized (requestOutcomeLock) {
            if (activeRequestFailures != null) {
                throw new IllegalStateException("line request outcome is already active");
            }
            activeRequestFailures = new RequestFailureTracker<>();
            return activeRequestFailures;
        }
    }

    private void completeRequest(RequestFailureTracker<LineSessionException> requestFailures) {
        LineSessionException requestFailure;
        TerminalOutcome sessionOutcome;
        synchronized (requestOutcomeLock) {
            requestFailure = requestFailures.failure();
            sessionOutcome = terminalOutcome.get();
            if (requestFailure == null && sessionOutcome == null) {
                activeRequestFailures = null;
                return;
            }
        }
        Error fatalError = fatalError(sessionOutcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (requestFailure != null) {
            throw requestFailure;
        }
        throw terminalException((TerminalFailure) sessionOutcome);
    }

    private void endRequest(RequestFailureTracker<LineSessionException> requestFailures) {
        synchronized (requestOutcomeLock) {
            if (activeRequestFailures == requestFailures) {
                activeRequestFailures = null;
            }
        }
    }

    private byte[] encodeLine(String line, long deadlineNanos) {
        return LineRequestEncoder.encodeUntil(
                line,
                options,
                message -> failure(LineSessionException.Reason.REQUEST_TOO_LARGE, message, null),
                this::timeout,
                exception -> failure("Interrupted while encoding line request", exception),
                deadlineNanos);
    }

    private void acquireRequestLock(long deadlineNanos) {
        try {
            if (!requestGate.acquireUntil(deadlineNanos)) {
                throw arbitrateRequestLockFailure(this::timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw arbitrateRequestLockFailure(
                    () -> failure("Interrupted while waiting to start line request", exception));
        }
    }

    private LineSessionException arbitrateRequestLockFailure(Supplier<LineSessionException> localFailure) {
        TerminalOutcome outcome;
        boolean closedSelected;
        synchronized (requestOutcomeLock) {
            outcome = terminalOutcome.get();
            closedSelected = closed.get();
        }
        Error fatalError = fatalError(outcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (outcome instanceof TerminalFailure failure) {
            throw terminalException(failure);
        }
        if (closedSelected) {
            throw closed(null);
        }
        return Objects.requireNonNull(localFailure.get(), "localFailure");
    }

    private void recordDeadlineFailure(
            long deadlineNanos, RequestFailureTracker<LineSessionException> requestFailures) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            recordRequestTimeout(requestFailures);
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

    int pendingStdoutLineCount() {
        synchronized (stdoutEventLock) {
            return pendingLineEvents;
        }
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

    boolean outputCleanupCompleted() {
        return outputPumps.outputCleanupCompleted();
    }

    private void observeExitAfterOutputCleanup() {
        session.observeExit(
                (result, failure) -> outputPumps.publishAfterOutputCleanup(() -> exitPublication.publish(() -> {
                    session.awaitPhysicalOutputPublication();
                    if (failure == null) {
                        exit.complete(result);
                    } else {
                        exit.completeExceptionally(failure);
                    }
                })));
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
        boolean lifecycleOwner;
        synchronized (requestOutcomeLock) {
            lifecycleOwner = !closed.getAndSet(true);
        }
        if (lifecycleOwner) {
            callbackCancellation.cancel();
        }
        try {
            if (lifecycleOwner && publishClosed) {
                synchronized (stdoutEventLock) {
                    stdoutEvents.clear();
                    pendingLineEvents = 0;
                    pendingLineChars = 0;
                    stdoutEvents.addLast(StdoutEvent.closed());
                    closedEventPublished = true;
                    stdoutEventLock.notifyAll();
                }
            }
        } finally {
            if (primary != null) {
                outputPumps.closeSessionPreserving(primary);
            } else if (lifecycleOwner) {
                outputPumps.closeSession();
            }
        }
    }

    private void startPumps(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-line-stdout-",
                stream -> runPump("stdout", stream, true, stdoutDecoder),
                "procwright-line-stderr-",
                stream -> runPump("stderr", stream, false, stderrDecoder),
                this::markClosed);
    }

    private void markClosed() {
        synchronized (requestOutcomeLock) {
            closed.set(true);
        }
    }

    private void runPump(
            String streamName, InputStream stream, boolean responseStream, IncrementalTextDecoder decoder) {
        try {
            pump(streamName, stream, responseStream, decoder);
        } catch (RuntimeException failure) {
            malformed.compareAndSet(false, decoder.malformed());
            failRuntimeOutput(failure);
        } catch (Error error) {
            malformed.compareAndSet(false, decoder.malformed());
            failFatalOutput(error);
        }
    }

    private void pump(String streamName, InputStream stream, boolean responseStream, IncrementalTextDecoder decoder) {
        AtomicBoolean acceptingOutput = new AtomicBoolean(true);
        StringBuilder line = new StringBuilder();
        IncrementalTextDecoder.Sink sink = (chars, count) -> {
            if (!acceptingOutput.get()) {
                return;
            }
            transcript.appendStream(streamName, chars, count);
            if (responseStream && !publishLines(line, chars, count)) {
                acceptingOutput.set(false);
            }
        };
        try (stream) {
            byte[] buffer = new byte[1024];
            int consecutiveZeroReads = 0;
            while (!closed.get()) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, closed::get)) {
                        return;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                decoder.decode(buffer, count, sink);
                malformed.compareAndSet(false, decoder.malformed());
                if (!acceptingOutput.get()) {
                    return;
                }
            }
            if (closed.get()) {
                return;
            }
            decoder.end(sink);
            malformed.compareAndSet(false, decoder.malformed());
            if (responseStream && line.length() > 0) {
                if (line.length() > options.maxLineChars()) {
                    failOversizedLine();
                    return;
                }
                if (!offerStdoutLine(line)) {
                    return;
                }
            }
            if (responseStream) {
                offerStdoutEvent(StdoutEvent.eof());
            }
        } catch (java.io.IOException exception) {
            malformed.compareAndSet(false, decoder.malformed());
            if (!closed.get()) {
                offerStdoutEvent(StdoutEvent.failure(exception));
                closeQuietly(exception);
            }
        }
    }

    private IncrementalTextDecoder createDecoder(int configuredLimit) {
        return new IncrementalTextDecoder(
                options.charsetPolicy(),
                IncrementalTextDecoder.pendingByteLimitFor(configuredLimit),
                IncrementalTextDecoder.outputWithoutInputLimitFor(configuredLimit));
    }

    private boolean publishLines(StringBuilder currentLine, char[] chars, int count) {
        for (int index = 0; index < count; index++) {
            char value = chars[index];
            if (value == '\n') {
                int length = currentLine.length();
                if (length > 0 && currentLine.charAt(length - 1) == '\r') {
                    currentLine.deleteCharAt(length - 1);
                }
                if (!offerStdoutLine(currentLine)) {
                    return false;
                }
                currentLine.setLength(0);
            } else {
                currentLine.append(value);
                // A single trailing '\r' may still turn out to be a CRLF terminator, so the limit tolerates exactly
                // one pending '\r' beyond maxLineChars until the next character resolves it.
                int maxLineChars = options.maxLineChars();
                boolean pendingCarriageReturn = value == '\r' && currentLine.length() == maxLineChars + 1;
                if (currentLine.length() > maxLineChars && !pendingCarriageReturn) {
                    failOversizedLine();
                    return false;
                }
            }
        }
        return true;
    }

    private void failOversizedLine() {
        CommandExecutionException failure =
                new CommandExecutionException("Line-session stdout line exceeds maxLineChars");
        offerStdoutEvent(StdoutEvent.failure(LineSessionException.Reason.RESPONSE_TOO_LARGE, failure));
        closeQuietly(failure);
    }

    private void offerStdoutEvent(StdoutEvent event) {
        if (event.kind() == Kind.LINE) {
            throw new IllegalArgumentException("stdout line events must use offerStdoutLine");
        }
        synchronized (stdoutEventLock) {
            if (closedEventPublished) {
                return;
            }
            if (event.kind() == Kind.FAILURE) {
                recordTerminalFailure(event.reason(), failureMessage(event.reason()), event.failure());
            }
            stdoutEvents.addLast(event);
            stdoutEventLock.notifyAll();
        }
    }

    private boolean offerStdoutLine(StringBuilder line) {
        boolean overflow = false;
        synchronized (stdoutEventLock) {
            if (closedEventPublished) {
                return false;
            }
            int lineChars = line.length();
            if (pendingLineEvents >= options.stdoutBacklogLines()
                    || lineChars > options.stdoutBacklogChars() - pendingLineChars) {
                CommandExecutionException failure =
                        new CommandExecutionException("Line-session stdout backlog overflow");
                recordTerminalFailure(
                        LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW,
                        failureMessage(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW),
                        failure);
                stdoutEvents.clear();
                pendingLineEvents = 0;
                pendingLineChars = 0;
                stdoutEvents.addLast(StdoutEvent.failure(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, failure));
                overflow = true;
            } else {
                String publishedLine = line.toString();
                stdoutEvents.addLast(StdoutEvent.line(publishedLine));
                pendingLineEvents++;
                pendingLineChars += lineChars;
            }
            stdoutEventLock.notifyAll();
        }
        if (overflow) {
            closeQuietly(terminalPrimary(Objects.requireNonNull(terminalOutcome.get(), "terminal outcome")));
        }
        return !overflow;
    }

    private TerminalOutcome recordTerminalFailure(LineSessionException.Reason reason, String message, Throwable cause) {
        synchronized (requestOutcomeLock) {
            TerminalOutcome outcome = terminalOutcome.get();
            if (outcome == null) {
                outcome = new TerminalFailure(reason, message, cause);
                terminalOutcome.set(outcome);
            } else {
                SuppressionSupport.attach(terminalPrimary(outcome), cause);
            }
            if (activeRequestFailures != null && outcome instanceof TerminalFailure failure) {
                selectActiveTerminalFailure(activeRequestFailures, terminalException(failure));
            }
            return outcome;
        }
    }

    private TerminalOutcome recordFatalError(Error error) {
        synchronized (requestOutcomeLock) {
            TerminalOutcome outcome = terminalOutcome.get();
            if (outcome == null) {
                outcome = new FatalTerminalFailure(error);
                terminalOutcome.set(outcome);
            } else {
                SuppressionSupport.attach(terminalPrimary(outcome), error);
            }
            return outcome;
        }
    }

    private LineSessionException recordRequestFailure(
            RequestFailureTracker<LineSessionException> requestFailures,
            Supplier<LineSessionException> failureFactory) {
        synchronized (requestOutcomeLock) {
            return recordRequestFailureLocked(requestFailures, Objects.requireNonNull(failureFactory.get(), "failure"));
        }
    }

    private LineSessionException recordRequestFailureLocked(
            RequestFailureTracker<LineSessionException> requestFailures, LineSessionException candidate) {
        if (candidate.reason() == LineSessionException.Reason.CLOSED) {
            return requestFailures.record(candidate);
        }
        TerminalOutcome outcome = terminalOutcome.get();
        if (outcome == null) {
            LineSessionException primary = requestFailures.failure();
            if (primary == null) {
                primary = requestFailures.record(candidate);
            } else if (primary.reason() == LineSessionException.Reason.CLOSED) {
                primary = requestFailures.replaceWithTerminal(candidate);
            }
            outcome = new TerminalFailure(primary.reason(), primary.getMessage(), primary);
            terminalOutcome.set(outcome);
            return primary;
        }
        if (outcome instanceof TerminalFailure failure) {
            LineSessionException primary = requestFailures.failure();
            if (primary == null) {
                primary = requestFailures.record(terminalException(failure));
            } else if (primary.reason() == LineSessionException.Reason.CLOSED) {
                primary = requestFailures.replaceWithTerminal(terminalException(failure));
            }
            SuppressionSupport.attach(failure.cause(), candidate);
            return primary;
        }
        FatalTerminalFailure fatalFailure = (FatalTerminalFailure) outcome;
        SuppressionSupport.attach(fatalFailure.error(), candidate);
        return candidate;
    }

    private static void selectActiveTerminalFailure(
            RequestFailureTracker<LineSessionException> requestFailures, LineSessionException terminalFailure) {
        LineSessionException current = requestFailures.failure();
        if (current == null) {
            requestFailures.record(terminalFailure);
        } else if (current.reason() == LineSessionException.Reason.CLOSED) {
            requestFailures.replaceWithTerminal(terminalFailure);
        }
    }

    private List<String> decode(
            ResponseReader reader,
            RequestCapabilityScope capabilityScope,
            long deadlineNanos,
            RequestFailureTracker<LineSessionException> requestFailures) {
        try {
            return BoundedTaskRunner.runReportingLateFailure(
                    BoundedTaskRunner.PROTOCOL_CALLBACKS,
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
            throw selectedCallbackFailure(requestFailures, this::timeout);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw selectedCallbackFailure(requestFailures, () -> closed(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw selectedCallbackFailure(
                    requestFailures, () -> failure("Interrupted while decoding line response", exception));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            Error fatalError = fatalError(terminalOutcome.get());
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
            throw failure(LineSessionException.Reason.DECODER_FAILED, "Response decoder failed", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private void selectCallbackAbandonment(
            RequestFailureTracker<LineSessionException> requestFailures, Throwable cause) {
        if (cause instanceof TimeoutException) {
            recordRequestTimeout(requestFailures);
        } else if (cause instanceof BoundedTaskRunner.TaskCancelledException cancellation) {
            recordRequestFailure(requestFailures, () -> closed(cancellation));
        } else if (cause instanceof InterruptedException interruption) {
            recordRequestFailure(
                    requestFailures, () -> failure("Interrupted while decoding line response", interruption));
        } else {
            throw new IllegalArgumentException("Unsupported callback abandonment", cause);
        }
    }

    private LineSessionException selectedCallbackFailure(
            RequestFailureTracker<LineSessionException> requestFailures,
            Supplier<LineSessionException> fallbackFactory) {
        synchronized (requestOutcomeLock) {
            Error fatalError = fatalError(terminalOutcome.get());
            if (fatalError != null) {
                throw fatalError;
            }
            LineSessionException selected = requestFailures.failure();
            return selected != null
                    ? selected
                    : recordRequestFailureLocked(
                            requestFailures, Objects.requireNonNull(fallbackFactory.get(), "failure"));
        }
    }

    private LineSessionException recordRequestTimeout(RequestFailureTracker<LineSessionException> requestFailures) {
        synchronized (requestOutcomeLock) {
            LineSessionException selected = requestFailures.failure();
            if (selected != null && selected.reason() == LineSessionException.Reason.TIMEOUT) {
                return selected;
            }
            return recordRequestFailureLocked(requestFailures, timeout());
        }
    }

    private void writeLine(
            byte[] encodedLine, long deadlineNanos, RequestFailureTracker<LineSessionException> requestFailures)
            throws RetryablePreWriteFailure {
        BoundedTaskRunner.TaskHandoff handoff = new BoundedTaskRunner.TaskHandoff();
        try {
            writeTaskRunner.run(
                    BoundedTaskRunner.BLOCKING_WRITES, "procwright-line-stdin-", deadlineNanos, handoff, () -> {
                        java.io.OutputStream stdin = session.stdin();
                        handoff.markSideEffectStarted();
                        stdin.write(encodedLine);
                        stdin.flush();
                        return null;
                    });
        } catch (SessionStdinClosedException exception) {
            throw recordRequestFailure(requestFailures, () -> closed(exception));
        } catch (IllegalStateException exception) {
            throw recordRequestFailure(
                    requestFailures,
                    () -> failure(
                            LineSessionException.Reason.FAILURE, "Could not write line-session stdin", exception));
        } catch (TimeoutException exception) {
            if (handoff.retrySafe()) {
                throw retryablePreWriteFailure(requestFailures, timeout());
            }
            throw recordRequestTimeout(requestFailures);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LineSessionException interrupted = failure(
                    LineSessionException.Reason.FAILURE, "Interrupted while writing line-session stdin", exception);
            if (handoff.retrySafe()) {
                throw retryablePreWriteFailure(requestFailures, interrupted);
            }
            throw recordRequestFailure(requestFailures, () -> interrupted);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (handoff.retrySafe()) {
                throw retryablePreWriteFailure(
                        requestFailures,
                        failure(
                                LineSessionException.Reason.FAILURE,
                                "Could not start line-session stdin writer",
                                cause));
            }
            if (cause instanceof ProcessExitedException processExited) {
                throw recordRequestFailure(
                        requestFailures,
                        () -> failure(
                                LineSessionException.Reason.PROCESS_EXITED,
                                "Line-session process exited before the request could be written",
                                processExited));
            }
            if (cause instanceof SessionStdinClosedException stdinClosed) {
                throw recordRequestFailure(requestFailures, () -> closed(stdinClosed));
            }
            if (cause instanceof IOException ioException) {
                throw recordRequestFailure(
                        requestFailures,
                        () -> failure(
                                LineSessionException.Reason.BROKEN_PIPE,
                                "Could not write line-session stdin",
                                ioException));
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw recordRequestFailure(
                        requestFailures,
                        () -> failure(
                                LineSessionException.Reason.FAILURE,
                                "Could not write line-session stdin",
                                runtimeException));
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw recordRequestFailure(
                    requestFailures,
                    () -> failure(LineSessionException.Reason.FAILURE, "Could not write line-session stdin", cause));
        }
    }

    private RetryablePreWriteFailure retryablePreWriteFailure(
            RequestFailureTracker<LineSessionException> requestFailures, LineSessionException candidate) {
        synchronized (requestOutcomeLock) {
            TerminalOutcome outcome = terminalOutcome.get();
            Error fatalError = fatalError(outcome);
            if (fatalError != null) {
                throw fatalError;
            }
            LineSessionException activeFailure = requestFailures.failure();
            if (activeFailure != null) {
                throw activeFailure;
            }
            if (outcome instanceof TerminalFailure failure) {
                LineSessionException terminalFailure = terminalException(failure);
                requestFailures.record(terminalFailure);
                throw terminalFailure;
            }
            if (activeRequestFailures != requestFailures) {
                throw new IllegalStateException("line request outcome is no longer active");
            }
            activeRequestFailures = null;
            return new RetryablePreWriteFailure(candidate);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            TerminalOutcome outcome = terminalOutcome.get();
            Error fatalError = fatalError(outcome);
            if (fatalError != null) {
                throw fatalError;
            }
            if (outcome instanceof TerminalFailure failure) {
                throw terminalException(failure);
            }
            throw closed(null);
        }
    }

    private LineSessionException terminalException(TerminalFailure failure) {
        return new LineSessionException(
                failure.reason(),
                lineTranscript(),
                "Line session was closed by an earlier failure: " + failure.message(),
                failure.cause());
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
        TerminalOutcome outcome = terminalOutcome.get();
        Throwable primary = outcome == null ? candidate : terminalPrimary(outcome);
        try {
            closeWithEvent(false, primary);
        } catch (RuntimeException ignored) {
            // The caller will observe the original line-session failure.
        }
    }

    private void failFatalOutput(Error error) {
        TerminalOutcome outcome = recordFatalError(error);
        Throwable primary = terminalPrimary(outcome);
        if (outcome instanceof FatalTerminalFailure fatalFailure) {
            offerFatalStdoutEvent(fatalFailure.error());
        }
        closeTerminalPreserving(primary);
    }

    private void failRuntimeOutput(RuntimeException failure) {
        boolean publishFailure = !closed.get();
        TerminalOutcome outcome = recordTerminalFailure(
                LineSessionException.Reason.DECODE_ERROR,
                failureMessage(LineSessionException.Reason.DECODE_ERROR),
                failure);
        Throwable primary = terminalPrimary(outcome);
        try {
            if (publishFailure && outcome instanceof TerminalFailure terminalFailure) {
                offerStdoutEvent(StdoutEvent.failure(terminalFailure.reason(), terminalFailure.cause()));
            }
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(primary, publicationFailure);
        } finally {
            closeTerminalPreserving(primary);
        }
    }

    private void offerFatalStdoutEvent(Error error) {
        synchronized (stdoutEventLock) {
            if (closedEventPublished) {
                return;
            }
            stdoutEvents.clear();
            pendingLineEvents = 0;
            pendingLineChars = 0;
            stdoutEvents.addLast(StdoutEvent.fatal(error));
            stdoutEventLock.notifyAll();
        }
    }

    private Throwable terminalPrimary(TerminalOutcome outcome) {
        if (outcome instanceof FatalTerminalFailure fatalFailure) {
            return fatalFailure.error();
        }
        return ((TerminalFailure) outcome).cause();
    }

    private static Error fatalError(TerminalOutcome outcome) {
        return outcome instanceof FatalTerminalFailure failure ? failure.error() : null;
    }

    private LineSessionException timeout() {
        return new LineSessionException(
                LineSessionException.Reason.TIMEOUT, lineTranscript(), "Line request timed out");
    }

    private LineSessionException eof() {
        return new LineSessionException(LineSessionException.Reason.EOF, lineTranscript(), "Line session reached EOF");
    }

    private LineSessionException closed(Throwable cause) {
        if (cause == null) {
            return new LineSessionException(
                    LineSessionException.Reason.CLOSED, lineTranscript(), "Line session is closed");
        }
        return new LineSessionException(
                LineSessionException.Reason.CLOSED, lineTranscript(), "Line session is closed", cause);
    }

    private LineSessionException failure(String message, Throwable cause) {
        return new LineSessionException(LineSessionException.Reason.FAILURE, lineTranscript(), message, cause);
    }

    private LineSessionException failure(LineSessionException.Reason reason, String message, Throwable cause) {
        return new LineSessionException(reason, lineTranscript(), message, cause);
    }

    private static LineSessionException primaryFailure(
            RequestFailureTracker<LineSessionException> requestFailures, LineSessionException fallback) {
        LineSessionException primary = requestFailures.failure();
        return primary == null ? fallback : primary;
    }

    private LineTranscript lineTranscript() {
        BoundedTranscriptBuffer.Snapshot snapshot = transcript.snapshot();
        return new LineTranscript(snapshot.text(), snapshot.truncated(), malformed.get());
    }

    boolean hasActiveRequestForTest() {
        synchronized (requestOutcomeLock) {
            return activeRequestFailures != null;
        }
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
                    throw recordRequestTimeout(requestFailures);
                }

                StdoutEvent event = takeStdoutEvent(deadlineNanos, requestFailures);

                switch (event.kind()) {
                    case LINE -> {
                        responseReadProbe.check(ResponseReadTransition.BEFORE_LIMIT_CHECK);
                        linesRead++;
                        if (linesRead > options.maxResponseLines()) {
                            throw track(() -> failure(
                                    LineSessionException.Reason.RESPONSE_TOO_LARGE,
                                    "Line response exceeds maxResponseLines",
                                    null));
                        }
                        int lineLength = event.line().length();
                        if (lineLength > options.maxResponseChars() - charactersRead) {
                            throw track(() -> failure(
                                    LineSessionException.Reason.RESPONSE_TOO_LARGE,
                                    "Line response exceeds maxResponseChars",
                                    null));
                        }
                        charactersRead += lineLength;
                        return event.line();
                    }
                    case EOF -> throw track(DefaultLineSession.this::eof);
                    case CLOSED -> throw track(() -> closed(null));
                    case FAILURE ->
                        throw track(() -> failure(event.reason(), failureMessage(event.reason()), event.failure()));
                    case FATAL -> throw (Error) event.failure();
                }
            }
        }

        private LineSessionException track(Supplier<LineSessionException> failureFactory) {
            return recordRequestFailure(requestFailures, failureFactory);
        }
    }

    private StdoutEvent takeStdoutEvent(
            long deadlineNanos, RequestFailureTracker<LineSessionException> requestFailures) {
        synchronized (stdoutEventLock) {
            while (stdoutEvents.isEmpty()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw recordRequestTimeout(requestFailures);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(stdoutEventLock, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw recordRequestFailure(
                            requestFailures, () -> failure("Interrupted while waiting for line response", exception));
                }
            }
            StdoutEvent event = stdoutEvents.removeFirst();
            if (event.kind() == Kind.LINE) {
                pendingLineEvents--;
                pendingLineChars -= event.line().length();
            }
            return event;
        }
    }

    private static String failureMessage(LineSessionException.Reason reason) {
        if (reason == LineSessionException.Reason.DECODE_ERROR) {
            return "Could not decode line-session stdout";
        }
        if (reason == LineSessionException.Reason.RESPONSE_TOO_LARGE) {
            return "Line-session response exceeded configured size limit";
        }
        if (reason == LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW) {
            return "Line-session stdout backlog overflow";
        }
        return "Could not read line-session stdout";
    }

    private record StdoutEvent(Kind kind, String line, LineSessionException.Reason reason, Throwable failure) {

        private static StdoutEvent line(String line) {
            return new StdoutEvent(Kind.LINE, line, null, null);
        }

        private static StdoutEvent eof() {
            return new StdoutEvent(Kind.EOF, null, null, null);
        }

        private static StdoutEvent failure(Throwable failure) {
            LineSessionException.Reason reason = failure instanceof CharacterCodingException
                    ? LineSessionException.Reason.DECODE_ERROR
                    : LineSessionException.Reason.FAILURE;
            return failure(reason, failure);
        }

        private static StdoutEvent failure(LineSessionException.Reason reason, Throwable failure) {
            return new StdoutEvent(Kind.FAILURE, null, reason, failure);
        }

        private static StdoutEvent fatal(Error failure) {
            return new StdoutEvent(Kind.FATAL, null, null, failure);
        }

        private static StdoutEvent closed() {
            return new StdoutEvent(Kind.CLOSED, null, null, null);
        }

        private StdoutEvent {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.LINE) {
                Objects.requireNonNull(line, "line");
            }
            if (kind == Kind.FAILURE) {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(failure, "failure");
            }
            if (kind == Kind.FATAL && !(failure instanceof Error)) {
                throw new IllegalArgumentException("fatal stdout event requires an Error");
            }
        }
    }

    private enum Kind {
        LINE,
        EOF,
        CLOSED,
        FAILURE,
        FATAL
    }

    @FunctionalInterface
    interface WriteTaskRunner {

        void run(
                BoundedTaskRunner.Limiter limiter,
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.TaskHandoff handoff,
                BoundedTaskRunner.Task<Void> task)
                throws TimeoutException, InterruptedException, ExecutionException;
    }

    @FunctionalInterface
    interface RequestTransitionProbe {

        void check(RequestTransition transition);
    }

    @FunctionalInterface
    interface ResponseReadProbe {

        void check(ResponseReadTransition transition);
    }

    enum RequestTransition {
        AFTER_LOCK_ACQUIRED,
        AFTER_REQUEST_BEGUN
    }

    enum ResponseReadTransition {
        BEFORE_LIMIT_CHECK
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

    private sealed interface TerminalOutcome permits TerminalFailure, FatalTerminalFailure {}

    private record TerminalFailure(LineSessionException.Reason reason, String message, Throwable cause)
            implements TerminalOutcome {

        private TerminalFailure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(cause, "cause");
        }
    }

    private record FatalTerminalFailure(Error error) implements TerminalOutcome {

        private FatalTerminalFailure {
            Objects.requireNonNull(error, "error");
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns line-session output pumping, framing, and the bounded stdout event queue.
 */
final class LineOutputTransport {

    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final LineSessionSettings options;
    private final LineSessionState state;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final BoundedTranscriptBuffer transcript;
    private final AtomicBoolean malformed;
    private final IncrementalTextDecoder stdoutDecoder;
    private final IncrementalTextDecoder stderrDecoder;
    private final FailureHandler failureHandler;
    private final Object eventLock = new Object();
    private final ArrayDeque<Event> events = new ArrayDeque<>();

    private boolean closedEventPublished;
    private int pendingLines;
    private long pendingCharacters;

    LineOutputTransport(
            LineSessionSettings options,
            LineSessionState state,
            ZeroReadBackoff zeroReadBackoff,
            OutputPumpCoordinator outputPumps,
            BoundedTranscriptBuffer transcript,
            AtomicBoolean malformed,
            IncrementalTextDecoder stdoutDecoder,
            IncrementalTextDecoder stderrDecoder,
            FailureHandler failureHandler) {
        this.options = Objects.requireNonNull(options, "options");
        this.state = Objects.requireNonNull(state, "state");
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.outputPumps = Objects.requireNonNull(outputPumps, "outputPumps");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.malformed = Objects.requireNonNull(malformed, "malformed");
        this.stdoutDecoder = Objects.requireNonNull(stdoutDecoder, "stdoutDecoder");
        this.stderrDecoder = Objects.requireNonNull(stderrDecoder, "stderrDecoder");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void start(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-line-stdout-",
                stream -> runPump("stdout", stream, true, stdoutDecoder),
                "procwright-line-stderr-",
                stream -> runPump("stderr", stream, false, stderrDecoder),
                state::markClosed);
    }

    void closeReaders() {
        synchronized (eventLock) {
            events.clear();
            pendingLines = 0;
            pendingCharacters = 0;
            events.addLast(ClosedEvent.INSTANCE);
            closedEventPublished = true;
            eventLock.notifyAll();
        }
    }

    void publishFatal(Error error) {
        synchronized (eventLock) {
            if (closedEventPublished) {
                return;
            }
            events.clear();
            pendingLines = 0;
            pendingCharacters = 0;
            events.addLast(new FatalEvent(error));
            eventLock.notifyAll();
        }
    }

    void publishFailure(LineSessionException.Reason reason, String message, Throwable failure) {
        offerFailure(reason, message, failure);
    }

    Event take(long deadlineNanos, LineSessionState.Request request) {
        InterruptedException interruption = null;
        Event event = null;
        synchronized (eventLock) {
            while (events.isEmpty() && interruption == null) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(eventLock, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    interruption = exception;
                }
            }
            if (interruption == null && !events.isEmpty()) {
                event = events.removeFirst();
                if (event instanceof LineEvent line) {
                    pendingLines--;
                    pendingCharacters -= line.value().length();
                }
            }
        }
        if (event != null) {
            return event;
        }
        if (interruption != null) {
            InterruptedException failure = interruption;
            throw state.recordRequestFailure(
                    request, () -> state.failure("Interrupted while waiting for line response", failure));
        }
        throw state.recordRequestTimeout(request);
    }

    private void runPump(
            String streamName, InputStream stream, boolean responseStream, IncrementalTextDecoder decoder) {
        try {
            pump(streamName, stream, responseStream, decoder);
        } catch (RuntimeException failure) {
            malformed.compareAndSet(false, decoder.malformed());
            failureHandler.failRuntime(
                    LineSessionException.Reason.FAILURE, "Line-session " + streamName + " output pump failed", failure);
        } catch (Error error) {
            malformed.compareAndSet(false, decoder.malformed());
            failureHandler.failFatal(error);
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
            while (!state.isClosed()) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, state::isClosed)) {
                        if (!state.isClosed() && Thread.currentThread().isInterrupted()) {
                            throw new CommandExecutionException(
                                    "Line-session output pump was interrupted during backoff");
                        }
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
            if (state.isClosed()) {
                return;
            }
            decoder.end(sink);
            malformed.compareAndSet(false, decoder.malformed());
            if (responseStream && line.length() > 0) {
                if (line.length() > options.maxLineChars()) {
                    failOversizedLine();
                    return;
                }
                if (!offerLine(line)) {
                    return;
                }
            }
            if (responseStream) {
                offerSignal(EofEvent.INSTANCE);
                state.recordStdoutEof();
            }
        } catch (IOException exception) {
            malformed.compareAndSet(false, decoder.malformed());
            if (!state.isClosed()) {
                LineSessionException.Reason reason = reasonFor(exception);
                offerFailure(reason, failureMessage(streamName, reason), exception);
                failureHandler.closeQuietly(exception);
            }
        }
    }

    private boolean publishLines(StringBuilder currentLine, char[] chars, int count) {
        for (int index = 0; index < count; index++) {
            char value = chars[index];
            if (value == '\n') {
                int length = currentLine.length();
                if (length > 0 && currentLine.charAt(length - 1) == '\r') {
                    currentLine.deleteCharAt(length - 1);
                }
                if (!offerLine(currentLine)) {
                    return false;
                }
                currentLine.setLength(0);
            } else {
                currentLine.append(value);
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
        offerFailure(
                LineSessionException.Reason.RESPONSE_TOO_LARGE,
                failureMessage("stdout", LineSessionException.Reason.RESPONSE_TOO_LARGE),
                failure);
        failureHandler.closeQuietly(failure);
    }

    private void offerSignal(Event event) {
        if (event instanceof LineEvent) {
            throw new IllegalArgumentException("stdout line events must use offerLine");
        }
        if (event instanceof FailureEvent) {
            throw new IllegalArgumentException("failure events must use offerFailure");
        }
        synchronized (eventLock) {
            if (closedEventPublished) {
                return;
            }
            events.addLast(event);
            eventLock.notifyAll();
        }
    }

    private void offerFailure(LineSessionException.Reason reason, String message, Throwable failure) {
        LineSessionState.TerminalSelection selection;
        synchronized (eventLock) {
            if (closedEventPublished) {
                return;
            }
            selection = state.selectTerminalFailure(reason, message, failure);
            events.addLast(eventFor(selection.selected()));
            eventLock.notifyAll();
        }
        state.reportDiscarded(selection);
    }

    private boolean offerLine(StringBuilder line) {
        boolean overflow = false;
        LineSessionState.TerminalSelection overflowSelection = null;
        synchronized (eventLock) {
            if (closedEventPublished) {
                return false;
            }
            int lineCharacters = line.length();
            if (pendingLines >= options.stdoutBacklogLines()
                    || lineCharacters > options.stdoutBacklogChars() - pendingCharacters) {
                CommandExecutionException overflowFailure =
                        new CommandExecutionException("Line-session stdout backlog overflow");
                events.clear();
                pendingLines = 0;
                pendingCharacters = 0;
                overflowSelection = state.selectTerminalFailure(
                        LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW,
                        failureMessage("stdout", LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW),
                        overflowFailure);
                events.addLast(eventFor(overflowSelection.selected()));
                overflow = true;
            } else {
                String publishedLine = line.toString();
                events.addLast(new LineEvent(publishedLine));
                pendingLines++;
                pendingCharacters += lineCharacters;
            }
            eventLock.notifyAll();
        }
        if (overflow) {
            LineSessionState.TerminalSelection selected =
                    Objects.requireNonNull(overflowSelection, "overflowSelection");
            state.reportDiscarded(selected);
            failureHandler.closeQuietly(selected.selected().primary());
        }
        return !overflow;
    }

    private static Event eventFor(LineSessionState.TerminalSnapshot terminal) {
        return switch (terminal) {
            case LineSessionState.FailureSnapshot failure ->
                new FailureEvent(failure.reason(), failure.message(), failure.primary());
            case LineSessionState.FatalSnapshot fatal -> new FatalEvent(fatal.error());
        };
    }

    static String failureMessage(String streamName, LineSessionException.Reason reason) {
        if (reason == LineSessionException.Reason.DECODE_ERROR) {
            return "Could not decode line-session " + streamName;
        }
        if (reason == LineSessionException.Reason.RESPONSE_TOO_LARGE) {
            return "Line-session response exceeded configured size limit";
        }
        if (reason == LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW) {
            return "Line-session stdout backlog overflow";
        }
        return "Could not read line-session " + streamName;
    }

    private static LineSessionException.Reason reasonFor(IOException failure) {
        return failure instanceof CharacterCodingException
                ? LineSessionException.Reason.DECODE_ERROR
                : LineSessionException.Reason.FAILURE;
    }

    interface FailureHandler {

        void failRuntime(LineSessionException.Reason reason, String message, RuntimeException failure);

        void failFatal(Error error);

        void closeQuietly(Throwable failure);
    }

    sealed interface Event permits LineEvent, EofEvent, ClosedEvent, FailureEvent, FatalEvent {}

    record LineEvent(String value) implements Event {

        public LineEvent {
            Objects.requireNonNull(value, "value");
        }
    }

    enum EofEvent implements Event {
        INSTANCE
    }

    enum ClosedEvent implements Event {
        INSTANCE
    }

    record FailureEvent(LineSessionException.Reason reason, String message, Throwable failure) implements Event {

        public FailureEvent {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(failure, "failure");
        }
    }

    record FatalEvent(Error error) implements Event {

        public FatalEvent {
            Objects.requireNonNull(error, "error");
        }
    }
}

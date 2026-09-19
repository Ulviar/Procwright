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
            BoundedTranscriptBuffer transcript,
            AtomicBoolean malformed,
            IncrementalTextDecoder stdoutDecoder,
            IncrementalTextDecoder stderrDecoder,
            FailureHandler failureHandler) {
        this.options = Objects.requireNonNull(options, "options");
        this.state = Objects.requireNonNull(state, "state");
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.malformed = Objects.requireNonNull(malformed, "malformed");
        this.stdoutDecoder = Objects.requireNonNull(stdoutDecoder, "stdoutDecoder");
        this.stderrDecoder = Objects.requireNonNull(stderrDecoder, "stderrDecoder");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void start(PumpStarter pumpStarter, OutputPumpCoordinator outputPumps) {
        Objects.requireNonNull(outputPumps, "outputPumps");
        outputPumps.start(
                pumpStarter,
                "procwright-line-stdout-",
                stream -> runPump("stdout", stream, true, stdoutDecoder),
                "procwright-line-stderr-",
                stream -> runPump("stderr", stream, false, stderrDecoder));
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

    void publishTerminal(LineSessionState.TerminalSnapshot terminal) {
        Objects.requireNonNull(terminal, "terminal");
        synchronized (eventLock) {
            if (closedEventPublished) {
                return;
            }
            events.clear();
            pendingLines = 0;
            pendingCharacters = 0;
            events.addLast(eventFor(terminal));
            eventLock.notifyAll();
        }
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
                if (line.length() > options.maxResponseChars()) {
                    failOversizedLine();
                    return;
                }
                if (!offerLine(line)) {
                    return;
                }
            }
            if (responseStream) {
                offerSignal(EofEvent.INSTANCE);
            }
        } catch (IOException exception) {
            malformed.compareAndSet(false, decoder.malformed());
            LineSessionException.Reason reason = reasonFor(exception);
            closeAfter(offerFailure(reason, failureMessage(streamName, reason), exception));
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
                int maxResponseChars = options.maxResponseChars();
                boolean pendingCarriageReturn = value == '\r' && currentLine.length() == maxResponseChars + 1;
                if (currentLine.length() > maxResponseChars && !pendingCarriageReturn) {
                    failOversizedLine();
                    return false;
                }
            }
        }
        return true;
    }

    private void failOversizedLine() {
        CommandExecutionException failure =
                new CommandExecutionException("Line-session stdout line exceeds maxResponseChars");
        closeAfter(offerFailure(
                LineSessionException.Reason.RESPONSE_TOO_LARGE,
                failureMessage("stdout", LineSessionException.Reason.RESPONSE_TOO_LARGE),
                failure));
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

    private LineSessionState.OutputSelection offerFailure(
            LineSessionException.Reason reason, String message, Throwable failure) {
        LineSessionState.OutputSelection selection;
        synchronized (eventLock) {
            if (closedEventPublished) {
                return null;
            }
            selection = state.selectOutputFailure(reason, message, failure);
            if (!selection.rejectedAfterClose()) {
                events.addLast(eventFor(Objects.requireNonNull(selection.selected(), "selected")));
            }
            eventLock.notifyAll();
        }
        state.reportDiscarded(selection);
        return selection;
    }

    private boolean offerLine(StringBuilder line) {
        boolean overflow = false;
        LineSessionState.OutputSelection overflowSelection = null;
        synchronized (eventLock) {
            if (closedEventPublished) {
                return false;
            }
            int lineCharacters = line.length();
            if (pendingLines >= options.maxResponseLines()
                    || lineCharacters > options.maxResponseChars() - pendingCharacters) {
                CommandExecutionException overflowFailure =
                        new CommandExecutionException("Line-session pending stdout exceeds response limits");
                events.clear();
                pendingLines = 0;
                pendingCharacters = 0;
                overflowSelection = state.selectOutputFailure(
                        LineSessionException.Reason.RESPONSE_TOO_LARGE,
                        failureMessage("stdout", LineSessionException.Reason.RESPONSE_TOO_LARGE),
                        overflowFailure);
                if (!overflowSelection.rejectedAfterClose()) {
                    events.addLast(eventFor(Objects.requireNonNull(overflowSelection.selected(), "selected")));
                }
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
            LineSessionState.OutputSelection selected = Objects.requireNonNull(overflowSelection, "overflowSelection");
            state.reportDiscarded(selected);
            closeAfter(selected);
        }
        return !overflow;
    }

    private void closeAfter(LineSessionState.OutputSelection selection) {
        if (selection == null || selection.rejectedAfterClose()) {
            return;
        }
        failureHandler.closeQuietly(
                Objects.requireNonNull(selection.selected(), "selected").primary());
    }

    private static Event eventFor(LineSessionState.TerminalSnapshot terminal) {
        if (terminal instanceof LineSessionState.FailureSnapshot failure) {
            return new FailureEvent(failure.reason(), failure.message(), failure.primary());
        }
        if (terminal instanceof LineSessionState.FatalSnapshot fatal) {
            return new FatalEvent(fatal.error());
        }
        throw new AssertionError("Unknown line terminal outcome: " + terminal);
    }

    static String failureMessage(String streamName, LineSessionException.Reason reason) {
        if (reason == LineSessionException.Reason.DECODE_ERROR) {
            return "Could not decode line-session " + streamName;
        }
        if (reason == LineSessionException.Reason.RESPONSE_TOO_LARGE) {
            return "Line-session response or pending stdout exceeded configured response limit";
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

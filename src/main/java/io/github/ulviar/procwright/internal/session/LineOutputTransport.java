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
            events.addLast(Event.closed());
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
            events.addLast(Event.fatal(error));
            eventLock.notifyAll();
        }
    }

    void publishFailure(LineSessionException.Reason reason, String message, Throwable failure) {
        offerEvent(Event.failure(reason, message, failure));
    }

    Event take(long deadlineNanos, RequestFailureTracker<LineSessionException> request) {
        synchronized (eventLock) {
            while (events.isEmpty()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw state.recordRequestTimeout(request);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(eventLock, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw state.recordRequestFailure(
                            request, () -> state.failure("Interrupted while waiting for line response", exception));
                }
            }
            Event event = events.removeFirst();
            if (event.kind() == Kind.LINE) {
                pendingLines--;
                pendingCharacters -= event.line().length();
            }
            return event;
        }
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
                offerEvent(Event.eof());
                if (state.recordStdoutEof()) {
                    outputPumps.sealFailureAttribution();
                }
            }
        } catch (IOException exception) {
            malformed.compareAndSet(false, decoder.malformed());
            if (!state.isClosed()) {
                LineSessionException.Reason reason = reasonFor(exception);
                offerEvent(Event.failure(reason, failureMessage(streamName, reason), exception));
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
        offerEvent(Event.failure(
                LineSessionException.Reason.RESPONSE_TOO_LARGE,
                failureMessage("stdout", LineSessionException.Reason.RESPONSE_TOO_LARGE),
                failure));
        failureHandler.closeQuietly(failure);
    }

    private void offerEvent(Event event) {
        if (event.kind() == Kind.LINE) {
            throw new IllegalArgumentException("stdout line events must use offerLine");
        }
        synchronized (eventLock) {
            if (closedEventPublished) {
                return;
            }
            if (event.kind() == Kind.FAILURE) {
                state.recordTerminalFailure(event.reason(), event.message(), event.failure());
            }
            events.addLast(event);
            eventLock.notifyAll();
        }
    }

    private boolean offerLine(StringBuilder line) {
        boolean overflow = false;
        synchronized (eventLock) {
            if (closedEventPublished) {
                return false;
            }
            int lineCharacters = line.length();
            if (pendingLines >= options.stdoutBacklogLines()
                    || lineCharacters > options.stdoutBacklogChars() - pendingCharacters) {
                CommandExecutionException failure =
                        new CommandExecutionException("Line-session stdout backlog overflow");
                state.recordTerminalFailure(
                        LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW,
                        failureMessage("stdout", LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW),
                        failure);
                events.clear();
                pendingLines = 0;
                pendingCharacters = 0;
                events.addLast(Event.failure(
                        LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW,
                        failureMessage("stdout", LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW),
                        failure));
                overflow = true;
            } else {
                String publishedLine = line.toString();
                events.addLast(Event.line(publishedLine));
                pendingLines++;
                pendingCharacters += lineCharacters;
            }
            eventLock.notifyAll();
        }
        if (overflow) {
            failureHandler.closeQuietly(
                    Objects.requireNonNull(state.terminal(), "terminal outcome").primary());
        }
        return !overflow;
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

    record Event(Kind kind, String line, LineSessionException.Reason reason, String message, Throwable failure) {

        static Event line(String line) {
            return new Event(Kind.LINE, line, null, null, null);
        }

        static Event eof() {
            return new Event(Kind.EOF, null, null, null, null);
        }

        static Event failure(LineSessionException.Reason reason, String message, Throwable failure) {
            return new Event(Kind.FAILURE, null, reason, message, failure);
        }

        static Event fatal(Error failure) {
            return new Event(Kind.FATAL, null, null, null, failure);
        }

        static Event closed() {
            return new Event(Kind.CLOSED, null, null, null, null);
        }

        Event {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.LINE) {
                Objects.requireNonNull(line, "line");
            }
            if (kind == Kind.FAILURE) {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(message, "message");
                Objects.requireNonNull(failure, "failure");
            }
            if (kind == Kind.FATAL && !(failure instanceof Error)) {
                throw new IllegalArgumentException("fatal stdout event requires an Error");
            }
        }
    }

    enum Kind {
        LINE,
        EOF,
        CLOSED,
        FAILURE,
        FATAL
    }
}

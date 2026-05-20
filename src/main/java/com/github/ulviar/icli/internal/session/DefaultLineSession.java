package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.command.CommandExecutionException;
import com.github.ulviar.icli.internal.DurationSupport;
import com.github.ulviar.icli.internal.Threading;
import com.github.ulviar.icli.session.LineResponse;
import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.LineSessionException;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.LineTranscript;
import com.github.ulviar.icli.session.ResponseDecoder;
import com.github.ulviar.icli.session.SessionExit;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Line-oriented request/response workflow over an interactive process.
 *
 * <p>Only one request is decoded at a time. Custom response decoders consume stdout lines through a deadline-aware
 * reader, while stderr is drained into the bounded transcript for diagnostics.
 */
public final class DefaultLineSession implements LineSession {

    private static final String OUTPUT_OWNER = "LineSession";

    private final DefaultSession session;
    private final LineSessionOptions options;
    private final BoundedTranscriptBuffer transcript;
    private final BlockingQueue<StdoutEvent> stdoutEvents;
    private final ReentrantLock requestLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultLineSession(DefaultSession session, LineSessionOptions options) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        this.transcript = new BoundedTranscriptBuffer(options.transcriptLimit());
        this.stdoutEvents = new ArrayBlockingQueue<>(options.stdoutBacklogLimit());
        this.session.claimOutputOwner(OUTPUT_OWNER);
        startPumps();
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
        requireRequestLine(line);
        Duration requestTimeout = requirePositive(timeout, "timeout");
        requestLock.lock();
        try {
            ensureOpen();
            Instant started = Instant.now();
            long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
            writeLine(line, options.charset(), deadlineNanos);

            ResponseReader reader = new ResponseReader(deadlineNanos);
            List<String> lines = decode(reader);
            return new LineResponse(lines, lineTranscript(), Duration.between(started, Instant.now()));
        } catch (LineSessionException exception) {
            if (exception.reason() != LineSessionException.Reason.CLOSED) {
                closePreserving(exception);
            }
            throw exception;
        } finally {
            requestLock.unlock();
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
     * Returns the underlying process exit future view.
     *
     * @return process exit future
     */
    public CompletableFuture<SessionExit> onExit() {
        return session.onExit();
    }

    /**
     * Closes the underlying interactive session. Calling this method more than once has no effect.
     */
    @Override
    public void close() {
        closeWithEvent(true);
    }

    private void closeWithEvent(boolean publishClosed) {
        if (closed.compareAndSet(false, true)) {
            if (publishClosed) {
                stdoutEvents.clear();
                stdoutEvents.offer(StdoutEvent.closed());
            }
            session.close();
        }
    }

    private void startPumps() {
        startPump("stdout", session.ownedStdout(OUTPUT_OWNER), true);
        startPump("stderr", session.ownedStderr(OUTPUT_OWNER), false);
    }

    private void startPump(String streamName, InputStream stream, boolean responseStream) {
        Threading.start("icli-line-" + streamName + "-", () -> pump(streamName, stream, responseStream));
    }

    private void pump(String streamName, InputStream stream, boolean responseStream) {
        try (Reader reader = new InputStreamReader(
                stream,
                options.charsetPolicy()
                        .charset()
                        .newDecoder()
                        .onMalformedInput(options.charsetPolicy().malformedInputAction())
                        .onUnmappableCharacter(options.charsetPolicy().unmappableCharacterAction()))) {
            char[] buffer = new char[1024];
            StringBuilder line = new StringBuilder();
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                transcript.appendStream(streamName, buffer, count);
                if (responseStream && !publishLines(line, buffer, count)) {
                    return;
                }
            }
            if (responseStream && line.length() > 0) {
                offerStdoutEvent(StdoutEvent.line(line.toString()));
            }
            if (responseStream) {
                offerStdoutEvent(StdoutEvent.eof());
            }
        } catch (java.io.IOException exception) {
            if (responseStream && !closed.get()) {
                offerStdoutEvent(StdoutEvent.failure(exception));
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
                offerStdoutEvent(StdoutEvent.line(currentLine.toString()));
                currentLine.setLength(0);
            } else {
                currentLine.append(value);
                if (currentLine.length() > options.maxLineChars()) {
                    offerStdoutEvent(StdoutEvent.failure(
                            LineSessionException.Reason.RESPONSE_TOO_LARGE,
                            new CommandExecutionException("Line-session stdout line exceeds maxLineChars")));
                    closeQuietly();
                    return false;
                }
            }
        }
        return true;
    }

    private void offerStdoutEvent(StdoutEvent event) {
        if (stdoutEvents.offer(event)) {
            return;
        }
        stdoutEvents.clear();
        stdoutEvents.offer(StdoutEvent.failure(
                LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW,
                new CommandExecutionException("Line-session stdout backlog overflow")));
        closeQuietly();
    }

    private List<String> decode(ResponseReader reader) {
        try {
            return List.copyOf(options.responseDecoder().decode(reader));
        } catch (LineSessionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(LineSessionException.Reason.DECODER_FAILED, "Response decoder failed", exception);
        }
    }

    private void writeLine(String line, Charset charset, long deadlineNanos) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        Thread writer = Threading.start("icli-line-stdin-", () -> {
            try {
                session.stdin().write((line + "\n").getBytes(charset));
                session.stdin().flush();
                written.complete(null);
            } catch (Throwable throwable) {
                written.completeExceptionally(throwable);
            }
        });
        try {
            written.get(DurationSupport.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (IllegalStateException exception) {
            throw closed(exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            writer.interrupt();
            throw timeout();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.interrupt();
            throw failure(
                    LineSessionException.Reason.FAILURE, "Interrupted while writing line-session stdin", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IllegalStateException illegalState) {
                throw closed(illegalState);
            }
            if (cause instanceof IOException ioException) {
                throw failure(
                        LineSessionException.Reason.BROKEN_PIPE, "Could not write line-session stdin", ioException);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw failure(
                        LineSessionException.Reason.FAILURE, "Could not write line-session stdin", runtimeException);
            }
            throw failure(LineSessionException.Reason.FAILURE, "Could not write line-session stdin", cause);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw closed(null);
        }
    }

    private void closePreserving(LineSessionException exception) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            exception.addSuppressed(closeFailure);
        }
    }

    private void closeQuietly() {
        try {
            closeWithEvent(false);
        } catch (RuntimeException ignored) {
            // The caller will observe the original line-session failure.
        }
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

    private LineTranscript lineTranscript() {
        BoundedTranscriptBuffer.Snapshot snapshot = transcript.snapshot();
        return new LineTranscript(snapshot.text(), snapshot.truncated());
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static String requireRequestLine(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain line separators");
        }
        return line;
    }

    private final class ResponseReader implements ResponseDecoder.Reader {

        private final long deadlineNanos;

        private ResponseReader(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public String readLine() {
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw timeout();
                }

                StdoutEvent event;
                try {
                    event = stdoutEvents.poll(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw failure("Interrupted while waiting for line response", exception);
                }
                if (event == null) {
                    throw timeout();
                }

                switch (event.kind()) {
                    case LINE -> {
                        return event.line();
                    }
                    case EOF -> throw eof();
                    case CLOSED -> throw closed(null);
                    case FAILURE -> throw failure(event.reason(), failureMessage(event.reason()), event.failure());
                }
            }
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
        }
    }

    private enum Kind {
        LINE,
        EOF,
        CLOSED,
        FAILURE
    }
}

package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.internal.DurationSupport;
import com.github.ulviar.icli.session.Expect;
import com.github.ulviar.icli.session.ExpectException;
import com.github.ulviar.icli.session.ExpectOptions;
import com.github.ulviar.icli.session.ExpectTranscriptValues;
import com.github.ulviar.icli.session.LineTranscript;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small expect-style prompt automation helper over a raw {@link Session}.
 *
 * <p>Matching is performed against filtered stdout. Stderr is drained into the transcript for diagnostics.
 */
public final class DefaultExpect implements Expect {

    private static final String OUTPUT_OWNER = "Expect";

    private final DefaultSession session;
    private final ExpectOptions options;
    private final TranscriptBuffer transcript;
    private final int matchBufferLimit;
    private final StringBuilder output = new StringBuilder();
    private final Object monitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int cursor;
    private boolean stdoutEof;
    private Throwable outputFailure;

    public DefaultExpect(DefaultSession session, ExpectOptions options) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        this.transcript = new TranscriptBuffer(options.transcriptLimit());
        this.matchBufferLimit = options.matchBufferLimit();
        this.session.claimOutputOwner(OUTPUT_OWNER);
        startPumps();
    }

    /**
     * Sends text without adding a line separator.
     *
     * @param text text to send
     * @return this helper
     */
    public Expect send(String text) {
        Objects.requireNonNull(text, "text");
        try {
            session.send(text);
            transcript.appendAction("send: " + transcriptValue(text));
            return this;
        } catch (RuntimeException exception) {
            throw failure("Could not send expect text", exception);
        }
    }

    /**
     * Sends text followed by a line feed.
     *
     * @param line line to send
     * @return this helper
     */
    public Expect sendLine(String line) {
        requireLine(line);
        try {
            session.sendLine(line);
            transcript.appendAction("send line: " + transcriptValue(line));
            return this;
        } catch (RuntimeException exception) {
            throw failure("Could not send expect line", exception);
        }
    }

    /**
     * Waits for literal text using the default timeout.
     *
     * @param text expected text
     * @return this helper
     */
    public Expect expectText(String text) {
        return expectText(text, options.timeout());
    }

    /**
     * Waits for literal text.
     *
     * @param text expected text
     * @param timeout match timeout
     * @return this helper
     */
    public Expect expectText(String text, Duration timeout) {
        Objects.requireNonNull(text, "text");
        transcript.appendAction("expect text: " + transcriptValue(text));
        long deadlineNanos = deadlineFromNow(requirePositive(timeout, "timeout"));
        synchronized (monitor) {
            while (true) {
                throwIfFailed();
                int index = output.indexOf(text, cursor);
                if (index >= 0) {
                    cursor = index + text.length();
                    return this;
                }
                waitForMore(deadlineNanos, "Expected text not found: " + text);
            }
        }
    }

    /**
     * Waits for a regular expression match using the default timeout.
     *
     * @param pattern expected pattern
     * @return this helper
     */
    public Expect expectRegex(Pattern pattern) {
        return expectRegex(pattern, options.timeout());
    }

    /**
     * Waits for a regular expression match.
     *
     * @param pattern expected pattern
     * @param timeout match timeout
     * @return this helper
     */
    public Expect expectRegex(Pattern pattern, Duration timeout) {
        Objects.requireNonNull(pattern, "pattern");
        transcript.appendAction("expect regex: " + transcriptValue(pattern.pattern()));
        long deadlineNanos = deadlineFromNow(requirePositive(timeout, "timeout"));
        synchronized (monitor) {
            while (true) {
                throwIfFailed();
                Matcher matcher = pattern.matcher(output);
                matcher.region(cursor, output.length());
                if (matcher.find()) {
                    cursor = matcher.end();
                    return this;
                }
                waitForMore(deadlineNanos, "Expected regex not found: " + pattern.pattern());
            }
        }
    }

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    public LineTranscript transcript() {
        return transcript.snapshot();
    }

    /**
     * Closes this helper and the underlying session. Calling this method more than once has no effect.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
            session.close();
        }
    }

    private void startPumps() {
        startPump("stdout", session.ownedStdout(OUTPUT_OWNER), true);
        startPump("stderr", session.ownedStderr(OUTPUT_OWNER), false);
    }

    private void startPump(String streamName, InputStream stream, boolean matchable) {
        Thread.ofVirtual().name("icli-expect-" + streamName + "-", 0).start(() -> pump(streamName, stream, matchable));
    }

    private void pump(String streamName, InputStream stream, boolean matchable) {
        try (Reader reader = new InputStreamReader(stream, options.charset())) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                String filtered = options.outputFilter().apply(new String(buffer, 0, count));
                if (filtered.isEmpty()) {
                    continue;
                }
                transcript.appendStream(streamName, filtered);
                if (matchable) {
                    synchronized (monitor) {
                        appendOutput(filtered);
                        monitor.notifyAll();
                    }
                }
            }
            if (matchable) {
                synchronized (monitor) {
                    stdoutEof = true;
                    monitor.notifyAll();
                }
            }
        } catch (Throwable throwable) {
            if (!closed.get()) {
                synchronized (monitor) {
                    outputFailure = throwable;
                    monitor.notifyAll();
                }
            }
        }
    }

    private void waitForMore(long deadlineNanos, String message) {
        if (closed.get()) {
            throw closed();
        }
        throwIfFailed();
        if (stdoutEof) {
            throw eof(message);
        }

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw timeout(message);
        }

        try {
            monitor.wait(Math.max(1, DurationSupport.remainingMillis(deadlineNanos)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("Interrupted while waiting for expected output", exception);
        }
    }

    private ExpectException timeout(String message) {
        return new ExpectException(ExpectException.Reason.TIMEOUT, transcript.snapshot(), message);
    }

    private ExpectException eof(String message) {
        return new ExpectException(ExpectException.Reason.EOF, transcript.snapshot(), message);
    }

    private ExpectException closed() {
        return new ExpectException(ExpectException.Reason.CLOSED, transcript.snapshot(), "Expect helper is closed");
    }

    private ExpectException failure(String message, Throwable cause) {
        return new ExpectException(ExpectException.Reason.FAILURE, transcript.snapshot(), message, cause);
    }

    private void appendOutput(String chunk) {
        output.append(chunk);
        if (output.length() > matchBufferLimit) {
            int removed = output.length() - matchBufferLimit;
            output.delete(0, removed);
            cursor = Math.max(0, cursor - removed);
        }
    }

    private void throwIfFailed() {
        if (outputFailure != null) {
            throw failure("Could not read expect output", outputFailure);
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static long deadlineFromNow(Duration duration) {
        return DurationSupport.deadlineFromNow(duration);
    }

    private static String requireLine(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain line separators");
        }
        return line;
    }

    private static String printable(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private String transcriptValue(String text) {
        if (options.transcriptValues() == ExpectTranscriptValues.VERBATIM) {
            return printable(text);
        }
        return "<redacted>";
    }

    private static final class TranscriptBuffer {

        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private String currentStream;
        private boolean atLineStart = true;
        private boolean truncated;

        private TranscriptBuffer(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
        }

        private synchronized void appendAction(String action) {
            if (!atLineStart) {
                text.append('\n');
            }
            text.append(action).append('\n');
            atLineStart = true;
            currentStream = null;
            trim();
        }

        private synchronized void appendStream(String streamName, String chunk) {
            for (int index = 0; index < chunk.length(); index++) {
                if (atLineStart || !streamName.equals(currentStream)) {
                    if (!atLineStart) {
                        text.append('\n');
                    }
                    text.append(streamName).append(": ");
                    atLineStart = false;
                    currentStream = streamName;
                }
                char value = chunk.charAt(index);
                text.append(value);
                if (value == '\n') {
                    atLineStart = true;
                    currentStream = null;
                }
            }
            trim();
        }

        private synchronized LineTranscript snapshot() {
            return new LineTranscript(text.toString(), truncated);
        }

        private void trim() {
            if (text.length() > limit) {
                text.delete(0, text.length() - limit);
                truncated = true;
            }
        }
    }
}

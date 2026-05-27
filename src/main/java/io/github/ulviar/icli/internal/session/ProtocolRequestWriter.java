package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.session.ProtocolSessionException;
import io.github.ulviar.icli.session.ProtocolSessionOptions;
import io.github.ulviar.icli.session.ProtocolWriter;
import java.io.IOException;
import java.util.Objects;

/**
 * Deadline-aware request writer that owns request byte and text limits.
 */
final class ProtocolRequestWriter implements ProtocolWriter {

    private final DefaultSession session;
    private final ProtocolSessionOptions options;
    private final long deadlineNanos;
    private final ProtocolRuntimeFailures failures;
    private long writtenBytes;
    private long writtenChars;

    ProtocolRequestWriter(
            DefaultSession session,
            ProtocolSessionOptions options,
            long deadlineNanos,
            ProtocolRuntimeFailures failures) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        this.deadlineNanos = deadlineNanos;
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    @Override
    public void write(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        checkDeadline();
        writtenBytes += bytes.length;
        if (writtenBytes > options.maxRequestBytes()) {
            throw failures.failure(
                    ProtocolSessionException.Reason.REQUEST_TOO_LARGE,
                    "Protocol request exceeds maxRequestBytes",
                    null);
        }
        try {
            session.stdin().write(bytes);
        } catch (IOException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.BROKEN_PIPE, "Could not write protocol request", exception);
        } catch (IllegalStateException exception) {
            throw failures.closed(exception);
        }
    }

    @Override
    public void write(String text) {
        Objects.requireNonNull(text, "text");
        writtenChars += text.length();
        if (writtenChars > options.maxRequestChars()) {
            throw failures.failure(
                    ProtocolSessionException.Reason.REQUEST_TOO_LARGE,
                    "Protocol request exceeds maxRequestChars",
                    null);
        }
        write(text.getBytes(options.charsetPolicy().charset()));
    }

    @Override
    public void writeLine(String line) {
        write(Objects.requireNonNull(line, "line") + "\n");
    }

    @Override
    public void flush() {
        checkDeadline();
        try {
            session.stdin().flush();
        } catch (IOException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.BROKEN_PIPE, "Could not flush protocol request", exception);
        } catch (IllegalStateException exception) {
            throw failures.closed(exception);
        }
    }

    private void checkDeadline() {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw failures.timeout(null);
        }
    }
}

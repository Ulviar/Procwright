/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.IOException;
import java.util.Objects;

/**
 * Deadline-aware request writer that owns request byte and text limits.
 */
final class ProtocolRequestWriter implements ProtocolWriter {

    private final DefaultSession session;
    private final ProtocolSessionSettings options;
    private final long deadlineNanos;
    private final ProtocolRuntimeFailures failures;
    private final RequestCapabilityScope capabilityScope;
    private long writtenBytes;
    private long writtenChars;
    private ProtocolSessionException terminalFailure;
    private Error terminalError;

    ProtocolRequestWriter(
            DefaultSession session,
            ProtocolSessionSettings options,
            long deadlineNanos,
            ProtocolRuntimeFailures failures,
            RequestCapabilityScope capabilityScope) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        this.deadlineNanos = deadlineNanos;
        this.failures = Objects.requireNonNull(failures, "failures");
        this.capabilityScope = Objects.requireNonNull(capabilityScope, "capabilityScope");
    }

    @Override
    public void write(byte[] bytes) {
        ensureWritable();
        Objects.requireNonNull(bytes, "bytes");
        writeBytes(bytes, 0, bytes.length);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        ensureWritable();
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        writeBytes(bytes, offset, length);
    }

    @Override
    public long remainingByteCapacity() {
        ensureWritable();
        checkDeadline();
        return options.maxRequestBytes() - writtenBytes;
    }

    @Override
    public void ensureByteCapacity(long byteCount) {
        ensureWritable();
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        checkDeadline();
        if (byteCount > options.maxRequestBytes() - writtenBytes) {
            throw recordFailure(failures.failure(
                    ProtocolSessionException.Reason.REQUEST_TOO_LARGE,
                    "Protocol request exceeds maxRequestBytes",
                    null));
        }
    }

    private void writeBytes(byte[] bytes, int offset, int length) {
        ensureByteCapacity(length);
        writtenBytes += length;
        invokeDelegate(
                () -> session.stdin().write(bytes, offset, length),
                "Could not write protocol request",
                "Protocol process exited before the request could be written");
    }

    @Override
    public void write(String text) {
        ensureWritable();
        writeText(Objects.requireNonNull(text, "text"));
    }

    private void writeText(CharSequence text) {
        ensureWritable();
        if (text.length() > options.maxRequestChars() - writtenChars) {
            throw recordFailure(failures.failure(
                    ProtocolSessionException.Reason.REQUEST_TOO_LARGE,
                    "Protocol request exceeds maxRequestChars",
                    null));
        }
        int remainingBytes = (int) (options.maxRequestBytes() - writtenBytes);
        BoundedTextEncoder.EncodedText encoded = encodeText(text, remainingBytes);
        if (encoded == null) {
            throw recordFailure(failures.failure(
                    ProtocolSessionException.Reason.REQUEST_TOO_LARGE,
                    "Protocol request exceeds maxRequestBytes",
                    null));
        }
        writtenChars += text.length();
        writtenBytes += encoded.length();
        invokeDelegate(
                () -> session.stdin().write(encoded.bytes(), 0, encoded.length()),
                "Could not write protocol request",
                "Protocol process exited before the request could be written");
    }

    private BoundedTextEncoder.EncodedText encodeText(CharSequence text, int remainingBytes) {
        try {
            return BoundedTextEncoder.encodeUpTo(
                    text, options.charsetPolicy().charset(), remainingBytes, this::checkDeadline);
        } catch (ProtocolSessionException exception) {
            if (exception == terminalFailure) {
                throw exception;
            }
            throw recordFailure(failures.failure(
                    ProtocolSessionException.Reason.FAILURE, "Could not encode protocol request", exception));
        } catch (RuntimeException exception) {
            throw recordFailure(failures.failure(
                    ProtocolSessionException.Reason.FAILURE, "Could not encode protocol request", exception));
        } catch (Error error) {
            recordFailure(failures.failure(
                    ProtocolSessionException.Reason.FAILURE, "Could not encode protocol request", error));
            terminalError = error;
            throw error;
        }
    }

    @Override
    public void writeLine(String line) {
        ensureWritable();
        writeText(new LineFeedTerminatedText(Objects.requireNonNull(line, "line")));
    }

    @Override
    public void flush() {
        ensureWritable();
        checkDeadline();
        invokeDelegate(
                () -> session.stdin().flush(),
                "Could not flush protocol request",
                "Protocol process exited before the request could be flushed");
    }

    private void invokeDelegate(DelegateOperation operation, String failureMessage, String processExitedMessage) {
        try {
            operation.run();
        } catch (IOException exception) {
            throw recordFailure(
                    failures.failure(ProtocolSessionException.Reason.BROKEN_PIPE, failureMessage, exception));
        } catch (ProcessExitedException exception) {
            throw recordFailure(
                    failures.failure(ProtocolSessionException.Reason.PROCESS_EXITED, processExitedMessage, exception));
        } catch (SessionStdinClosedException exception) {
            throw recordFailure(failures.closed(exception));
        } catch (RuntimeException exception) {
            throw recordFailure(failures.failure(ProtocolSessionException.Reason.FAILURE, failureMessage, exception));
        } catch (Error error) {
            recordFailure(failures.failure(ProtocolSessionException.Reason.FAILURE, failureMessage, error));
            terminalError = error;
            throw error;
        }
    }

    private void checkDeadline() {
        ensureWritable();
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw recordFailure(failures.timeout(null));
        }
    }

    private void ensureWritable() {
        capabilityScope.verifyAccess();
        throwIfFailed();
    }

    void throwIfFailed() {
        if (terminalError != null) {
            throw terminalError;
        }
        if (terminalFailure != null) {
            throw terminalFailure;
        }
    }

    void throwIfError() {
        if (terminalError != null) {
            throw terminalError;
        }
    }

    private ProtocolSessionException recordFailure(ProtocolSessionException failure) {
        if (terminalFailure == null) {
            terminalFailure = Objects.requireNonNull(failure, "failure");
        }
        return terminalFailure;
    }

    @FunctionalInterface
    private interface DelegateOperation {

        void run() throws IOException;
    }
}

package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.command.CommandExecutionException;
import io.github.ulviar.icli.internal.DurationSupport;
import io.github.ulviar.icli.internal.Threading;
import io.github.ulviar.icli.session.ProtocolAdapter;
import io.github.ulviar.icli.session.ProtocolReader;
import io.github.ulviar.icli.session.ProtocolReaders;
import io.github.ulviar.icli.session.ProtocolSession;
import io.github.ulviar.icli.session.ProtocolSessionException;
import io.github.ulviar.icli.session.ProtocolSessionOptions;
import io.github.ulviar.icli.session.ProtocolTranscript;
import io.github.ulviar.icli.session.ProtocolWriter;
import io.github.ulviar.icli.session.SessionExit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Generic request/response workflow over an interactive process.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class DefaultProtocolSession<I, O> implements ProtocolSession<I, O> {

    private static final String OUTPUT_OWNER = "ProtocolSession";
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final DefaultSession session;
    private final ProtocolAdapter<I, O> adapter;
    private final ProtocolSessionOptions options;
    private final ProtocolTranscriptBuffer transcript;
    private final OutputQueue stdout;
    private final OutputQueue stderr;
    private final ReentrantLock requestLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultProtocolSession(
            DefaultSession session, ProtocolAdapter<I, O> adapter, ProtocolSessionOptions options) {
        this.session = Objects.requireNonNull(session, "session");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.options = Objects.requireNonNull(options, "options");
        this.transcript = new ProtocolTranscriptBuffer(options.transcriptLimit(), options.charsetPolicy());
        this.stdout = new OutputQueue(options.stdoutBacklogLimit());
        this.stderr = new OutputQueue(options.stdoutBacklogLimit());
        this.session.claimOutputOwner(OUTPUT_OWNER);
        startPumps();
    }

    @Override
    public O request(I request) {
        return request(request, options.requestTimeout());
    }

    @Override
    public O request(I request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        Duration requestTimeout = requirePositive(timeout, "timeout");
        requestLock.lock();
        try {
            ensureOpen();
            long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
            writeRequest(request, deadlineNanos);
            return readResponse(deadlineNanos);
        } catch (ProtocolSessionException exception) {
            if (exception.reason() != ProtocolSessionException.Reason.CLOSED) {
                closePreserving(exception);
            }
            throw exception;
        } finally {
            requestLock.unlock();
        }
    }

    @Override
    public ProtocolTranscript transcript() {
        return protocolTranscript();
    }

    @Override
    public CompletableFuture<SessionExit> onExit() {
        return session.onExit();
    }

    @Override
    public void close() {
        closeWithEvent(true);
    }

    private void closeWithEvent(boolean publishClosed) {
        if (closed.compareAndSet(false, true)) {
            if (publishClosed) {
                stdout.close();
                stderr.close();
            }
            session.close();
        }
    }

    private void startPumps() {
        startPump("stdout", session.ownedStdout(OUTPUT_OWNER), stdout);
        startPump("stderr", session.ownedStderr(OUTPUT_OWNER), stderr);
    }

    private void startPump(String streamName, InputStream stream, OutputQueue output) {
        Threading.start("icli-protocol-" + streamName + "-", () -> pump(streamName, stream, output));
    }

    private void pump(String streamName, InputStream stream, OutputQueue output) {
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                transcript.appendStream(streamName, buffer, count);
                if (!output.offer(Arrays.copyOf(buffer, count))) {
                    failOutputBacklogOverflow();
                    closeQuietly();
                    return;
                }
            }
            output.eof();
        } catch (IOException exception) {
            if (!closed.get()) {
                output.failure(reasonFor(exception), exception);
            }
        }
    }

    private void writeRequest(I request, long deadlineNanos) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        Thread writer = Threading.start("icli-protocol-stdin-", () -> {
            try {
                adapter.writeRequest(request, new Writer(deadlineNanos));
                written.complete(null);
            } catch (Throwable throwable) {
                written.completeExceptionally(throwable);
            }
        });
        try {
            written.get(DurationSupport.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            writer.interrupt();
            throw timeout(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.interrupt();
            throw failure(
                    ProtocolSessionException.Reason.FAILURE, "Interrupted while writing protocol request", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            if (cause instanceof IOException ioException) {
                throw failure(
                        ProtocolSessionException.Reason.BROKEN_PIPE, "Could not write protocol request", ioException);
            }
            if (cause instanceof IllegalStateException illegalState) {
                throw closed(illegalState);
            }
            throw failure(ProtocolSessionException.Reason.FAILURE, "Could not write protocol request", cause);
        }
    }

    private O readResponse(long deadlineNanos) {
        try {
            ResponseBudget budget = new ResponseBudget(options.maxResponseBytes(), options.maxResponseChars());
            return adapter.readResponse(
                    new Readers(new Reader(stdout, deadlineNanos, budget), new Reader(stderr, deadlineNanos, budget)));
        } catch (ProtocolSessionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                    "Protocol response decoder failed",
                    exception);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw closed(null);
        }
    }

    private ProtocolSessionException timeout(Throwable cause) {
        return new ProtocolSessionException(
                ProtocolSessionException.Reason.TIMEOUT, protocolTranscript(), "Protocol request timed out", cause);
    }

    private ProtocolSessionException closed(Throwable cause) {
        return cause == null
                ? new ProtocolSessionException(
                        ProtocolSessionException.Reason.CLOSED, protocolTranscript(), "Protocol session is closed")
                : new ProtocolSessionException(
                        ProtocolSessionException.Reason.CLOSED,
                        protocolTranscript(),
                        "Protocol session is closed",
                        cause);
    }

    private ProtocolSessionException eof() {
        OptionalInt exitCode = exitCodeSnapshot(Duration.ofMillis(100));
        ProtocolSessionException.Reason reason = exitCode.isPresent()
                ? ProtocolSessionException.Reason.PROCESS_EXITED
                : ProtocolSessionException.Reason.EOF;
        return new ProtocolSessionException(
                reason, protocolTranscript(), exitCode, "Protocol session reached EOF", null);
    }

    private ProtocolSessionException failure(ProtocolSessionException.Reason reason, String message, Throwable cause) {
        return new ProtocolSessionException(reason, protocolTranscript(), exitCodeSnapshot(), message, cause);
    }

    private ProtocolTranscript protocolTranscript() {
        return transcript.snapshot();
    }

    private OptionalInt exitCodeSnapshot() {
        return exitCodeSnapshot(Duration.ZERO);
    }

    private OptionalInt exitCodeSnapshot(Duration wait) {
        CompletableFuture<SessionExit> exit = session.onExit();
        if (!exit.isDone()) {
            try {
                if (!wait.isZero() && !wait.isNegative()) {
                    return exit.get(DurationSupport.saturatedMillis(wait), TimeUnit.MILLISECONDS)
                            .exitCode();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return OptionalInt.empty();
            } catch (ExecutionException | TimeoutException exception) {
                return OptionalInt.empty();
            }
        }
        try {
            return exit.getNow(new SessionExit(OptionalInt.empty(), false)).exitCode();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    private void closePreserving(ProtocolSessionException exception) {
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
            // The reader observes the original protocol failure.
        }
    }

    private void failOutputBacklogOverflow() {
        CommandExecutionException failure = new CommandExecutionException("Protocol output backlog overflow");
        stdout.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
        stderr.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
    }

    private static ProtocolSessionException.Reason reasonFor(IOException exception) {
        return exception instanceof CharacterCodingException
                ? ProtocolSessionException.Reason.DECODE_ERROR
                : ProtocolSessionException.Reason.FAILURE;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private final class Writer implements ProtocolWriter {

        private final long deadlineNanos;
        private long writtenBytes;
        private long writtenChars;

        private Writer(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public void write(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            checkDeadline();
            writtenBytes += bytes.length;
            if (writtenBytes > options.maxRequestBytes()) {
                throw failure(
                        ProtocolSessionException.Reason.REQUEST_TOO_LARGE,
                        "Protocol request exceeds maxRequestBytes",
                        null);
            }
            try {
                session.stdin().write(bytes);
            } catch (IOException exception) {
                throw failure(
                        ProtocolSessionException.Reason.BROKEN_PIPE, "Could not write protocol request", exception);
            } catch (IllegalStateException exception) {
                throw closed(exception);
            }
        }

        @Override
        public void write(String text) {
            Objects.requireNonNull(text, "text");
            writtenChars += text.length();
            if (writtenChars > options.maxRequestChars()) {
                throw failure(
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
                throw failure(
                        ProtocolSessionException.Reason.BROKEN_PIPE, "Could not flush protocol request", exception);
            } catch (IllegalStateException exception) {
                throw closed(exception);
            }
        }

        private void checkDeadline() {
            if (deadlineNanos - System.nanoTime() <= 0) {
                throw timeout(null);
            }
        }
    }

    private record Readers(ProtocolReader stdout, ProtocolReader stderr) implements ProtocolReaders {}

    private final class Reader implements ProtocolReader {

        private final OutputQueue output;
        private final long deadlineNanos;
        private final ResponseBudget budget;
        private byte[] current = EMPTY_BYTES;
        private int offset;

        private Reader(OutputQueue output, long deadlineNanos, ResponseBudget budget) {
            this.output = output;
            this.deadlineNanos = deadlineNanos;
            this.budget = budget;
        }

        @Override
        public byte readByte() {
            return (byte) readOneUnsignedByte();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            Objects.requireNonNull(buffer, "buffer");
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            int copied = 0;
            while (copied == 0) {
                if (this.offset < current.length) {
                    int count = Math.min(length, current.length - this.offset);
                    budget.addBytes(count);
                    System.arraycopy(current, this.offset, buffer, offset, count);
                    this.offset += count;
                    copied += count;
                    if (this.offset == current.length) {
                        current = EMPTY_BYTES;
                        this.offset = 0;
                    }
                    return copied;
                }
                OutputEvent event = output.take(deadlineNanos);
                switch (event.kind()) {
                    case BYTES -> current = event.bytes();
                    case EOF -> throw eof();
                    case CLOSED -> throw closed(null);
                    case FAILURE -> throw failure(event.reason(), failureMessage(event.reason()), event.failure());
                }
            }
            return copied;
        }

        private int readOneUnsignedByte() {
            while (true) {
                if (offset < current.length) {
                    budget.addBytes(1);
                    int value = current[offset++] & 0xff;
                    if (offset == current.length) {
                        current = EMPTY_BYTES;
                        offset = 0;
                    }
                    return value;
                }
                OutputEvent event = output.take(deadlineNanos);
                switch (event.kind()) {
                    case BYTES -> current = event.bytes();
                    case EOF -> throw eof();
                    case CLOSED -> throw closed(null);
                    case FAILURE -> throw failure(event.reason(), failureMessage(event.reason()), event.failure());
                }
            }
        }

        @Override
        public byte[] readExactly(int length) {
            if (length < 0) {
                throw new IllegalArgumentException("length must not be negative");
            }
            byte[] result = new byte[length];
            int read = 0;
            while (read < length) {
                read += read(result, read, length - read);
            }
            return result;
        }

        @Override
        public byte[] readUntil(byte delimiter, int maxBytes) {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            while (bytes.size() < maxBytes) {
                byte value = (byte) readOneUnsignedByte();
                bytes.write(value);
                if (value == delimiter) {
                    return bytes.toByteArray();
                }
            }
            throw failure(
                    ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                    "Protocol response exceeds delimiter read limit",
                    null);
        }

        @Override
        public String readLine(int maxChars) {
            if (maxChars <= 0) {
                throw new IllegalArgumentException("maxChars must be positive");
            }
            int readLimit = maxChars == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxChars + 1;
            String line = readTextUntil((byte) '\n', readLimit);
            if (line.endsWith("\n")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.length() > maxChars) {
                throw failure(
                        ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                        "Protocol response line exceeds maxChars",
                        null);
            }
            return line;
        }

        @Override
        public String readTextUntil(byte delimiter, int maxChars) {
            if (maxChars <= 0) {
                throw new IllegalArgumentException("maxChars must be positive");
            }
            CharsetDecoder decoder = options.charsetPolicy()
                    .charset()
                    .newDecoder()
                    .onMalformedInput(options.charsetPolicy().malformedInputAction())
                    .onUnmappableCharacter(options.charsetPolicy().unmappableCharacterAction());
            ByteBuffer input = ByteBuffer.allocate(16);
            CharBuffer output = CharBuffer.allocate(128);
            StringBuilder text = new StringBuilder(Math.min(maxChars, 128));
            while (true) {
                byte value = (byte) readOneUnsignedByte();
                if (!input.hasRemaining()) {
                    input = grow(input);
                }
                input.put(value);
                decodeTextChunk(decoder, input, output, text, maxChars, false);
                if (value == delimiter) {
                    decodeTextChunk(decoder, input, output, text, maxChars, true);
                    return text.toString();
                }
            }
        }

        private void decodeTextChunk(
                CharsetDecoder decoder,
                ByteBuffer input,
                CharBuffer output,
                StringBuilder text,
                int maxChars,
                boolean endOfInput) {
            input.flip();
            while (true) {
                CoderResult result = decoder.decode(input, output, endOfInput);
                appendDecoded(output, text, maxChars);
                if (result.isOverflow()) {
                    continue;
                }
                if (result.isUnderflow()) {
                    break;
                }
                throwDecodeFailure(result);
            }
            input.compact();
            if (endOfInput) {
                while (true) {
                    CoderResult result = decoder.flush(output);
                    appendDecoded(output, text, maxChars);
                    if (result.isOverflow()) {
                        continue;
                    }
                    if (result.isUnderflow()) {
                        return;
                    }
                    throwDecodeFailure(result);
                }
            }
        }

        private void appendDecoded(CharBuffer output, StringBuilder text, int maxChars) {
            output.flip();
            int decodedChars = output.remaining();
            text.append(output);
            output.clear();
            if (decodedChars > 0) {
                budget.addChars(decodedChars);
            }
            if (text.length() > maxChars) {
                throw failure(
                        ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                        "Protocol response text exceeds maxChars",
                        null);
            }
        }

        private void throwDecodeFailure(CoderResult result) {
            try {
                result.throwException();
            } catch (CharacterCodingException exception) {
                throw failure(
                        ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
            }
            throw failure(ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", null);
        }

        private ByteBuffer grow(ByteBuffer input) {
            input.flip();
            ByteBuffer grown = ByteBuffer.allocate(input.capacity() * 2);
            grown.put(input);
            return grown;
        }
    }

    private final class ResponseBudget {

        private final int maxBytes;
        private final int maxChars;
        private long bytes;
        private long chars;

        private ResponseBudget(int maxBytes, int maxChars) {
            this.maxBytes = maxBytes;
            this.maxChars = maxChars;
        }

        private void addBytes(int count) {
            bytes += count;
            if (bytes > maxBytes) {
                throw failure(
                        ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                        "Protocol response exceeds maxResponseBytes",
                        null);
            }
        }

        private void addChars(int count) {
            chars += count;
            if (chars > maxChars) {
                throw failure(
                        ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                        "Protocol response exceeds maxResponseChars",
                        null);
            }
        }
    }

    private static String failureMessage(ProtocolSessionException.Reason reason) {
        return switch (reason) {
            case DECODE_ERROR -> "Could not decode protocol output";
            case RESPONSE_TOO_LARGE -> "Protocol response exceeded configured size limit";
            case OUTPUT_BACKLOG_OVERFLOW -> "Protocol output backlog overflow";
            default -> "Could not read protocol output";
        };
    }

    private final class OutputQueue {

        private final int byteLimit;
        private final ArrayDeque<OutputEvent> events = new ArrayDeque<>();
        private long pendingBytes;

        private OutputQueue(int byteLimit) {
            this.byteLimit = byteLimit;
        }

        private synchronized boolean offer(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            if (pendingBytes + bytes.length > byteLimit) {
                return false;
            }
            pendingBytes += bytes.length;
            events.addLast(OutputEvent.bytes(bytes));
            notifyAll();
            return true;
        }

        private synchronized void eof() {
            events.addLast(OutputEvent.eof());
            notifyAll();
        }

        private synchronized void close() {
            events.clear();
            pendingBytes = 0;
            events.addLast(OutputEvent.closed());
            notifyAll();
        }

        private synchronized void failure(ProtocolSessionException.Reason reason, Throwable failure) {
            events.addLast(OutputEvent.failure(reason, failure));
            notifyAll();
        }

        private synchronized void failAndClear(ProtocolSessionException.Reason reason, Throwable failure) {
            events.clear();
            pendingBytes = 0;
            events.addLast(OutputEvent.failure(reason, failure));
            notifyAll();
        }

        private synchronized OutputEvent take(long deadlineNanos) {
            while (events.isEmpty()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw timeout(null);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw DefaultProtocolSession.this.failure(
                            ProtocolSessionException.Reason.FAILURE,
                            "Interrupted while waiting for protocol output",
                            exception);
                }
            }
            OutputEvent event = events.removeFirst();
            if (event.kind() == OutputKind.BYTES) {
                pendingBytes -= event.bytes().length;
            }
            return event;
        }
    }

    private record OutputEvent(
            OutputKind kind, byte[] bytes, ProtocolSessionException.Reason reason, Throwable failure) {

        private static OutputEvent bytes(byte[] bytes) {
            return new OutputEvent(OutputKind.BYTES, bytes, null, null);
        }

        private static OutputEvent eof() {
            return new OutputEvent(OutputKind.EOF, null, null, null);
        }

        private static OutputEvent closed() {
            return new OutputEvent(OutputKind.CLOSED, null, null, null);
        }

        private static OutputEvent failure(ProtocolSessionException.Reason reason, Throwable failure) {
            return new OutputEvent(OutputKind.FAILURE, null, reason, failure);
        }

        private OutputEvent {
            Objects.requireNonNull(kind, "kind");
            if (kind == OutputKind.BYTES) {
                Objects.requireNonNull(bytes, "bytes");
            }
            if (kind == OutputKind.FAILURE) {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(failure, "failure");
            }
        }
    }

    private enum OutputKind {
        BYTES,
        EOF,
        CLOSED,
        FAILURE
    }
}

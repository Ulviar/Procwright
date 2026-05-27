package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.session.ProtocolReader;
import io.github.ulviar.icli.session.ProtocolSessionException;
import io.github.ulviar.icli.session.ProtocolSessionOptions;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.Objects;

/**
 * Deadline-aware response reader that owns delimiter, line, text decoding, and response budget accounting.
 */
final class ProtocolResponseReader implements ProtocolReader {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private final ProtocolOutputQueue output;
    private final ProtocolSessionOptions options;
    private final long deadlineNanos;
    private final ProtocolResponseBudget budget;
    private final ProtocolRuntimeFailures failures;
    private byte[] current = EMPTY_BYTES;
    private int offset;

    ProtocolResponseReader(
            ProtocolOutputQueue output,
            ProtocolSessionOptions options,
            long deadlineNanos,
            ProtocolResponseBudget budget,
            ProtocolRuntimeFailures failures) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        this.deadlineNanos = deadlineNanos;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.failures = Objects.requireNonNull(failures, "failures");
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
        while (true) {
            if (this.offset < current.length) {
                int count = Math.min(length, current.length - this.offset);
                budget.addBytes(count);
                System.arraycopy(current, this.offset, buffer, offset, count);
                this.offset += count;
                clearConsumedCurrent();
                return count;
            }
            loadNextOutputEvent();
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
        throw failures.failure(
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
            throw failures.failure(
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

    private int readOneUnsignedByte() {
        while (true) {
            if (offset < current.length) {
                budget.addBytes(1);
                int value = current[offset++] & 0xff;
                clearConsumedCurrent();
                return value;
            }
            loadNextOutputEvent();
        }
    }

    private void loadNextOutputEvent() {
        ProtocolOutputEvent event = output.take(deadlineNanos, failures);
        switch (event.kind()) {
            case BYTES -> current = event.bytes();
            case EOF -> throw failures.eof();
            case CLOSED -> throw failures.closed(null);
            case FAILURE -> throw failures.failure(event.reason(), failureMessage(event.reason()), event.failure());
        }
    }

    private void clearConsumedCurrent() {
        if (offset == current.length) {
            current = EMPTY_BYTES;
            offset = 0;
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
            flushDecoder(decoder, output, text, maxChars);
        }
    }

    private void flushDecoder(CharsetDecoder decoder, CharBuffer output, StringBuilder text, int maxChars) {
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

    private void appendDecoded(CharBuffer output, StringBuilder text, int maxChars) {
        output.flip();
        int decodedChars = output.remaining();
        text.append(output);
        output.clear();
        if (decodedChars > 0) {
            budget.addChars(decodedChars);
        }
        if (text.length() > maxChars) {
            throw failures.failure(
                    ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                    "Protocol response text exceeds maxChars",
                    null);
        }
    }

    private void throwDecodeFailure(CoderResult result) {
        try {
            result.throwException();
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
        }
        throw failures.failure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", null);
    }

    private ByteBuffer grow(ByteBuffer input) {
        input.flip();
        ByteBuffer grown = ByteBuffer.allocate(input.capacity() * 2);
        grown.put(input);
        return grown;
    }

    private static String failureMessage(ProtocolSessionException.Reason reason) {
        return switch (reason) {
            case DECODE_ERROR -> "Could not decode protocol output";
            case RESPONSE_TOO_LARGE -> "Protocol response exceeded configured size limit";
            case OUTPUT_BACKLOG_OVERFLOW -> "Protocol output backlog overflow";
            default -> "Could not read protocol output";
        };
    }
}

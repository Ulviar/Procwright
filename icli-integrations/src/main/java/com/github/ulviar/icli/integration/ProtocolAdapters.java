package com.github.ulviar.icli.integration;

import com.github.ulviar.icli.command.CharsetPolicy;
import com.github.ulviar.icli.session.ProtocolAdapter;
import com.github.ulviar.icli.session.ProtocolReader;
import com.github.ulviar.icli.session.ProtocolReaders;
import com.github.ulviar.icli.session.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Protocol adapters built on top of iCLI protocol sessions.
 */
public final class ProtocolAdapters {

    private static final int DEFAULT_MAX_HEADER_BYTES = 8192;

    private ProtocolAdapters() {}

    /**
     * Returns a JSON Lines adapter.
     *
     * @param maxLineChars maximum response line characters
     * @return JSON Lines adapter
     */
    public static ProtocolAdapter<JsonValue, JsonValue> jsonLines(int maxLineChars) {
        if (maxLineChars <= 0) {
            throw new IllegalArgumentException("maxLineChars must be positive");
        }
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(JsonValue request, ProtocolWriter writer) {
                writer.writeLine(JsonLines.line(request));
                writer.flush();
            }

            @Override
            public JsonValue readResponse(ProtocolReaders readers) {
                return JsonLines.parseLine(readers.stdout().readLine(maxLineChars));
            }
        };
    }

    /**
     * Returns a delimiter-framed bytes adapter. The delimiter is appended to requests and removed from responses.
     *
     * @param delimiter frame delimiter
     * @param maxFrameBytes maximum response frame bytes including delimiter
     * @return delimiter-framed adapter
     */
    public static ProtocolAdapter<byte[], byte[]> delimiter(byte delimiter, int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(byte[] request, ProtocolWriter writer) {
                byte[] payload = Objects.requireNonNull(request, "request").clone();
                for (byte value : payload) {
                    if (value == delimiter) {
                        throw new IntegrationProtocolException(
                                IntegrationProtocolException.Reason.BAD_FRAME,
                                "Request payload must not contain the frame delimiter");
                    }
                }
                writer.write(payload);
                writer.write(new byte[] {delimiter});
                writer.flush();
            }

            @Override
            public byte[] readResponse(ProtocolReaders readers) {
                byte[] frame = readers.stdout().readUntil(delimiter, maxFrameBytes);
                return java.util.Arrays.copyOf(frame, frame.length - 1);
            }
        };
    }

    /**
     * Returns a Content-Length framed JSON adapter.
     *
     * @param maxFrameBytes maximum response body bytes
     * @return Content-Length JSON adapter
     */
    public static ProtocolAdapter<JsonValue, JsonValue> contentLengthJson(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(JsonValue request, ProtocolWriter writer) {
                writer.write(ContentLengthJsonFrames.frame(request));
                writer.flush();
            }

            @Override
            public JsonValue readResponse(ProtocolReaders readers) {
                int length = readContentLength(readers.stdout());
                if (length > maxFrameBytes) {
                    throw new IntegrationProtocolException(
                            IntegrationProtocolException.Reason.OVERSIZED_FRAME, "Frame exceeds maxFrameBytes");
                }
                byte[] body = readers.stdout().readExactly(length);
                return JsonCodec.parse(decodeUtf8(body));
            }
        };
    }

    /**
     * Maps a JSON protocol adapter to domain request and response types.
     *
     * @param encode request encoder
     * @param decode response decoder
     * @param transport JSON transport adapter
     * @param <I> request type
     * @param <O> response type
     * @return typed protocol adapter
     */
    public static <I, O> ProtocolAdapter<I, O> typedJson(
            Function<? super I, JsonValue> encode,
            Function<? super JsonValue, ? extends O> decode,
            ProtocolAdapter<JsonValue, JsonValue> transport) {
        Objects.requireNonNull(encode, "encode");
        Objects.requireNonNull(decode, "decode");
        Objects.requireNonNull(transport, "transport");
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(I request, ProtocolWriter writer) {
                transport.writeRequest(encode.apply(request), writer);
            }

            @Override
            public O readResponse(ProtocolReaders readers) {
                return decode.apply(transport.readResponse(readers));
            }
        };
    }

    private static int readContentLength(ProtocolReader input) {
        String headerBlock = readHeaderBlock(input);
        Integer contentLength = null;
        for (String line : headerBlock.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IntegrationProtocolException(
                        IntegrationProtocolException.Reason.BAD_HEADER, "Malformed frame header");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if ("content-length".equals(name)) {
                if (contentLength != null) {
                    throw new IntegrationProtocolException(
                            IntegrationProtocolException.Reason.BAD_HEADER, "Duplicate Content-Length header");
                }
                contentLength = parseLength(value);
            }
        }
        if (contentLength == null) {
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.MISSING_LENGTH, "Content-Length header is required");
        }
        return contentLength;
    }

    private static String readHeaderBlock(ProtocolReader input) {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        while (header.size() <= DEFAULT_MAX_HEADER_BYTES) {
            int value = input.readByte() & 0xFF;
            header.write(value);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && value == '\n') {
                return header.toString(StandardCharsets.US_ASCII);
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = value;
        }
        throw new IntegrationProtocolException(
                IntegrationProtocolException.Reason.BAD_HEADER, "Frame headers exceed limit");
    }

    private static int parseLength(String value) {
        try {
            int length = Integer.parseInt(value);
            if (length < 0) {
                throw new IntegrationProtocolException(
                        IntegrationProtocolException.Reason.BAD_LENGTH, "Content-Length must not be negative");
            }
            return length;
        } catch (NumberFormatException exception) {
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.BAD_LENGTH, "Content-Length must be a decimal integer");
        }
    }

    private static String decodeUtf8(byte[] body) {
        try {
            return CharsetPolicy.report(StandardCharsets.UTF_8).decode(body);
        } catch (CharacterCodingException exception) {
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.INVALID_ENCODING, "Frame body must be valid UTF-8", exception);
        }
    }
}

package io.github.ulviar.icli.integration;

import static io.github.ulviar.icli.integration.IntegrationProtocolException.Reason.BAD_HEADER;
import static io.github.ulviar.icli.integration.IntegrationProtocolException.Reason.BAD_LENGTH;
import static io.github.ulviar.icli.integration.IntegrationProtocolException.Reason.EOF;
import static io.github.ulviar.icli.integration.IntegrationProtocolException.Reason.INVALID_ENCODING;
import static io.github.ulviar.icli.integration.IntegrationProtocolException.Reason.IO;
import static io.github.ulviar.icli.integration.IntegrationProtocolException.Reason.MISSING_LENGTH;
import static io.github.ulviar.icli.integration.IntegrationProtocolException.Reason.OVERSIZED_FRAME;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Content-Length framed JSON helpers for MCP-like stdin/stdout protocols.
 */
public final class ContentLengthJsonFrames {

    private static final int DEFAULT_MAX_HEADER_BYTES = 8192;

    private ContentLengthJsonFrames() {}

    /**
     * Encodes one JSON value as a Content-Length framed UTF-8 message.
     *
     * @param value JSON value
     * @return frame bytes
     */
    public static byte[] frame(JsonValue value) {
        byte[] body = JsonCodec.write(value).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(header.length + body.length);
        frame.writeBytes(header);
        frame.writeBytes(body);
        return frame.toByteArray();
    }

    /**
     * Writes one framed JSON value to an output stream.
     *
     * @param output output stream
     * @param value JSON value
     */
    public static void write(OutputStream output, JsonValue value) {
        Objects.requireNonNull(output, "output");
        try {
            output.write(frame(value));
            output.flush();
        } catch (IOException exception) {
            throw new IntegrationProtocolException(IO, "Could not write framed JSON message", exception);
        }
    }

    /**
     * Reads and parses one framed JSON value.
     *
     * @param input input stream
     * @param maxFrameBytes maximum allowed body size
     * @return parsed JSON value
     */
    public static JsonValue read(InputStream input, int maxFrameBytes) {
        Objects.requireNonNull(input, "input");
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        try {
            int length = readContentLength(input);
            if (length > maxFrameBytes) {
                throw new IntegrationProtocolException(OVERSIZED_FRAME, "Frame exceeds maxFrameBytes");
            }
            byte[] body = input.readNBytes(length);
            if (body.length != length) {
                throw new IntegrationProtocolException(EOF, "Input ended before frame body was complete");
            }
            return JsonCodec.parse(decodeUtf8(body));
        } catch (IOException exception) {
            throw new IntegrationProtocolException(IO, "Could not read framed JSON message", exception);
        }
    }

    private static String decodeUtf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IntegrationProtocolException(INVALID_ENCODING, "Frame body must be valid UTF-8", exception);
        }
    }

    private static int readContentLength(InputStream input) throws IOException {
        String headerBlock = readHeaderBlock(input);
        Integer contentLength = null;
        for (String line : headerBlock.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IntegrationProtocolException(BAD_HEADER, "Malformed frame header");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if ("content-length".equals(name)) {
                if (contentLength != null) {
                    throw new IntegrationProtocolException(BAD_HEADER, "Duplicate Content-Length header");
                }
                contentLength = parseLength(value);
            }
        }
        if (contentLength == null) {
            throw new IntegrationProtocolException(MISSING_LENGTH, "Content-Length header is required");
        }
        return contentLength;
    }

    private static String readHeaderBlock(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        while (header.size() <= DEFAULT_MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new IntegrationProtocolException(EOF, "Input ended before frame headers were complete");
            }
            header.write(value);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && value == '\n') {
                return header.toString(StandardCharsets.US_ASCII);
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = value;
        }
        throw new IntegrationProtocolException(BAD_HEADER, "Frame headers exceed limit");
    }

    private static int parseLength(String value) {
        try {
            int length = Integer.parseInt(value);
            if (length < 0) {
                throw new IntegrationProtocolException(BAD_LENGTH, "Content-Length must not be negative");
            }
            return length;
        } catch (NumberFormatException exception) {
            throw new IntegrationProtocolException(BAD_LENGTH, "Content-Length must be a decimal integer");
        }
    }
}

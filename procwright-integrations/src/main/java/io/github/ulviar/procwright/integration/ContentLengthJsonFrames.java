/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.EOF;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.INVALID_ENCODING;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.IO;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.OVERSIZED_FRAME;

import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Content-Length framed JSON helpers for MCP-like stdin/stdout protocols.
 */
public final class ContentLengthJsonFrames {

    private static final int MAX_HEADER_BYTES = 30;
    private static final int MAX_BODY_BYTES = Integer.MAX_VALUE - MAX_HEADER_BYTES;
    private static final byte[] MINIMUM_HEADER = header(0);

    private ContentLengthJsonFrames() {}

    /**
     * Encodes one JSON value as a Content-Length framed UTF-8 message.
     *
     * @param value JSON value
     * @return frame bytes
     */
    public static byte[] frame(JsonValue value) {
        Objects.requireNonNull(value, "value");
        try {
            int bodyLength = bodyLength(value, MAX_BODY_BYTES);
            byte[] header = header(bodyLength);
            byte[] frame = new byte[Math.addExact(header.length, bodyLength)];
            System.arraycopy(header, 0, frame, 0, header.length);
            FixedByteArrayOutputStream body = new FixedByteArrayOutputStream(frame, header.length, bodyLength);
            JsonCodec.writeUtf8(value, body);
            body.requireComplete();
            return frame;
        } catch (JsonCodec.OutputLimitExceededException | ArithmeticException exception) {
            throw oversizedFrame(exception);
        } catch (IOException exception) {
            throw new IntegrationProtocolException(IO, "Could not encode framed JSON message", exception);
        }
    }

    /**
     * Writes one framed JSON value to an output stream.
     *
     * @param output output stream
     * @param value JSON value
     */
    public static void write(OutputStream output, JsonValue value) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(value, "value");
        try {
            int bodyLength = bodyLength(value, MAX_BODY_BYTES);
            output.write(header(bodyLength));
            JsonCodec.writeUtf8(value, output);
            output.flush();
        } catch (JsonCodec.OutputLimitExceededException exception) {
            throw oversizedFrame(exception);
        } catch (IOException exception) {
            throw new IntegrationProtocolException(IO, "Could not write framed JSON message", exception);
        }
    }

    static void write(ProtocolWriter writer, JsonValue value) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(value, "value");
        long remaining = writer.remainingByteCapacity();
        if (remaining < MINIMUM_HEADER.length) {
            writer.ensureByteCapacity(MINIMUM_HEADER.length);
            throw oversizedFrame(null);
        }
        long bodyLimit = Math.min(MAX_BODY_BYTES, remaining - MINIMUM_HEADER.length);
        int bodyLength;
        try {
            bodyLength = bodyLength(value, bodyLimit);
        } catch (JsonCodec.OutputLimitExceededException exception) {
            if (remaining < Long.MAX_VALUE) {
                writer.ensureByteCapacity(remaining + 1);
            }
            throw oversizedFrame(exception);
        } catch (IOException exception) {
            throw new IntegrationProtocolException(IO, "Could not encode framed JSON message", exception);
        }
        byte[] header = header(bodyLength);
        long frameLength = (long) header.length + bodyLength;
        writer.ensureByteCapacity(frameLength);
        if (frameLength > remaining) {
            throw oversizedFrame(null);
        }
        writer.write(header);
        try {
            JsonCodec.writeUtf8(value, new ProtocolWriterOutputStream(writer));
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
            int length = ContentLengthHeaders.read(input::read);
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

    private static int bodyLength(JsonValue value, long maxBytes) throws IOException {
        return Math.toIntExact(JsonCodec.utf8Length(value, maxBytes));
    }

    private static byte[] header(int bodyLength) {
        return ("Content-Length: " + bodyLength + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static IntegrationProtocolException oversizedFrame(Throwable cause) {
        return new IntegrationProtocolException(OVERSIZED_FRAME, "JSON frame exceeds the supported byte length", cause);
    }

    private static final class FixedByteArrayOutputStream extends OutputStream {

        private final byte[] target;
        private final int end;
        private int offset;

        private FixedByteArrayOutputStream(byte[] target, int offset, int length) {
            this.target = target;
            this.offset = offset;
            this.end = offset + length;
        }

        @Override
        public void write(int value) throws IOException {
            if (offset == end) {
                throw new IOException("JSON serializer exceeded its measured body length");
            }
            target[offset++] = (byte) value;
        }

        @Override
        public void write(byte[] bytes, int sourceOffset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(sourceOffset, length, bytes.length);
            if (length > end - offset) {
                throw new IOException("JSON serializer exceeded its measured body length");
            }
            System.arraycopy(bytes, sourceOffset, target, offset, length);
            offset += length;
        }

        private void requireComplete() throws IOException {
            if (offset != end) {
                throw new IOException("JSON serializer produced fewer bytes than its measured body length");
            }
        }
    }

    private static final class ProtocolWriterOutputStream extends OutputStream {

        private final ProtocolWriter writer;
        private final byte[] singleByte = new byte[1];

        private ProtocolWriterOutputStream(ProtocolWriter writer) {
            this.writer = writer;
        }

        @Override
        public void write(int value) {
            singleByte[0] = (byte) value;
            writer.write(singleByte);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writer.write(bytes, offset, length);
        }
    }
}

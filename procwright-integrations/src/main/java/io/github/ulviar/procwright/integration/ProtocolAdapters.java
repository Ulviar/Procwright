/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Protocol adapter factories built on Procwright protocol sessions. */
public final class ProtocolAdapters {

    private static final byte[] LINE_FEED = {'\n'};
    private static final int MIN_CONTENT_LENGTH_HEADER_BYTES = contentLengthHeader(0).length;

    private ProtocolAdapters() {}

    /**
     * Returns a factory for a JSON Lines protocol.
     *
     * @param maxLineBytes maximum response frame bytes including LF
     * @return fresh JSON Lines adapter factory
     */
    public static Supplier<ProtocolAdapter<JsonNode, JsonNode>> jsonLinesSession(int maxLineBytes) {
        requirePositive(maxLineBytes, "maxLineBytes");
        return () -> new ProtocolAdapter<>() {
            @Override
            public void writeRequest(JsonNode request, ProtocolWriter writer) {
                byte[] body = encodeWithinRequestBudget(request, writer, LINE_FEED.length);
                writer.ensureByteCapacity((long) body.length + LINE_FEED.length);
                writer.write(body);
                writer.write(LINE_FEED);
                writer.flush();
            }

            @Override
            public JsonNode readResponse(ProtocolReaders readers) {
                byte[] frame = readers.stdout().readUntil((byte) '\n', maxLineBytes);
                int payloadLength = frame.length - 1;
                if (payloadLength > 0 && frame[payloadLength - 1] == '\r') {
                    payloadLength--;
                }
                return JacksonJson.read(Arrays.copyOf(frame, payloadLength));
            }
        };
    }

    /**
     * Returns a factory for a delimiter-framed byte protocol. The delimiter is appended to requests and removed from
     * responses.
     *
     * @param delimiter frame delimiter
     * @param maxFrameBytes maximum response frame bytes including delimiter
     * @return fresh delimiter-framed adapter factory
     */
    public static Supplier<ProtocolAdapter<byte[], byte[]>> delimiterSession(byte delimiter, int maxFrameBytes) {
        requirePositive(maxFrameBytes, "maxFrameBytes");
        return () -> new ProtocolAdapter<>() {
            @Override
            public void writeRequest(byte[] request, ProtocolWriter writer) {
                Objects.requireNonNull(request, "request");
                writer.ensureByteCapacity((long) request.length + 1);
                byte[] payload = request.clone();
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
                return Arrays.copyOf(frame, frame.length - 1);
            }
        };
    }

    /**
     * Returns a factory for a Content-Length framed JSON protocol.
     *
     * @param maxFrameBytes maximum response body bytes
     * @return fresh Content-Length JSON adapter factory
     */
    public static Supplier<ProtocolAdapter<JsonNode, JsonNode>> contentLengthJsonSession(int maxFrameBytes) {
        requirePositive(maxFrameBytes, "maxFrameBytes");
        return () -> new ProtocolAdapter<>() {
            @Override
            public void writeRequest(JsonNode request, ProtocolWriter writer) {
                byte[] body = encodeWithinRequestBudget(request, writer, MIN_CONTENT_LENGTH_HEADER_BYTES);
                byte[] header = contentLengthHeader(body.length);
                writer.ensureByteCapacity((long) header.length + body.length);
                writer.write(header);
                writer.write(body);
                writer.flush();
            }

            @Override
            public JsonNode readResponse(ProtocolReaders readers) {
                try {
                    int length = readContentLength(readers.stdout());
                    if (length > maxFrameBytes) {
                        throw new IntegrationProtocolException(
                                IntegrationProtocolException.Reason.OVERSIZED_FRAME, "Frame exceeds maxFrameBytes");
                    }
                    return JacksonJson.read(readers.stdout().readExactly(length));
                } catch (ProtocolSessionException exception) {
                    if (exception.reason() == ProtocolSessionException.Reason.EOF) {
                        throw new IntegrationProtocolException(
                                IntegrationProtocolException.Reason.EOF,
                                "Protocol output ended before the Content-Length frame was complete",
                                exception);
                    }
                    throw exception;
                }
            }
        };
    }

    /**
     * Maps a JSON adapter factory to domain request and response types.
     *
     * <p>Each factory call creates a new wrapper around a new transport adapter. The retained encoder and decoder may
     * be called concurrently by different pool workers and therefore must be thread-safe.
     *
     * @param encode request encoder
     * @param decode response decoder
     * @param transportFactory fresh JSON transport factory
     * @param <I> request type
     * @param <O> response type
     * @return fresh typed adapter factory
     */
    public static <I, O> Supplier<ProtocolAdapter<I, O>> typedJsonSession(
            Function<? super I, ? extends JsonNode> encode,
            Function<? super JsonNode, ? extends O> decode,
            Supplier<? extends ProtocolAdapter<JsonNode, JsonNode>> transportFactory) {
        Objects.requireNonNull(encode, "encode");
        Objects.requireNonNull(decode, "decode");
        Objects.requireNonNull(transportFactory, "transportFactory");
        return () -> {
            ProtocolAdapter<JsonNode, JsonNode> transport =
                    Objects.requireNonNull(transportFactory.get(), "transportFactory returned null");
            return new ProtocolAdapter<>() {
                @Override
                public void writeRequest(I request, ProtocolWriter writer) {
                    transport.writeRequest(
                            Objects.requireNonNull(encode.apply(request), "encode returned null"), writer);
                }

                @Override
                public O readResponse(ProtocolReaders readers) {
                    return Objects.requireNonNull(
                            decode.apply(transport.readResponse(readers)), "decode returned null");
                }
            };
        };
    }

    private static byte[] contentLengthHeader(int length) {
        return ("Content-Length: " + length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] encodeWithinRequestBudget(JsonNode request, ProtocolWriter writer, int reservedBytes) {
        Objects.requireNonNull(writer, "writer");
        long remaining = writer.remainingByteCapacity();
        if (remaining < reservedBytes) {
            writer.ensureByteCapacity(reservedBytes);
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.BAD_FRAME, "JSON request exceeds the writer capacity");
        }
        byte[] body = JacksonJson.writeBytes(request, remaining - reservedBytes);
        if (body != null) {
            return body;
        }
        if (remaining < Long.MAX_VALUE) {
            writer.ensureByteCapacity(remaining + 1);
        }
        throw new IntegrationProtocolException(
                IntegrationProtocolException.Reason.BAD_FRAME, "JSON request exceeds the supported size");
    }

    private static int readContentLength(ProtocolReader input) {
        return ContentLengthHeaders.read(() -> input.readByte() & 0xFF);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

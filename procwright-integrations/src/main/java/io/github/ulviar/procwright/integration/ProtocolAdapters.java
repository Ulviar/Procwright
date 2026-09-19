/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

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
import tools.jackson.databind.JsonNode;

/**
 * Ready-to-use framing for a CLI that reads repeated requests from stdin and writes responses to stdout.
 *
 * <p>Pass a returned factory to {@link io.github.ulviar.procwright.CommandService#protocolSession(Supplier)} and open
 * either a direct session or a pool. Each factory invocation creates a fresh adapter. The built-in framing factories
 * are safe to call concurrently; {@link #typedJson(Function, Function, Supplier)} also retains caller-supplied callbacks
 * with the concurrency requirements described by that method. A factory does not launch a process.
 *
 * <p>The built-in JSON Lines and Content-Length adapters send and receive UTF-8 bytes, independently of the
 * session's text charset policy. Each response must contain exactly one JSON value; duplicate object keys, trailing
 * values, malformed UTF-8, and nesting
 * deeper than 256 levels are rejected. JSON requests are serialized once per write, with the same nesting bound. Do
 * not mutate request nodes while an exchange is in progress. Returned response nodes belong to the caller.
 *
 * <p>The factory limits bound response frames. The session's
 * {@link io.github.ulviar.procwright.ProtocolSessionScenario.Draft#withMaxRequestBytes(int) request limit} separately
 * bounds the complete outgoing frame, including its delimiter or header. Adapters check that a complete request fits
 * before writing any frame bytes; this does not make operating-system writes atomic. The session's
 * {@link io.github.ulviar.procwright.ProtocolSessionScenario.Draft#withMaxResponseBytes(int) response limit} also applies.
 *
 * <p>When used through a session, adapter failures follow {@link ProtocolAdapter}'s exception contract. In particular,
 * an {@link IntegrationProtocolException} becomes the cause of a {@link ProtocolSessionException}: writing uses reason
 * {@link ProtocolSessionException.Reason#FAILURE}, and decoding uses
 * {@link ProtocolSessionException.Reason#PROTOCOL_DECODER_FAILED}. Core timeout and request-size failures keep their
 * core reasons. See {@link io.github.ulviar.procwright.session.ProtocolSession} for
 * when a failed request closes its session.
 */
public final class ProtocolAdapters {

    private static final byte[] LINE_FEED = {'\n'};
    private static final int MIN_CONTENT_LENGTH_HEADER_BYTES = contentLengthHeader(0).length;

    private ProtocolAdapters() {}

    /**
     * Returns a factory that writes one JSON value followed by LF and reads one JSON value from a stdout line.
     *
     * <p>Responses may end in LF or CRLF. The terminator is removed before parsing; an empty or incomplete line is not
     * a JSON response. Encoding and JSON validation follow the {@linkplain ProtocolAdapters class contract}. Requests
     * are flushed after the terminating LF. This adapter does not read application messages from stderr.
     *
     * @param maxLineBytes positive maximum response frame size in bytes, including LF and an optional preceding CR
     * @return reusable, concurrent-safe factory that creates a distinct adapter on each invocation
     * @throws IllegalArgumentException if {@code maxLineBytes} is zero or negative
     */
    public static Supplier<ProtocolAdapter<JsonNode, JsonNode>> jsonLines(int maxLineBytes) {
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
     * <p>Request payloads must not contain the delimiter: this adapter has no escaping scheme. Such a request fails
     * with {@link IntegrationProtocolException.Reason#BAD_FRAME} before any frame bytes are written. Empty payloads
     * are allowed. A request is copied when the adapter writes it, then flushed with its delimiter; callers must not
     * mutate the array during an exchange. Responses are read from stdout and returned in a new caller-owned array.
     * No charset conversion is performed.
     *
     * @param delimiter byte that terminates each request and response
     * @param maxFrameBytes positive maximum response frame size in bytes, including the delimiter
     * @return reusable, concurrent-safe factory that creates a distinct adapter on each invocation
     * @throws IllegalArgumentException if {@code maxFrameBytes} is zero or negative
     */
    public static Supplier<ProtocolAdapter<byte[], byte[]>> delimited(byte delimiter, int maxFrameBytes) {
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
     * Returns a factory for UTF-8 JSON messages preceded by a Content-Length header block.
     *
     * <p>Requests are flushed as {@code Content-Length: N\r\n\r\n} followed by exactly {@code N} UTF-8 body bytes.
     * Responses are read from stdout. Headers must use ASCII text and CRLF line endings, followed by an empty line;
     * the complete header block may occupy at most 8192 bytes. Exactly one case-insensitive Content-Length field is
     * required, containing a non-negative decimal byte count. Other syntactically valid headers are ignored; they do
     * not change the JSON encoding. The announced body size is checked before the body is read.
     * Incomplete headers or bodies produce {@link IntegrationProtocolException.Reason#EOF}.
     *
     * <p>The body follows the {@linkplain ProtocolAdapters JSON validation contract}. This is message framing, not
     * an implementation of JSON-RPC, LSP, or another application protocol. It does not correlate request identifiers
     * or dispatch unsolicited notifications.
     *
     * @param maxFrameBytes positive maximum response body size in bytes, excluding headers
     * @return reusable, concurrent-safe factory that creates a distinct adapter on each invocation
     * @throws IllegalArgumentException if {@code maxFrameBytes} is zero or negative
     */
    public static Supplier<ProtocolAdapter<JsonNode, JsonNode>> contentLengthJson(int maxFrameBytes) {
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
     * <p>Each factory call invokes {@code transportFactory} and wraps its result in a new adapter. The retained
     * transport factory, encoder, and decoder may all be called concurrently for different sessions or pool workers
     * and must be thread-safe. Within one session, encoding, transport writing, transport reading, and decoding run
     * in that order without overlapping another request on that session. Captured mutable state is not copied.
     *
     * <p>The transport factory must return a fresh non-null adapter. A failure or null result from that factory occurs
     * when the returned supplier is invoked, before the session launches its process. The encoder and decoder must
     * also return non-null values. Their failures follow {@link ProtocolAdapter}'s callback contract: encoding is part
     * of writing the request, and decoding is part of reading the response. A null callback result is treated as a
     * callback failure with a {@link NullPointerException} cause.
     *
     * @param encode non-null, thread-safe function converting one request to a non-null JSON value
     * @param decode non-null, thread-safe function converting one JSON response to a non-null domain value
     * @param transportFactory non-null, concurrent-safe factory of fresh JSON transport adapters
     * @param <I> non-null request type
     * @param <O> non-null response type
     * @return factory of distinct typed adapters; creating the factory does not invoke any callback
     * @throws NullPointerException if any argument is {@code null}
     */
    public static <I, O> Supplier<ProtocolAdapter<I, O>> typedJson(
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

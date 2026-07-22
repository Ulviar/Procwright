/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Protocol adapter factories built on top of Procwright protocol sessions.
 */
public final class ProtocolAdapters {

    private ProtocolAdapters() {}

    /**
     * Returns a factory for JSON Lines protocol sessions.
     *
     * <p>The factory can be passed directly to {@code command(...).protocolSession(...)} and returns a fresh adapter
     * for every opened session or pool worker.
     *
     * @param maxLineChars maximum response line characters
     * @return fresh JSON Lines adapter factory
     */
    public static Supplier<ProtocolAdapter<JsonValue, JsonValue>> jsonLinesSession(int maxLineChars) {
        if (maxLineChars <= 0) {
            throw new IllegalArgumentException("maxLineChars must be positive");
        }
        return () -> new ProtocolAdapter<>() {
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
     * Returns a factory for delimiter-framed byte protocol sessions. The delimiter is appended to requests and removed
     * from responses.
     *
     * <p>The factory can be passed directly to {@code command(...).protocolSession(...)} and returns a fresh adapter
     * for every opened session or pool worker.
     *
     * @param delimiter frame delimiter
     * @param maxFrameBytes maximum response frame bytes including delimiter
     * @return fresh delimiter-framed adapter factory
     */
    public static Supplier<ProtocolAdapter<byte[], byte[]>> delimiterSession(byte delimiter, int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        return () -> new ProtocolAdapter<>() {
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
     * Returns a factory for Content-Length framed JSON protocol sessions.
     *
     * <p>The factory can be passed directly to {@code command(...).protocolSession(...)} and returns a fresh adapter
     * for every opened session or pool worker.
     *
     * @param maxFrameBytes maximum response body bytes
     * @return fresh Content-Length JSON adapter factory
     */
    public static Supplier<ProtocolAdapter<JsonValue, JsonValue>> contentLengthJsonSession(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        return () -> new ProtocolAdapter<>() {
            @Override
            public void writeRequest(JsonValue request, ProtocolWriter writer) {
                ContentLengthJsonFrames.write(writer, request);
                writer.flush();
            }

            @Override
            public JsonValue readResponse(ProtocolReaders readers) {
                try {
                    int length = readContentLength(readers.stdout());
                    if (length > maxFrameBytes) {
                        throw new IntegrationProtocolException(
                                IntegrationProtocolException.Reason.OVERSIZED_FRAME, "Frame exceeds maxFrameBytes");
                    }
                    byte[] body = readers.stdout().readExactly(length);
                    return JsonCodec.parse(decodeUtf8(body));
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
     * Maps a JSON protocol-session factory to domain request and response types.
     *
     * <p>Each factory call obtains a fresh transport adapter and creates a fresh typed wrapper, preserving the adapter
     * isolation required by direct sessions and pools. Mutable per-adapter state belongs inside the transport factory
     * call, not in the retained callbacks.
     *
     * <p>The returned factory retains {@code encode}, {@code decode}, and {@code transportFactory}. Different sessions
     * and pool workers can invoke those same callback instances concurrently. They must be thread-safe; this helper
     * does not synchronize them or serialize work across adapters.
     *
     * <p>A runtime exception from {@code transportFactory} propagates from the factory call unchanged, as does an
     * {@link Error}; a null transport fails with {@link NullPointerException}. Runtime exceptions from {@code encode}
     * and {@code decode} propagate from the adapter callback unchanged. Procwright maps ordinary runtime failures to
     * protocol {@link ProtocolSessionException.Reason#FAILURE} and
     * {@link ProtocolSessionException.Reason#PROTOCOL_DECODER_FAILED}, respectively; an explicitly thrown
     * {@link ProtocolSessionException} retains its existing reason. Callback errors propagate unchanged. Null encoder
     * or decoder results fail with {@link NullPointerException} and follow the ordinary runtime mapping.
     *
     * @param encode request encoder
     * @param decode response decoder
     * @param transportFactory factory that returns a fresh JSON transport adapter for each call
     * @param <I> request type
     * @param <O> response type
     * @return fresh typed protocol adapter factory
     */
    public static <I, O> Supplier<ProtocolAdapter<I, O>> typedJsonSession(
            Function<? super I, JsonValue> encode,
            Function<? super JsonValue, ? extends O> decode,
            Supplier<? extends ProtocolAdapter<JsonValue, JsonValue>> transportFactory) {
        Objects.requireNonNull(encode, "encode");
        Objects.requireNonNull(decode, "decode");
        Objects.requireNonNull(transportFactory, "transportFactory");
        return () -> {
            ProtocolAdapter<JsonValue, JsonValue> transport =
                    Objects.requireNonNull(transportFactory.get(), "transportFactory returned null");
            return new ProtocolAdapter<>() {
                @Override
                public void writeRequest(I request, ProtocolWriter writer) {
                    JsonValue encoded = Objects.requireNonNull(encode.apply(request), "encode returned null");
                    transport.writeRequest(encoded, writer);
                }

                @Override
                public O readResponse(ProtocolReaders readers) {
                    return Objects.requireNonNull(
                            decode.apply(transport.readResponse(readers)), "decode returned null");
                }
            };
        };
    }

    private static int readContentLength(ProtocolReader input) {
        return ContentLengthHeaders.readProtocol(() -> input.readByte() & 0xFF);
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

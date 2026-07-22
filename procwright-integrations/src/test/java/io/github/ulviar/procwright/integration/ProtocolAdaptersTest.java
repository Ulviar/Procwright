/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ProtocolAdaptersTest {

    @Test
    void jsonLinesAdapterWritesAndReadsJacksonNodes() {
        RecordingWriter writer = new RecordingWriter();
        ProtocolAdapter<JsonNode, JsonNode> adapter =
                ProtocolAdapters.jsonLinesSession(1024).get();
        JsonNode request = JsonNodeFactory.instance.objectNode().put("request", "line\nbreak");
        JsonNode response = JsonNodeFactory.instance.objectNode().put("response", "ok");

        adapter.writeRequest(request, writer);
        JsonNode decoded = adapter.readResponse(readers("{\"response\":\"ok\"}\n"));

        assertEquals("{\"request\":\"line\\nbreak\"}\n", writer.text());
        assertEquals(response, decoded);
    }

    @Test
    void jsonLinesAdapterRejectsMalformedAndTrailingJson() {
        var adapter = ProtocolAdapters.jsonLinesSession(1024).get();

        for (String response : new String[] {"{bad}\n", "{} []\n"}) {
            IntegrationProtocolException failure =
                    assertThrows(IntegrationProtocolException.class, () -> adapter.readResponse(readers(response)));
            assertEquals(IntegrationProtocolException.Reason.MALFORMED_JSON, failure.reason());
        }
    }

    @Test
    void jsonAdaptersRejectDuplicateKeysAndExcessiveNesting() {
        String tooDeep = "[".repeat(257) + "0" + "]".repeat(257);
        var adapter = ProtocolAdapters.jsonLinesSession(4096).get();

        for (String response : new String[] {"{\"key\":1,\"key\":2}\n", tooDeep + "\n"}) {
            IntegrationProtocolException failure =
                    assertThrows(IntegrationProtocolException.class, () -> adapter.readResponse(readers(response)));
            assertEquals(IntegrationProtocolException.Reason.MALFORMED_JSON, failure.reason());
        }
    }

    @Test
    void jsonLinesUsesStrictUtf8BytesIndependentlyOfTextDecodingPolicy() {
        byte[] malformed = {'"', (byte) 0xFF, '"', '\n'};

        IntegrationProtocolException failure = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.jsonLinesSession(1024).get().readResponse(readers(malformed)));

        assertEquals(IntegrationProtocolException.Reason.INVALID_ENCODING, failure.reason());
    }

    @Test
    void jsonLinesAdapterBoundsSerializationBeforeWriting() {
        BoundedRecordingWriter writer = new BoundedRecordingWriter(5);

        ProtocolSessionException failure = assertThrows(
                ProtocolSessionException.class,
                () -> ProtocolAdapters.jsonLinesSession(1024).get().writeRequest(TextNode.valueOf("payload"), writer));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
        assertEquals(0, writer.bytes().length);
    }

    @Test
    void delimiterAdapterWritesAndReadsDelimitedFrames() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.delimiterSession((byte) 0, 1024).get();

        adapter.writeRequest(new byte[] {1, 2}, writer);
        byte[] response = adapter.readResponse(readers(new byte[] {3, 4, 0}));

        assertArrayEquals(new byte[] {1, 2, 0}, writer.bytes());
        assertArrayEquals(new byte[] {3, 4}, response);
    }

    @Test
    void delimiterAdapterRejectsAmbiguousRequestBeforeWriting() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.delimiterSession((byte) 0, 1024).get();

        IntegrationProtocolException failure =
                assertThrows(IntegrationProtocolException.class, () -> adapter.writeRequest(new byte[] {1, 0}, writer));

        assertEquals(IntegrationProtocolException.Reason.BAD_FRAME, failure.reason());
        assertEquals(0, writer.bytes().length);
    }

    @Test
    void delimiterAdapterPreflightsCapacityBeforeCopyingOrWriting() {
        BoundedRecordingWriter writer = new BoundedRecordingWriter(1);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> ProtocolAdapters.delimiterSession((byte) 0, 1024)
                        .get()
                        .writeRequest(new byte[1024 * 1024], writer));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
        assertEquals(0, writer.bytes().length);
    }

    @Test
    void contentLengthAdapterWritesAndReadsJsonFrames() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.contentLengthJsonSession(1024).get();
        JsonNode request = JsonNodeFactory.instance.objectNode().put("text", "é-🚀");
        JsonNode response = JsonNodeFactory.instance.objectNode().put("ok", true);

        adapter.writeRequest(request, writer);
        JsonNode decoded = adapter.readResponse(readers(frame(response.toString())));

        byte[] expectedBody = JacksonJson.writeBytes(request);
        assertEquals(
                "Content-Length: " + expectedBody.length + "\r\n\r\n"
                        + new String(expectedBody, StandardCharsets.UTF_8),
                writer.text());
        assertEquals(response, decoded);
    }

    @Test
    void contentLengthAdapterBoundsSerializationBeforeWriting() {
        BoundedRecordingWriter writer = new BoundedRecordingWriter(24);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> ProtocolAdapters.contentLengthJsonSession(1024)
                        .get()
                        .writeRequest(TextNode.valueOf("payload"), writer));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
        assertEquals(0, writer.bytes().length);
    }

    @Test
    void contentLengthAdapterPreflightsActualHeaderAndBodyBeforeWriting() {
        BoundedRecordingWriter writer = new BoundedRecordingWriter(31);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> ProtocolAdapters.contentLengthJsonSession(1024)
                        .get()
                        .writeRequest(TextNode.valueOf("12345678"), writer));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
        assertEquals(0, writer.bytes().length);
    }

    @Test
    void contentLengthSerializesMutableNodeOnceBeforeWritingHeaderAndBody() {
        RecordingWriter writer = new RecordingWriter();
        ChangingTextNode request = new ChangingTextNode();

        ProtocolAdapters.contentLengthJsonSession(1024).get().writeRequest(request, writer);

        assertEquals(1, request.serializations());
        assertEquals("Content-Length: 9\r\n\r\n\"value-1\"", writer.text());
    }

    @Test
    void jacksonWriteConstraintFailsBeforeWritingAnyFrameBytes() {
        RecordingWriter writer = new RecordingWriter();
        JsonNode nested = JsonNodeFactory.instance.arrayNode();
        for (int depth = 0; depth < 257; depth++) {
            nested = JsonNodeFactory.instance.arrayNode().add(nested);
        }
        JsonNode request = nested;

        IntegrationProtocolException failure = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().writeRequest(request, writer));

        assertEquals(IntegrationProtocolException.Reason.BAD_FRAME, failure.reason());
        assertEquals(0, writer.bytes().length);
    }

    @Test
    void contentLengthAdapterRejectsOversizedBodyBeforeReadingIt() {
        TrackingByteReader stdout = new TrackingByteReader(frame("{}"));

        IntegrationProtocolException failure = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1).get().readResponse(readers(stdout)));

        assertEquals(IntegrationProtocolException.Reason.OVERSIZED_FRAME, failure.reason());
        assertEquals(0, stdout.readExactlyCalls());
    }

    @Test
    void contentLengthAdapterAcceptsHeaderAtLimitAndRejectsTheNextByte() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        TrackingByteReader atLimit = new TrackingByteReader(concatenate(headerWithTotalBytes(8192, body.length), body));
        TrackingByteReader overLimit =
                new TrackingByteReader(concatenate(headerWithTotalBytes(8193, body.length), body));

        assertEquals(
                JsonNodeFactory.instance.objectNode(),
                ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(atLimit)));
        IntegrationProtocolException failure = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(overLimit)));

        assertEquals(IntegrationProtocolException.Reason.BAD_HEADER, failure.reason());
        assertEquals(8192, overLimit.bytesRead());
        assertEquals(0, overLimit.readExactlyCalls());
    }

    @Test
    void contentLengthAdapterDistinguishesMalformedJsonFromInvalidUtf8() {
        byte[] invalidUtf8 =
                concatenate("Content-Length: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII), new byte[] {(byte) 0xFF});

        IntegrationProtocolException malformed = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(frame("{bad}"))));
        IntegrationProtocolException encoding = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(invalidUtf8)));

        assertEquals(IntegrationProtocolException.Reason.MALFORMED_JSON, malformed.reason());
        assertEquals(IntegrationProtocolException.Reason.INVALID_ENCODING, encoding.reason());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("headerCases")
    void contentLengthAdapterAppliesStrictHeaderGrammar(ContentLengthHeaderTestCases.HeaderCase testCase) {
        byte[] input = testCase.expectedReason() == IntegrationProtocolException.Reason.EOF
                ? testCase.header()
                : concatenate(testCase.header(), "{}".getBytes(StandardCharsets.UTF_8));
        TrackingByteReader stdout = new TrackingByteReader(input);

        if (testCase.expectedReason() == null) {
            assertEquals(
                    JsonNodeFactory.instance.objectNode(),
                    ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(stdout)));
            return;
        }

        IntegrationProtocolException failure = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(stdout)));
        assertEquals(testCase.expectedReason(), failure.reason());
        assertEquals(0, stdout.readExactlyCalls());
        assertTrue(stdout.bytesRead() <= testCase.header().length);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonEofProtocolFailures")
    void contentLengthAdapterDoesNotNormalizeCoreProtocolFailures(ProtocolFailureCase testCase) {
        ProtocolSessionException propagated =
                assertThrows(ProtocolSessionException.class, () -> ProtocolAdapters.contentLengthJsonSession(1024)
                        .get()
                        .readResponse(readers(new FaultingByteReader(
                                testCase.bytes(), testCase.failureOffset(), testCase.failure()))));

        assertSame(testCase.failure(), propagated);
    }

    @Test
    void contentLengthEofHasStableIntegrationReasonAndOriginalCause() {
        byte[] header = "Content-Length: 2\r\n".getBytes(StandardCharsets.US_ASCII);
        ProtocolSessionException eof = protocolFailure(ProtocolSessionException.Reason.EOF);

        IntegrationProtocolException failure =
                assertThrows(IntegrationProtocolException.class, () -> ProtocolAdapters.contentLengthJsonSession(1024)
                        .get()
                        .readResponse(readers(new FaultingByteReader(header, header.length, eof))));

        assertEquals(IntegrationProtocolException.Reason.EOF, failure.reason());
        assertSame(eof, failure.getCause());
    }

    @Test
    void contentLengthBodyEofHasStableIntegrationReasonAndOriginalCause() {
        byte[] frame = frame("{}");
        int bodyOffset = frame.length - 2;
        ProtocolSessionException eof = protocolFailure(ProtocolSessionException.Reason.EOF);

        IntegrationProtocolException failure =
                assertThrows(IntegrationProtocolException.class, () -> ProtocolAdapters.contentLengthJsonSession(1024)
                        .get()
                        .readResponse(readers(new FaultingByteReader(frame, bodyOffset + 1, eof))));

        assertEquals(IntegrationProtocolException.Reason.EOF, failure.reason());
        assertSame(eof, failure.getCause());
    }

    @Test
    void factoriesCreateFreshAdaptersAndComposeWithDirectAndPooledScenarios() {
        assertFresh(ProtocolAdapters.jsonLinesSession(1024));
        assertFresh(ProtocolAdapters.delimiterSession((byte) 0, 1024));
        assertFresh(ProtocolAdapters.contentLengthJsonSession(1024));

        var command = Procwright.command(CommandSpec.of("not-started"));
        var factory = ProtocolAdapters.jsonLinesSession(1024);
        assertNotNull(command.protocolSession(factory));
        assertNotNull(command.protocolSession(factory).pooled());
    }

    @Test
    void typedFactoryCreatesFreshWrappersAndTransports() {
        AtomicInteger transports = new AtomicInteger();
        Supplier<ProtocolAdapter<JsonNode, JsonNode>> transportFactory = () -> {
            transports.incrementAndGet();
            return ProtocolAdapters.jsonLinesSession(1024).get();
        };
        Supplier<ProtocolAdapter<String, String>> factory =
                ProtocolAdapters.typedJsonSession(TextNode::valueOf, JsonNode::textValue, transportFactory);

        assertNotSame(factory.get(), factory.get());
        assertEquals(2, transports.get());
    }

    @Test
    void adapterFactoriesRejectInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolAdapters.jsonLinesSession(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolAdapters.delimiterSession((byte) 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolAdapters.contentLengthJsonSession(0));
    }

    private static void assertFresh(Supplier<? extends ProtocolAdapter<?, ?>> factory) {
        assertNotSame(factory.get(), factory.get());
    }

    private static Stream<ContentLengthHeaderTestCases.HeaderCase> headerCases() {
        return ContentLengthHeaderTestCases.cases();
    }

    private static Stream<ProtocolFailureCase> nonEofProtocolFailures() {
        byte[] frame = frame("{}");
        int bodyOffset = frame.length - 2;
        return Arrays.stream(ProtocolSessionException.Reason.values())
                .filter(reason -> reason != ProtocolSessionException.Reason.EOF)
                .flatMap(reason -> Stream.of(
                        new ProtocolFailureCase("header " + reason, frame, 0, protocolFailure(reason)),
                        new ProtocolFailureCase("body " + reason, frame, bodyOffset, protocolFailure(reason))));
    }

    private static ProtocolSessionException protocolFailure(ProtocolSessionException.Reason reason) {
        return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), "controlled " + reason);
    }

    private static byte[] frame(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return concatenate(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII), body);
    }

    private static byte[] headerWithTotalBytes(int totalBytes, int contentLength) {
        String prefix = "Content-Length: " + contentLength + "\r\nX-Filler: ";
        String suffix = "\r\n\r\n";
        return (prefix + "a".repeat(totalBytes - prefix.length() - suffix.length()) + suffix)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static ProtocolReaders readers(String stdout) {
        return readers(stdout.getBytes(StandardCharsets.UTF_8));
    }

    private static ProtocolReaders readers(byte[] stdout) {
        return readers(new ByteReader(stdout));
    }

    private static ProtocolReaders readers(ProtocolReader stdout) {
        return new ProtocolReaders() {
            @Override
            public ProtocolReader stdout() {
                return stdout;
            }

            @Override
            public ProtocolReader stderr() {
                return new ByteReader(new byte[0]);
            }
        };
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static class RecordingWriter implements ProtocolWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override
        public void write(byte[] bytes) {
            output.writeBytes(bytes);
        }

        @Override
        public void write(String text) {
            write(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void writeLine(String line) {
            write(line + "\n");
        }

        @Override
        public void flush() {}

        final byte[] bytes() {
            return output.toByteArray();
        }

        final String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class BoundedRecordingWriter extends RecordingWriter {
        private final long capacity;

        private BoundedRecordingWriter(long capacity) {
            this.capacity = capacity;
        }

        @Override
        public long remainingByteCapacity() {
            return capacity - bytes().length;
        }

        @Override
        public void ensureByteCapacity(long bytes) {
            if (bytes > remainingByteCapacity()) {
                throw protocolFailure(ProtocolSessionException.Reason.REQUEST_TOO_LARGE);
            }
        }
    }

    private static class ByteReader implements ProtocolReader {
        private final byte[] bytes;
        protected int offset;

        private ByteReader(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public byte readByte() {
            if (offset == bytes.length) {
                throw protocolFailure(ProtocolSessionException.Reason.EOF);
            }
            return bytes[offset++];
        }

        @Override
        public int read(byte[] buffer, int targetOffset, int length) {
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, buffer, targetOffset, count);
            offset += count;
            return count;
        }

        @Override
        public byte[] readExactly(int length) {
            byte[] result = new byte[length];
            for (int index = 0; index < length; index++) {
                result[index] = readByte();
            }
            return result;
        }

        @Override
        public String readTextExactly(int byteLength, int maxChars) {
            return new String(readExactly(byteLength), StandardCharsets.UTF_8);
        }

        @Override
        public byte[] readUntil(byte delimiter, int maxBytes) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (result.size() < maxBytes) {
                byte value = readByte();
                result.write(value);
                if (value == delimiter) {
                    return result.toByteArray();
                }
            }
            throw new AssertionError("delimiter not found within limit");
        }

        @Override
        public String readLine(int maxChars) {
            String line = readTextUntil((byte) '\n', maxChars + 1);
            return line.substring(0, line.length() - 1);
        }

        @Override
        public String readTextUntil(byte delimiter, int maxChars) {
            return new String(readUntil(delimiter, maxChars), StandardCharsets.UTF_8);
        }
    }

    private static final class TrackingByteReader extends ByteReader {
        private int readExactlyCalls;

        private TrackingByteReader(byte[] bytes) {
            super(bytes);
        }

        @Override
        public byte[] readExactly(int length) {
            readExactlyCalls++;
            return super.readExactly(length);
        }

        private int bytesRead() {
            return offset;
        }

        private int readExactlyCalls() {
            return readExactlyCalls;
        }
    }

    private static final class FaultingByteReader extends ByteReader {
        private final int failureOffset;
        private final ProtocolSessionException failure;

        private FaultingByteReader(byte[] bytes, int failureOffset, ProtocolSessionException failure) {
            super(bytes);
            this.failureOffset = failureOffset;
            this.failure = failure;
        }

        @Override
        public byte readByte() {
            if (offset == failureOffset) {
                throw failure;
            }
            return super.readByte();
        }
    }

    private static final class ChangingTextNode extends ValueNode {

        private static final long serialVersionUID = 1L;
        private int serializations;

        @Override
        public JsonToken asToken() {
            return JsonToken.VALUE_STRING;
        }

        @Override
        public JsonNodeType getNodeType() {
            return JsonNodeType.STRING;
        }

        @Override
        public String asText() {
            return "value-" + serializations;
        }

        @Override
        public void serialize(JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeString("value-" + ++serializations);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public String toString() {
            return "\"value-" + serializations + "\"";
        }

        private int serializations() {
            return serializations;
        }
    }

    private record ProtocolFailureCase(String name, byte[] bytes, int failureOffset, ProtocolSessionException failure) {
        private ProtocolFailureCase {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}

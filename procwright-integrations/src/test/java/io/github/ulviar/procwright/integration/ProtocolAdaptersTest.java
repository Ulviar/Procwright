/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ProtocolAdaptersTest {

    @Test
    void jsonLinesAdapterWritesAndReadsJsonValues() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.jsonLinesSession(1024).get();

        adapter.writeRequest(JsonValue.object(Map.of("request", JsonValue.string("ok"))), writer);
        JsonValue response = adapter.readResponse(readers("{\"response\":\"ok\"}\n"));

        assertEquals("{\"request\":\"ok\"}\n", writer.text());
        assertEquals(JsonValue.object(Map.of("response", JsonValue.string("ok"))), response);
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
    void delimiterAdapterRejectsAmbiguousRequestFrames() {
        var adapter = ProtocolAdapters.delimiterSession((byte) 0, 1024).get();

        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> adapter.writeRequest(new byte[] {1, 0}, new RecordingWriter()));

        assertEquals(IntegrationProtocolException.Reason.BAD_FRAME, exception.reason());
    }

    @Test
    void contentLengthAdapterWritesAndReadsJsonFrames() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.contentLengthJsonSession(1024).get();
        JsonValue request = JsonValue.object(Map.of("id", JsonValue.number(1)));
        JsonValue response = JsonValue.object(Map.of("ok", JsonValue.bool(true)));

        adapter.writeRequest(request, writer);
        JsonValue decoded = adapter.readResponse(readers(ContentLengthJsonFrames.frame(response)));

        assertEquals(request, ContentLengthJsonFrames.read(new java.io.ByteArrayInputStream(writer.bytes()), 1024));
        assertEquals(response, decoded);
    }

    @Test
    void contentLengthAdapterStreamsTheCanonicalNumberSnapshotExactly() {
        AdversarialBigDecimal input = new AdversarialBigDecimal("1.500");
        RecordingWriter writer = new RecordingWriter();

        ProtocolAdapters.contentLengthJsonSession(1024).get().writeRequest(JsonValue.number(input), writer);

        assertEquals("Content-Length: 5\r\n\r\n1.500", writer.text());
        assertEquals(0, input.stringCalls());
    }

    @Test
    void contentLengthAdapterRejectsCanonicalNumberBeforeAnyOversizedWrite() {
        AdversarialBigDecimal input = new AdversarialBigDecimal("1.500");
        BoundedRecordingWriter writer = new BoundedRecordingWriter(25);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> ProtocolAdapters.contentLengthJsonSession(1024)
                        .get()
                        .writeRequest(JsonValue.number(input), writer));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
        assertEquals(0, writer.bytes().length);
        assertEquals(0, input.stringCalls());
    }

    @Test
    void contentLengthAdapterStreamsBmpAndSupplementaryCharactersWithoutReplacement() {
        RecordingWriter writer = new RecordingWriter();
        JsonValue value = JsonValue.string("é-🚀");

        ProtocolAdapters.contentLengthJsonSession(1024).get().writeRequest(value, writer);

        assertEquals("Content-Length: 9\r\n\r\n\"é-🚀\"", writer.text());
    }

    @Test
    void contentLengthAdapterRejectsHugeRequestDuringBoundedPreflightBeforeWriting() {
        BoundedRecordingWriter writer = new BoundedRecordingWriter(128);
        JsonValue request = JsonValue.string("x".repeat(4 * 1024 * 1024));

        ProtocolSessionException failure = assertThrows(
                ProtocolSessionException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().writeRequest(request, writer));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
        assertEquals(0, writer.bytes().length);
        assertTrue(writer.capacityChecks() <= 2, "preflight should reject without one check per JSON byte");
    }

    @Test
    void contentLengthAdapterAcceptsHeaderWhoseTerminatorEndsAtByteLimit() {
        byte[] header = headerWithTotalBytes(8192, 2);
        TrackingByteReader stdout = new TrackingByteReader(concatenate(header, "{}".getBytes(StandardCharsets.UTF_8)));

        JsonValue decoded =
                ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(stdout));

        assertEquals(JsonValue.object(Map.of()), decoded);
        assertEquals(1, stdout.readExactlyCalls());
    }

    @Test
    void contentLengthAdapterRejectsHeaderBeyondByteLimitWithoutReadingExtraByteOrBody() {
        byte[] header = headerWithTotalBytes(8193, 2);
        TrackingByteReader stdout = new TrackingByteReader(concatenate(header, "{}".getBytes(StandardCharsets.UTF_8)));

        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(stdout)));

        assertEquals(IntegrationProtocolException.Reason.BAD_HEADER, exception.reason());
        assertEquals(8192, stdout.bytesRead());
        assertEquals(0, stdout.readExactlyCalls());
    }

    @Test
    void contentLengthAdapterRejectsOversizedDeclaredBodyBeforeReadExactly() {
        byte[] header = "Content-Length: 3\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        TrackingByteReader stdout = new TrackingByteReader(concatenate(header, "{}".getBytes(StandardCharsets.UTF_8)));

        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(2).get().readResponse(readers(stdout)));

        assertEquals(IntegrationProtocolException.Reason.OVERSIZED_FRAME, exception.reason());
        assertEquals(0, stdout.readExactlyCalls());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("headerCases")
    void contentLengthAdapterAppliesStrictSharedHeaderGrammar(ContentLengthHeaderTestCases.HeaderCase testCase) {
        byte[] header = testCase.header();
        byte[] inputBytes = testCase.expectedReason() == IntegrationProtocolException.Reason.EOF
                ? header
                : concatenate(header, "{}".getBytes(StandardCharsets.UTF_8));
        TrackingByteReader stdout = new TrackingByteReader(inputBytes);

        if (testCase.expectedReason() == null) {
            assertEquals(
                    JsonValue.object(Map.of()),
                    ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(stdout)));
            return;
        }
        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ProtocolAdapters.contentLengthJsonSession(1024).get().readResponse(readers(stdout)));
        assertEquals(testCase.expectedReason(), exception.reason());
        if (testCase.expectedReason() == IntegrationProtocolException.Reason.EOF) {
            ProtocolSessionException cause = (ProtocolSessionException) exception.getCause();
            assertEquals(ProtocolSessionException.Reason.EOF, cause.reason());
        }
        assertEquals(0, stdout.readExactlyCalls());
        assertTrue(stdout.bytesRead() <= header.length);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentLengthEofCases")
    void contentLengthEofHasTheSameIntegrationTaxonomyAcrossTransportPaths(ContentLengthReadCase testCase) {
        IntegrationProtocolException failure = assertThrows(IntegrationProtocolException.class, testCase.read()::run);

        assertEquals(IntegrationProtocolException.class, failure.getClass());
        assertEquals(IntegrationProtocolException.Reason.EOF, failure.reason());
        assertSame(testCase.expectedCause(), failure.getCause());
    }

    private static Stream<ContentLengthReadCase> contentLengthEofCases() {
        byte[] incompleteHeader = "Content-Length: 2\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] truncatedBody = "Content-Length: 3\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);
        ProtocolSessionException headerEof = protocolFailure(ProtocolSessionException.Reason.EOF);
        ProtocolSessionException bodyEof = protocolFailure(ProtocolSessionException.Reason.EOF);
        return Stream.of(
                new ContentLengthReadCase(
                        "InputStream header EOF",
                        () -> ContentLengthJsonFrames.read(new ByteArrayInputStream(incompleteHeader), 1024),
                        null),
                new ContentLengthReadCase(
                        "InputStream truncated body",
                        () -> ContentLengthJsonFrames.read(new ByteArrayInputStream(truncatedBody), 1024),
                        null),
                new ContentLengthReadCase(
                        "ProtocolReader header EOF",
                        () -> ProtocolAdapters.contentLengthJsonSession(1024)
                                .get()
                                .readResponse(readers(
                                        new FaultingByteReader(incompleteHeader, incompleteHeader.length, headerEof))),
                        headerEof),
                new ContentLengthReadCase(
                        "ProtocolReader truncated body",
                        () -> ProtocolAdapters.contentLengthJsonSession(1024)
                                .get()
                                .readResponse(
                                        readers(new FaultingByteReader(truncatedBody, truncatedBody.length, bodyEof))),
                        bodyEof));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonEofProtocolFailures")
    void contentLengthAdapterDoesNotNormalizeNonEofProtocolFailures(ProtocolFailureCase testCase) {
        ProtocolSessionException propagated =
                assertThrows(ProtocolSessionException.class, () -> ProtocolAdapters.contentLengthJsonSession(1024)
                        .get()
                        .readResponse(readers(new FaultingByteReader(
                                testCase.bytes(), testCase.failureOffset(), testCase.failure()))));

        assertSame(testCase.failure(), propagated);
        assertEquals(testCase.failure().reason(), propagated.reason());
    }

    private static Stream<ProtocolFailureCase> nonEofProtocolFailures() {
        byte[] frame = "Content-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);
        int bodyOffset = frame.length - 2;
        return Arrays.stream(ProtocolSessionException.Reason.values())
                .filter(reason -> reason != ProtocolSessionException.Reason.EOF)
                .flatMap(reason -> Stream.of(
                        new ProtocolFailureCase("ProtocolReader header " + reason, frame, 0, protocolFailure(reason)),
                        new ProtocolFailureCase(
                                "ProtocolReader body " + reason, frame, bodyOffset, protocolFailure(reason))));
    }

    @Test
    void builtInSessionFactoriesCreateDistinctAdaptersForSessionsAndWorkers() {
        assertFresh(ProtocolAdapters.jsonLinesSession(1024));
        assertFresh(ProtocolAdapters.delimiterSession((byte) 0, 1024));
        assertFresh(ProtocolAdapters.contentLengthJsonSession(1024));
    }

    @Test
    void typedJsonSessionFactoryCreatesFreshWrapperAndTransportForEachWorker() {
        AtomicInteger transportsCreated = new AtomicInteger();
        Supplier<ProtocolAdapter<JsonValue, JsonValue>> transportFactory = () -> {
            transportsCreated.incrementAndGet();
            return ProtocolAdapters.jsonLinesSession(1024).get();
        };
        Supplier<ProtocolAdapter<String, String>> factory =
                ProtocolAdapters.typedJsonSession(JsonValue::string, JsonCodec::write, transportFactory);

        ProtocolAdapter<String, String> first = factory.get();
        ProtocolAdapter<String, String> second = factory.get();

        assertNotSame(first, second);
        assertEquals(2, transportsCreated.get());
    }

    @Test
    void sessionFactoryComposesDirectlyWithProtocolSessionAndPoolDrafts() {
        var command = Procwright.command(CommandSpec.of("not-started"));
        var factory = ProtocolAdapters.jsonLinesSession(1024);

        assertNotNull(command.protocolSession(factory));
        assertNotNull(command.protocolSession(factory).pooled());
    }

    private static void assertFresh(Supplier<? extends ProtocolAdapter<?, ?>> factory) {
        assertNotSame(factory.get(), factory.get());
    }

    private static Stream<ContentLengthHeaderTestCases.HeaderCase> headerCases() {
        return ContentLengthHeaderTestCases.cases();
    }

    private static ProtocolSessionException protocolFailure(ProtocolSessionException.Reason reason) {
        return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), "controlled " + reason);
    }

    private static ProtocolReaders readers(String stdout) {
        return readers(stdout.getBytes(StandardCharsets.UTF_8));
    }

    private static ProtocolReaders readers(byte[] stdout) {
        return readers(new ByteReader(stdout));
    }

    private static ProtocolReaders readers(ProtocolReader stdoutReader) {
        ByteReader stderrReader = new ByteReader(new byte[0]);
        return new ProtocolReaders() {
            @Override
            public ProtocolReader stdout() {
                return stdoutReader;
            }

            @Override
            public ProtocolReader stderr() {
                return stderrReader;
            }
        };
    }

    private static byte[] headerWithTotalBytes(int totalBytes, int contentLength) {
        String prefix = "Content-Length: " + contentLength + "\r\nX-Filler: ";
        String suffix = "\r\n\r\n";
        return (prefix + "a".repeat(totalBytes - prefix.length() - suffix.length()) + suffix)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
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

        protected final byte[] bytes() {
            return output.toByteArray();
        }

        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class BoundedRecordingWriter extends RecordingWriter {
        private final long capacity;
        private int capacityChecks;

        private BoundedRecordingWriter(long capacity) {
            this.capacity = capacity;
        }

        @Override
        public long remainingByteCapacity() {
            return capacity - bytes().length;
        }

        @Override
        public void ensureByteCapacity(long byteCount) {
            capacityChecks++;
            if (byteCount > remainingByteCapacity()) {
                throw protocolFailure(ProtocolSessionException.Reason.REQUEST_TOO_LARGE);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            ensureByteCapacity(length);
            super.write(Arrays.copyOfRange(bytes, offset, offset + length));
        }

        private int capacityChecks() {
            return capacityChecks;
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
                throw new ProtocolSessionException(
                        ProtocolSessionException.Reason.EOF,
                        new ProtocolTranscript("", false, false),
                        "controlled EOF");
            }
            return bytes[offset++];
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int count = Math.min(length, bytes.length - this.offset);
            System.arraycopy(bytes, this.offset, buffer, offset, count);
            this.offset += count;
            return count;
        }

        @Override
        public byte[] readExactly(int length) {
            byte[] result = new byte[length];
            read(result, 0, length);
            return result;
        }

        @Override
        public String readTextExactly(int byteLength, int maxChars) {
            String result = new String(readExactly(byteLength), StandardCharsets.UTF_8);
            if (result.length() > maxChars) {
                throw new AssertionError("text field exceeds maxChars");
            }
            return result;
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
            throw new AssertionError("delimiter not found");
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
            return super.offset;
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

        @Override
        public byte[] readExactly(int length) {
            byte[] result = new byte[length];
            for (int index = 0; index < result.length; index++) {
                result[index] = readByte();
            }
            return result;
        }
    }

    @FunctionalInterface
    private interface ContentLengthRead {
        void run();
    }

    private record ContentLengthReadCase(String name, ContentLengthRead read, Throwable expectedCause) {
        @Override
        public String toString() {
            return name;
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

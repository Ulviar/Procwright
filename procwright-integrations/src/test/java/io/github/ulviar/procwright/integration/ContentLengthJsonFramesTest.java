/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.BAD_HEADER;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.EOF;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.INVALID_ENCODING;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.IO;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.OVERSIZED_FRAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ContentLengthJsonFramesTest {

    @Test
    void frameUsesExactUtf8LengthAndPreservesOrderNumbersAndEscaping() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("text", JsonValue.string("é\n\""));
        members.put("number", JsonValue.number("1.500"));
        JsonValue value = JsonValue.object(members);

        byte[] frame = ContentLengthJsonFrames.frame(value);

        assertEquals(
                "Content-Length: 32\r\n\r\n{\"text\":\"é\\n\\\"\",\"number\":1.500}",
                new String(frame, StandardCharsets.UTF_8));
    }

    @Test
    void frameUsesOneCanonicalSnapshotForAdversarialBigDecimalSubclasses() {
        AdversarialBigDecimal input = new AdversarialBigDecimal("1.500");

        byte[] frame = ContentLengthJsonFrames.frame(JsonValue.number(input));

        assertEquals("Content-Length: 5\r\n\r\n1.500", new String(frame, StandardCharsets.UTF_8));
        assertEquals(0, input.stringCalls());
    }

    @Test
    void contentLengthPreservesValidUnicodeAndRejectsUnpairedSurrogates() {
        JsonValue value = JsonValue.string("é-🚀");
        byte[] frame = ContentLengthJsonFrames.frame(value);

        assertEquals("Content-Length: 9\r\n\r\n\"é-🚀\"", new String(frame, StandardCharsets.UTF_8));
        assertEquals(value, ContentLengthJsonFrames.read(new ByteArrayInputStream(frame), 64));

        byte[] malformedBody = "\"\\uD800\"".getBytes(StandardCharsets.US_ASCII);
        byte[] malformedFrame =
                concatenate("Content-Length: 8\r\n\r\n".getBytes(StandardCharsets.US_ASCII), malformedBody);
        assertThrows(
                JsonParseException.class,
                () -> ContentLengthJsonFrames.read(new ByteArrayInputStream(malformedFrame), 64));
    }

    @Test
    void framesAndReadsJsonMessages() {
        JsonValue value = JsonValue.object(Map.of("ok", JsonValue.bool(true)));
        byte[] frame = ContentLengthJsonFrames.frame(value);

        JsonValue parsed = ContentLengthJsonFrames.read(new ByteArrayInputStream(frame), 1024);

        assertEquals(value, parsed);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("headerCases")
    void appliesStrictHeaderGrammarWithoutReadingBodyAfterHeaderFailure(
            ContentLengthHeaderTestCases.HeaderCase testCase) {
        byte[] header = testCase.header();
        byte[] inputBytes =
                testCase.expectedReason() == EOF ? header : concatenate(header, "{}".getBytes(StandardCharsets.UTF_8));
        TrackingInputStream input = new TrackingInputStream(inputBytes, header.length);

        if (testCase.expectedReason() == null) {
            assertEquals(JsonValue.object(Map.of()), ContentLengthJsonFrames.read(input, 1024));
            return;
        }

        IntegrationProtocolException exception =
                assertThrows(IntegrationProtocolException.class, () -> ContentLengthJsonFrames.read(input, 1024));

        assertEquals(testCase.expectedReason(), exception.reason());
        assertFalse(input.bodyRead());
    }

    private static Stream<ContentLengthHeaderTestCases.HeaderCase> headerCases() {
        return ContentLengthHeaderTestCases.cases();
    }

    @Test
    void acceptsHeaderWhoseTerminatorEndsAtByteLimit() {
        byte[] header = headerWithTotalBytes(8192, 2);
        byte[] frame = concatenate(header, "{}".getBytes(StandardCharsets.UTF_8));

        JsonValue parsed = ContentLengthJsonFrames.read(new ByteArrayInputStream(frame), 1024);

        assertEquals(JsonValue.object(Map.of()), parsed);
    }

    @Test
    void rejectsHeaderWhoseTerminatorWouldEndBeyondByteLimitWithoutReadingExtraByte() {
        byte[] header = headerWithTotalBytes(8193, 2);
        TrackingInputStream input =
                new TrackingInputStream(concatenate(header, "{}".getBytes(StandardCharsets.UTF_8)), header.length);

        IntegrationProtocolException exception =
                assertThrows(IntegrationProtocolException.class, () -> ContentLengthJsonFrames.read(input, 1024));

        assertEquals(BAD_HEADER, exception.reason());
        assertEquals(8192, input.bytesRead());
        assertFalse(input.bodyRead());
    }

    @Test
    void rejectsOversizedBodyBeforeReadingIt() {
        byte[] header = "Content-Length: 3\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        TrackingInputStream input =
                new TrackingInputStream(concatenate(header, "{}".getBytes(StandardCharsets.UTF_8)), header.length);

        IntegrationProtocolException exception =
                assertThrows(IntegrationProtocolException.class, () -> ContentLengthJsonFrames.read(input, 2));

        assertEquals(OVERSIZED_FRAME, exception.reason());
        assertFalse(input.bodyRead());
    }

    @Test
    void normalizesHeaderInputStreamFailure() {
        byte[] frame = "Content-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);
        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ContentLengthJsonFrames.read(new ThrowingInputStream(frame, 5), 1024));

        assertEquals(IO, exception.reason());
    }

    @Test
    void normalizesBodyInputStreamFailure() {
        byte[] header = "Content-Length: 2\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] frame = concatenate(header, "{}".getBytes(StandardCharsets.US_ASCII));
        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ContentLengthJsonFrames.read(new ThrowingInputStream(frame, header.length), 1024));

        assertEquals(IO, exception.reason());
    }

    @Test
    void distinguishesEofBeforeBodyCompletion() {
        byte[] frame = "Content-Length: 3\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);
        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ContentLengthJsonFrames.read(new ByteArrayInputStream(frame), 1024));

        assertEquals(EOF, exception.reason());
    }

    @Test
    void rejectsMalformedUtf8Body() {
        byte[] frame = "Content-Length: 3\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[] {'"', (byte) 0xFF, '"'};
        byte[] combined = new byte[frame.length + body.length];
        System.arraycopy(frame, 0, combined, 0, frame.length);
        System.arraycopy(body, 0, combined, frame.length, body.length);

        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ContentLengthJsonFrames.read(new ByteArrayInputStream(combined), 1024));

        assertEquals(INVALID_ENCODING, exception.reason());
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

    private static final class TrackingInputStream extends InputStream {
        private final byte[] bytes;
        private final int bodyOffset;
        private int offset;
        private boolean bodyRead;

        private TrackingInputStream(byte[] bytes, int bodyOffset) {
            this.bytes = bytes.clone();
            this.bodyOffset = bodyOffset;
        }

        @Override
        public int read() {
            if (offset >= bytes.length) {
                return -1;
            }
            bodyRead |= offset >= bodyOffset;
            return bytes[offset++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int targetOffset, int length) throws IOException {
            if (offset >= bytes.length) {
                return -1;
            }
            bodyRead |= offset >= bodyOffset;
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, buffer, targetOffset, count);
            offset += count;
            return count;
        }

        private int bytesRead() {
            return offset;
        }

        private boolean bodyRead() {
            return bodyRead;
        }
    }

    private static final class ThrowingInputStream extends InputStream {
        private final byte[] bytes;
        private final int failureOffset;
        private int offset;

        private ThrowingInputStream(byte[] bytes, int failureOffset) {
            this.bytes = bytes.clone();
            this.failureOffset = failureOffset;
        }

        @Override
        public int read() throws IOException {
            throwIfAtFailure();
            return offset == bytes.length ? -1 : bytes[offset++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int targetOffset, int length) throws IOException {
            throwIfAtFailure();
            if (offset == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - offset);
            if (offset < failureOffset && offset + count > failureOffset) {
                count = failureOffset - offset;
            }
            System.arraycopy(bytes, offset, buffer, targetOffset, count);
            offset += count;
            return count;
        }

        private void throwIfAtFailure() throws IOException {
            if (offset == failureOffset) {
                throw new IOException("controlled read failure");
            }
        }
    }
}

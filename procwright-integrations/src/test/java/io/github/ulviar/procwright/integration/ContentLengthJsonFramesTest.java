/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.BAD_HEADER;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.BAD_LENGTH;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.EOF;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.INVALID_ENCODING;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.MISSING_LENGTH;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.OVERSIZED_FRAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ContentLengthJsonFramesTest {

    @Test
    void framesAndReadsJsonMessages() {
        JsonValue value = JsonValue.object(Map.of("ok", JsonValue.bool(true)));
        byte[] frame = ContentLengthJsonFrames.frame(value);

        JsonValue parsed = ContentLengthJsonFrames.read(new ByteArrayInputStream(frame), 1024);

        assertEquals(value, parsed);
    }

    @Test
    void rejectsMissingContentLength() {
        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class, () -> read("Content-Type: application/json\r\n\r\n{}"));

        assertEquals(MISSING_LENGTH, exception.reason());
    }

    @Test
    void rejectsMalformedHeaderLine() {
        IntegrationProtocolException exception =
                assertThrows(IntegrationProtocolException.class, () -> read("Content-Length 2\r\n\r\n{}"));

        assertEquals(BAD_HEADER, exception.reason());
    }

    @Test
    void rejectsInvalidContentLength() {
        IntegrationProtocolException exception =
                assertThrows(IntegrationProtocolException.class, () -> read("Content-Length: nope\r\n\r\n{}"));

        assertEquals(BAD_LENGTH, exception.reason());
    }

    @Test
    void rejectsDuplicateContentLength() {
        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class, () -> read("Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}"));

        assertEquals(BAD_HEADER, exception.reason());
    }

    @Test
    void rejectsHeadersBeyondFixedByteLimit() {
        String oversizedHeader = "X-Filler: " + "a".repeat(9000) + "\r\n\r\n{}";

        IntegrationProtocolException exception =
                assertThrows(IntegrationProtocolException.class, () -> read(oversizedHeader));

        assertEquals(BAD_HEADER, exception.reason());
    }

    @Test
    void rejectsOversizedBodyBeforeReadingIt() {
        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> ContentLengthJsonFrames.read(
                        new ByteArrayInputStream("Content-Length: 3\r\n\r\n{}".getBytes(StandardCharsets.UTF_8)), 2));

        assertEquals(OVERSIZED_FRAME, exception.reason());
    }

    @Test
    void distinguishesEofBeforeBodyCompletion() {
        IntegrationProtocolException exception =
                assertThrows(IntegrationProtocolException.class, () -> read("Content-Length: 3\r\n\r\n{}"));

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

    private static JsonValue read(String frame) {
        return ContentLengthJsonFrames.read(new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8)), 1024);
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ProtocolTranscriptBufferTest {

    @Test
    void transcriptDecoderDoesNotMarkUtf8CodePointSplitAcrossChunksAsMalformed() {
        ProtocolTranscriptBuffer buffer =
                new ProtocolTranscriptBuffer(1024, CharsetPolicy.report(StandardCharsets.UTF_8));
        byte[] bytes = "€".getBytes(StandardCharsets.UTF_8);

        buffer.appendStream("stdout", bytes, 1);
        buffer.appendStream("stdout", java.util.Arrays.copyOfRange(bytes, 1, bytes.length), 2);

        assertEquals(false, buffer.snapshot().malformed());
        assertEquals("stdout: €", buffer.snapshot().text());
    }

    @Test
    void transcriptDecoderDoesNotMarkUtf16CodePointSplitAcrossChunksAsMalformed() {
        ProtocolTranscriptBuffer buffer =
                new ProtocolTranscriptBuffer(1024, CharsetPolicy.report(StandardCharsets.UTF_16LE));
        byte[] bytes = "€".getBytes(StandardCharsets.UTF_16LE);

        buffer.appendStream("stdout", bytes, 1);
        buffer.appendStream("stdout", java.util.Arrays.copyOfRange(bytes, 1, bytes.length), 1);

        assertEquals(false, buffer.snapshot().malformed());
        assertEquals("stdout: €", buffer.snapshot().text());
    }

    @Test
    void transcriptDecoderMarksMalformedBytesWithoutThrowing() {
        ProtocolTranscriptBuffer buffer =
                new ProtocolTranscriptBuffer(1024, CharsetPolicy.report(StandardCharsets.UTF_8));

        buffer.appendStream("stdout", new byte[] {(byte) 0xFF}, 1);

        assertEquals(true, buffer.snapshot().malformed());
        assertEquals("stdout: \uFFFD", buffer.snapshot().text());
    }

    @Test
    void replacingPolicyStillMarksMalformedTranscriptBytes() {
        ProtocolTranscriptBuffer buffer =
                new ProtocolTranscriptBuffer(1024, CharsetPolicy.replace(StandardCharsets.UTF_8));

        buffer.appendStream("stdout", new byte[] {(byte) 0xFF}, 1);

        assertTrue(buffer.snapshot().malformed());
        assertEquals("stdout: \uFFFD", buffer.snapshot().text());
    }

    @Test
    void endOfStreamMarksIncompleteSequenceAsMalformed() {
        ProtocolTranscriptBuffer buffer =
                new ProtocolTranscriptBuffer(1024, CharsetPolicy.replace(StandardCharsets.UTF_8));

        buffer.appendStream("stdout", new byte[] {(byte) 0xD0}, 1);
        buffer.endStream("stdout");

        assertTrue(buffer.snapshot().malformed());
        assertEquals("stdout: \uFFFD", buffer.snapshot().text());
    }

    @Test
    void transcriptRetentionStaysBoundedForLargeDecodedChunks() {
        ProtocolTranscriptBuffer buffer =
                new ProtocolTranscriptBuffer(64, CharsetPolicy.report(StandardCharsets.UTF_8));
        byte[] bytes = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);

        buffer.appendStream("stdout", bytes, bytes.length);

        assertTrue(buffer.snapshot().truncated());
        assertTrue(buffer.snapshot().text().length() <= 64);
    }

    @Test
    void eachTranscriptStreamHasBoundedUndecodedState() throws Exception {
        ProtocolTranscriptBuffer buffer = new ProtocolTranscriptBuffer(
                4, CharsetPolicy.report(new IncrementalTextDecoderTest.NoProgressCharset()));
        byte[] atLimit = new byte[64];

        buffer.appendStream("stdout", atLimit, atLimit.length);
        buffer.appendStream("stderr", atLimit, atLimit.length);

        assertThrows(
                ProtocolTranscriptBuffer.TranscriptDecodingException.class,
                () -> buffer.appendStream("stdout", new byte[] {1}, 1));
        assertThrows(
                ProtocolTranscriptBuffer.TranscriptDecodingException.class,
                () -> buffer.appendStream("stderr", new byte[] {1}, 1));
        assertTrue(buffer.snapshot().text().length() <= 4);
    }
}

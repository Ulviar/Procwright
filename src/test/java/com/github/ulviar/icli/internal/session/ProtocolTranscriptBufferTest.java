package com.github.ulviar.icli.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.ulviar.icli.command.CharsetPolicy;
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
    void transcriptDecoderMarksMalformedBytesWithoutThrowing() {
        ProtocolTranscriptBuffer buffer =
                new ProtocolTranscriptBuffer(1024, CharsetPolicy.report(StandardCharsets.UTF_8));

        buffer.appendStream("stdout", new byte[] {(byte) 0xFF}, 1);

        assertEquals(true, buffer.snapshot().malformed());
        assertEquals("stdout: \uFFFD", buffer.snapshot().text());
    }
}

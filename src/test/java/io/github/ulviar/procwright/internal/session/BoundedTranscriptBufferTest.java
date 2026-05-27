package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BoundedTranscriptBufferTest {

    @Test
    void attributesInterleavedChunksToLabels() {
        BoundedTranscriptBuffer buffer = new BoundedTranscriptBuffer(128);

        buffer.appendStream("stdout", "out");
        buffer.appendStream("stderr", "err\n");
        buffer.appendStream("stdout", "next\n");

        BoundedTranscriptBuffer.Snapshot snapshot = buffer.snapshot();

        assertEquals("stdout: out\nstderr: err\nstdout: next\n", snapshot.text());
        assertFalse(snapshot.truncated());
    }

    @Test
    void actionStartsOnItsOwnLineAndResetsStreamLabel() {
        BoundedTranscriptBuffer buffer = new BoundedTranscriptBuffer(128);

        buffer.appendStream("stdout", "ready");
        buffer.appendAction("send line: <redacted>");
        buffer.appendStream("stdout", "done");

        assertEquals(
                "stdout: ready\nsend line: <redacted>\nstdout: done",
                buffer.snapshot().text());
    }

    @Test
    void truncatesFromTheFront() {
        BoundedTranscriptBuffer buffer = new BoundedTranscriptBuffer(8);

        boolean newlyTruncated = buffer.appendStream("stdout", "123456789");

        assertTrue(newlyTruncated);
        assertEquals("123456789".substring(1), buffer.snapshot().text());
        assertTrue(buffer.snapshot().truncated());
        assertFalse(buffer.appendStream("stdout", "0"));
    }
}

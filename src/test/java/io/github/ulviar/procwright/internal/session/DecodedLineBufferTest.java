/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DecodedLineBufferTest {

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new DecodedLineBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new DecodedLineBuffer(-1));
    }

    @Test
    void exposesOnlyTheFirstDecodedLineAndPreservesSplitCrLf() throws Exception {
        DecodedLineBuffer buffer = new DecodedLineBuffer(32);
        buffer.append("first\r".toCharArray(), 0, 6);
        buffer.append("\nsecond\n".toCharArray(), 0, 8);
        StringBuilder first = new StringBuilder();

        int firstCount = buffer.copyFirstLineTo(first);

        assertEquals("first\r\n", first.toString());
        assertEquals(7, firstCount);
        buffer.consume(firstCount);
        StringBuilder second = new StringBuilder();
        assertEquals(7, buffer.copyFirstLineTo(second));
        assertEquals("second\n", second.toString());
    }

    @Test
    void consumedCapacityCanBeReusedWithoutLosingPendingContent() throws Exception {
        DecodedLineBuffer buffer = new DecodedLineBuffer(4);
        buffer.append("a\nbc".toCharArray(), 0, 4);
        buffer.consume(2);

        buffer.append("d\n".toCharArray(), 0, 2);

        StringBuilder line = new StringBuilder();
        assertEquals(4, buffer.copyFirstLineTo(line));
        assertEquals("bcd\n", line.toString());
        assertEquals(0, buffer.remainingCapacity());
    }

    @Test
    void appendBeyondLimitIsAtomic() throws Exception {
        DecodedLineBuffer buffer = new DecodedLineBuffer(4);
        buffer.append("abc".toCharArray(), 0, 3);

        DecodedLineBuffer.LimitExceededException failure = assertThrows(
                DecodedLineBuffer.LimitExceededException.class, () -> buffer.append("de".toCharArray(), 0, 2));

        assertEquals(4, failure.limit());
        assertEquals(1, buffer.remainingCapacity());
        StringBuilder retained = new StringBuilder();
        assertEquals(3, buffer.copyFirstLineTo(retained));
        assertEquals("abc", retained.toString());
    }

    @Test
    void rollbackRestoresTheCheckpointWithoutDiscardingEarlierContent() throws Exception {
        DecodedLineBuffer buffer = new DecodedLineBuffer(16);
        buffer.append("first".toCharArray(), 0, 5);
        int checkpoint = buffer.checkpoint();
        buffer.append("\nsecond".toCharArray(), 0, 7);

        buffer.rollback(checkpoint);

        StringBuilder retained = new StringBuilder();
        assertEquals(5, buffer.copyFirstLineTo(retained));
        assertEquals("first", retained.toString());
        assertEquals(11, buffer.remainingCapacity());
    }

    @Test
    void consumeAndRollbackRejectPositionsOutsidePendingContent() throws Exception {
        DecodedLineBuffer buffer = new DecodedLineBuffer(4);
        buffer.append("ab".toCharArray(), 0, 2);

        assertThrows(IllegalArgumentException.class, () -> buffer.consume(-1));
        assertThrows(IllegalArgumentException.class, () -> buffer.consume(3));
        assertThrows(IllegalArgumentException.class, () -> buffer.rollback(-1));
        assertThrows(IllegalArgumentException.class, () -> buffer.rollback(3));
        assertTrue(buffer.hasPending());

        buffer.consume(2);

        assertFalse(buffer.hasPending());
        assertEquals(4, buffer.remainingCapacity());
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ProtocolResponseReaderTest {

    private static final ProtocolRuntimeFailures FAILURES = new ProtocolRuntimeFailures() {
        @Override
        public ProtocolSessionException timeout(Throwable cause) {
            return failure(ProtocolSessionException.Reason.TIMEOUT, "timeout", cause);
        }

        @Override
        public ProtocolSessionException closed(Throwable cause) {
            return failure(ProtocolSessionException.Reason.CLOSED, "closed", cause);
        }

        @Override
        public ProtocolSessionException eof() {
            return failure(ProtocolSessionException.Reason.EOF, "eof", null);
        }

        @Override
        public ProtocolSessionException failure(
                ProtocolSessionException.Reason reason, String message, Throwable cause) {
            return new ProtocolSessionException(
                    reason, new ProtocolTranscript("", false, false, false), message, cause);
        }
    };

    @Test
    void readLineAcceptsLineOfExactlyMaxCharsWithLineFeedTerminator() {
        ProtocolResponseReader reader = readerFor("abcde\n");

        assertEquals("abcde", reader.readLine(5));
    }

    @Test
    void readLineAcceptsLineOfExactlyMaxCharsWithCrLfTerminator() {
        ProtocolResponseReader reader = readerFor("abcde\r\n");

        assertEquals("abcde", reader.readLine(5));
    }

    @Test
    void readLineRejectsLineBeyondMaxCharsWithLineFeedTerminator() {
        ProtocolResponseReader reader = readerFor("abcdef\n");

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> reader.readLine(5));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void readLineRejectsLineBeyondMaxCharsWithCrLfTerminator() {
        ProtocolResponseReader reader = readerFor("abcdef\r\n");

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> reader.readLine(5));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void readLineKeepsLoneCarriageReturnInsideLineContent() {
        ProtocolResponseReader reader = readerFor("ab\rcd\n");

        assertEquals("ab\rcd", reader.readLine(5));
    }

    @Test
    void dropOldestQueueRetainsOnlyNewestBytesUpToLimit() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.DROP_OLDEST);
        queue.offer(new byte[] {1, 2, 3});
        queue.offer(new byte[] {4, 5, 6});

        ProtocolResponseReader reader = reader(queue);

        assertArrayEquals(new byte[] {3, 4, 5, 6}, reader.readExactly(4));
    }

    @Test
    void dropOldestQueueTrimsSingleChunkLargerThanLimit() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.DROP_OLDEST);
        queue.offer(new byte[] {1, 2, 3, 4, 5});

        ProtocolResponseReader reader = reader(queue);

        assertArrayEquals(new byte[] {4, 5}, reader.readExactly(2));
    }

    @Test
    void strictQueueRejectsBytesBeyondLimit() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);

        assertEquals(true, queue.offer(new byte[] {1, 2, 3}));
        assertEquals(false, queue.offer(new byte[] {4, 5}));
    }

    private static ProtocolResponseReader readerFor(String output) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(64 * 1024, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(output.getBytes(StandardCharsets.UTF_8));
        queue.eof();
        return reader(queue);
    }

    private static ProtocolResponseReader reader(ProtocolOutputQueue queue) {
        long deadlineNanos = DurationSupport.deadlineFromNow(Duration.ofSeconds(2));
        ProtocolResponseBudget budget = new ProtocolResponseBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, FAILURES);
        return new ProtocolResponseReader(queue, ProtocolSessionOptions.defaults(), deadlineNanos, budget, FAILURES);
    }
}

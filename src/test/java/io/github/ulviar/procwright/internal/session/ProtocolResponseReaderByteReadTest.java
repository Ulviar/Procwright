/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ProtocolResponseReaderByteReadTest extends ProtocolResponseReaderTestSupport {

    @Test
    void failOnReadQueueAcceptsItsExactByteLimit() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        queue.offer(new byte[] {1, 2, 3, 4});

        ProtocolResponseReader reader = reader(queue);

        assertArrayEquals(new byte[] {1, 2, 3, 4}, reader.readExactly(4));
    }

    @Test
    void failOnReadQueueNeverExposesSuffixAfterOverflow() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        queue.offer(new byte[] {1, 2, 3});
        queue.offer(new byte[] {4, 5, 6, '\n'});

        ProtocolResponseReader reader = reader(queue);

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> reader.readLine(4));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void strictQueueRejectsBytesBeyondLimit() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);

        assertEquals(true, queue.offer(new byte[] {1, 2, 3}));
        assertEquals(false, queue.offer(new byte[] {4, 5}));
    }

    @Test
    void readExactlyRejectsOversizedFrameBeforeConsumingQueuedBytes() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        ProtocolResponseReader reader = reader(queue, 4, Integer.MAX_VALUE, Duration.ofSeconds(2));

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> reader.readExactly(8));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertEquals((byte) 1, reader.readByte());
    }

    @Test
    void zeroLengthReadsSkipDeadlineTerminalAndBudgetChecks() {
        for (Consumer<ProtocolResponseReader> zeroLengthRead : zeroLengthReads()) {
            ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
            queue.offer(new byte[] {1, 2});
            ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
            ProtocolResponseBudget budget = new ProtocolResponseBudget(0, 0, FAILURES);
            ProtocolResponseReader reader =
                    requestReader(queue, options, budget, streamDecoder(options), readerScope(), Duration.ZERO);

            zeroLengthRead.accept(reader);

            assertEquals(0, budget.remainingBytes());
            assertEquals(0, budget.remainingChars());
            ProtocolSessionException terminal = assertThrows(ProtocolSessionException.class, reader::readByte);
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, terminal.reason());
        }
    }

    @Test
    void zeroLengthReadsStillRequireLiveReaderCapability() {
        RequestCapabilityScope scope = new RequestCapabilityScope("expired zero-length reader");
        scope.activate();
        scope.invalidate();
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        ProtocolResponseReader reader = requestReader(
                new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT),
                options,
                new ProtocolResponseBudget(1, 1, FAILURES),
                streamDecoder(options),
                scope,
                Duration.ofSeconds(2));

        for (Consumer<ProtocolResponseReader> zeroLengthRead : zeroLengthReads()) {
            assertThrows(IllegalStateException.class, () -> zeroLengthRead.accept(reader));
        }
    }

    @Test
    void invalidArgumentsPrecedeExpiredReaderCapabilityForZeroLengthReads() {
        RequestCapabilityScope scope = new RequestCapabilityScope("expired invalid-argument reader");
        scope.activate();
        scope.invalidate();
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        ProtocolResponseReader reader = requestReader(
                new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT),
                options,
                new ProtocolResponseBudget(1, 1, FAILURES),
                streamDecoder(options),
                scope,
                Duration.ofSeconds(2));

        assertThrows(NullPointerException.class, () -> reader.read(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.read(new byte[0], 1, 0));
        assertEquals(
                "length must not be negative",
                assertThrows(IllegalArgumentException.class, () -> reader.readExactly(-1))
                        .getMessage());
        assertEquals(
                "byteLength must not be negative",
                assertThrows(IllegalArgumentException.class, () -> reader.readTextExactly(-1, 0))
                        .getMessage());
        assertEquals(
                "maxChars must be positive",
                assertThrows(IllegalArgumentException.class, () -> reader.readTextExactly(0, 0))
                        .getMessage());
    }

    @Test
    void bulkReadDoesNotCopyOrConsumeBytesRejectedByResponseBudget() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1, 2});
        queue.eof();
        ProtocolResponseReader reader = reader(queue, 1, Integer.MAX_VALUE, Duration.ofSeconds(2));
        byte[] target = new byte[] {9, 9};

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.read(target, 0, target.length));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertArrayEquals(new byte[] {9, 9}, target);
        assertEquals(1, queue.readUnsignedByte(DurationSupport.deadlineFromNow(Duration.ofSeconds(2)), FAILURES));
    }

    @Test
    void exhaustedResponseBudgetFailsBeforeConsumingNextByte() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1, 2});
        queue.eof();
        ProtocolResponseReader reader = reader(queue, 1, Integer.MAX_VALUE, Duration.ofSeconds(2));

        assertEquals((byte) 1, reader.readByte());
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, reader::readByte);

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertEquals(2, queue.readUnsignedByte(DurationSupport.deadlineFromNow(Duration.ofSeconds(2)), FAILURES));
    }

    @Test
    void queuedBytesDoNotBypassExpiredDeadline() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader = reader(queue, 1, Integer.MAX_VALUE, Duration.ZERO);

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, reader::readByte);

        assertEquals(ProtocolSessionException.Reason.TIMEOUT, exception.reason());
    }

    @Test
    void pendingOverflowMarkerPrecedesExhaustedGlobalResponseBudget() {
        ProtocolResponseBudget budget = new ProtocolResponseBudget(1, Integer.MAX_VALUE, FAILURES);
        ProtocolOutputQueue stdout = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        ProtocolOutputQueue stderr = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        assertEquals(true, stdout.offer(new byte[] {1}));
        assertEquals(true, stderr.offer(new byte[] {2, 3}));
        long deadline = DurationSupport.deadlineFromNow(Duration.ofSeconds(2));
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        ProtocolResponseReader stdoutReader = new ProtocolResponseReader(
                stdout, options, deadline, budget, streamDecoder(options), FAILURES, readerScope());
        ProtocolResponseReader stderrReader = new ProtocolResponseReader(
                stderr, options, deadline, budget, streamDecoder(options), FAILURES, readerScope());
        assertEquals((byte) 1, stdoutReader.readByte());

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, stderrReader::readByte);

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
    }

    @Test
    void overflowAfterOrdinaryReadStartedDoesNotBypassExhaustedResponseBudget() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        ProtocolResponseReader reader = reader(queue, 1, Integer.MAX_VALUE, Duration.ofSeconds(2));
        assertEquals(true, queue.offer(new byte[] {1}));
        assertEquals((byte) 1, reader.readByte());
        assertEquals(true, queue.offer(new byte[] {2, 3}));

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, reader::readByte);

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void pendingOverflowMarkerPrecedesExpiredDeadline() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        assertEquals(true, queue.offer(new byte[] {1, 2}));
        ProtocolResponseReader reader = reader(queue, 1, Integer.MAX_VALUE, Duration.ZERO);

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, reader::readByte);

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
    }

    @Test
    void terminalObserverReturnsSecondSnapshotUpgradeAfterBytesWereConsumed() {
        AtomicInteger snapshots = new AtomicInteger();
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                1,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                System::nanoTime,
                () -> {},
                () -> {},
                () -> snapshots.incrementAndGet() == 1 ? OptionalInt.empty() : OptionalInt.of(17),
                null);
        queue.offer(new byte[] {'a'});
        queue.eof();
        ProtocolResponseReader reader = reader(queue, 2, 1, Duration.ofSeconds(2));

        assertEquals('a', reader.readByte());
        ProtocolSessionException exited = assertThrows(ProtocolSessionException.class, reader::readByte);

        assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, exited.reason());
        assertEquals(17, exited.exitCode().orElseThrow());
        assertEquals(2, snapshots.get());
    }

    @Test
    void terminalQueueIgnoresLateOutputWithoutBreakingItsBound() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        queue.failAndClear(
                ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, new IllegalStateException("terminal"));

        assertDoesNotThrow(() -> {
            queue.offer(new byte[] {1, 2, 3, 4});
            queue.offer(new byte[] {5});
        });

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader(queue).readByte());
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
    }
}

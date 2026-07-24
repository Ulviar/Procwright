/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ProtocolResponseReaderCharsetFixtures.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.nio.BufferUnderflowException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ProtocolResponseReaderDecoderStateTest extends ProtocolResponseReaderTestSupport {

    @Test
    void rawAndExactReadsRejectPendingContinuousInputBeforeConsumingBytes() {
        List<Consumer<ProtocolResponseReader>> rawReads = List.of(
                ProtocolResponseReader::readByte,
                reader -> reader.read(new byte[1], 0, 1),
                reader -> reader.readExactly(1),
                reader -> reader.readUntil((byte) 'X', 1),
                reader -> reader.readTextExactly(1, 1));

        for (Consumer<ProtocolResponseReader> rawRead : rawReads) {
            PendingInputFixture fixture = pendingInputFixture();

            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> rawRead.accept(fixture.reader()));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            assertEquals(
                    'X',
                    fixture.queue().readUnsignedByte(DurationSupport.deadlineFromNow(Duration.ofSeconds(2)), FAILURES));
        }
    }

    @Test
    void exactFieldReadCanBridgeCompleteContinuousTextBoundaries() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xc3, (byte) 0xa9, '\n', 'X', 'o', 'k', '\n'});
        ProtocolResponseReader reader =
                reader(queue, 8, 8, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        assertEquals("é", reader.readLine(1));
        assertEquals("X", reader.readTextExactly(1, 1));
        assertEquals("ok", reader.readLine(2));
    }

    @Test
    void rawReadCanResumeContinuousTextAtAnAdapterOwnedBoundary() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'X', 'o', 'k', '\n'});
        ProtocolResponseReader reader =
                reader(queue, 4, 4, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        assertArrayEquals(new byte[] {'X'}, reader.readExactly(1));
        assertEquals("ok", reader.readLine(2));
    }

    @Test
    void pendingDecoderBoundaryFailurePrecedesTerminalThatArrivedAfterContinuousReadStarted() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        queue.offer(new byte[] {(byte) 0xc3});
        ProtocolResponseReader reader =
                reader(queue, 2, 2, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));
        assertEquals("", reader.readTextUntil((byte) 0xc3, 1));
        queue.offer(new byte[] {'X', 'Y'});

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, reader::readByte);

        assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
    }

    @Test
    void pendingDecoderBoundaryFailurePrecedesExhaustedByteBudget() {
        PendingInputFixture fixture = pendingInputFixture(1);

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, fixture.reader()::readByte);

        assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
        assertEquals(
                'X',
                fixture.queue().readUnsignedByte(DurationSupport.deadlineFromNow(Duration.ofSeconds(2)), FAILURES));
    }

    @Test
    void textDecoderStateCanSpanRequestScopedReadersForOneOutputStream() {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withCharsetPolicy(io.github.ulviar.procwright.command.CharsetPolicy.report(StandardCharsets.UTF_16));
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(options.charsetPolicy());
        ProtocolOutputQueue firstOutput = new ProtocolOutputQueue(32, ProtocolOutputQueue.OverflowPolicy.STRICT);
        firstOutput.offer(utf16LeWithBom("first\n"));
        ProtocolResponseReader first = new ProtocolResponseReader(
                firstOutput,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(32, 32, FAILURES),
                decoder,
                FAILURES,
                readerScope());
        ProtocolOutputQueue secondOutput = new ProtocolOutputQueue(32, ProtocolOutputQueue.OverflowPolicy.STRICT);
        secondOutput.offer("second\n".getBytes(StandardCharsets.UTF_16LE));
        ProtocolResponseReader second = new ProtocolResponseReader(
                secondOutput,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(32, 32, FAILURES),
                decoder,
                FAILURES,
                readerScope());

        assertEquals("first", first.readLine(16));
        assertEquals("second", second.readLine(16));
    }

    @Test
    void requestScopedReadersPreserveCoalescedBytesFromTheSameQueueChunk() {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(options.charsetPolicy());
        ProtocolOutputQueue output = new ProtocolOutputQueue(32, ProtocolOutputQueue.OverflowPolicy.STRICT);
        output.offer("first\nsecond\n".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader first = new ProtocolResponseReader(
                output,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(32, 32, FAILURES),
                decoder,
                FAILURES,
                readerScope());
        ProtocolResponseReader second = new ProtocolResponseReader(
                output,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(32, 32, FAILURES),
                decoder,
                FAILURES,
                readerScope());

        assertEquals("first", first.readLine(16));
        assertEquals("second", second.readLine(16));
    }

    @Test
    void requestScopedReadersOwnAtomicDecodedSuffixAndChargeTheirOwnBudgets() {
        ProtocolSessionSettings options = atomicLineOptions(2, 8, AtomicLinesCharset.duringDecode("b"));
        ProtocolTextDecoderState decoder = streamDecoder(options);
        ProtocolOutputQueue output = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        output.offer(new byte[] {1});
        output.eof();
        ProtocolResponseBudget firstBudget = new ProtocolResponseBudget(1, 2, FAILURES);
        RequestCapabilityScope firstScope = new RequestCapabilityScope("first request reader");
        firstScope.activate();
        ProtocolResponseReader first =
                requestReader(output, options, firstBudget, decoder, firstScope, Duration.ofSeconds(2));

        assertEquals("a", first.readLine(1));
        assertEquals(0, firstBudget.remainingBytes());
        assertEquals(0, firstBudget.remainingChars());
        firstScope.invalidate();
        assertThrows(IllegalStateException.class, () -> first.readLine(1));

        ProtocolResponseBudget secondBudget = new ProtocolResponseBudget(1, 2, FAILURES);
        ProtocolResponseReader second =
                requestReader(output, options, secondBudget, decoder, readerScope(), Duration.ofSeconds(2));
        assertEquals("b", second.readLine(1));
        assertEquals(1, secondBudget.remainingBytes());
        assertEquals(0, secondBudget.remainingChars());

        ProtocolResponseReader third = requestReader(
                output,
                options,
                new ProtocolResponseBudget(1, 2, FAILURES),
                decoder,
                readerScope(),
                Duration.ofSeconds(2));
        ProtocolSessionException eof = assertThrows(ProtocolSessionException.class, () -> third.readLine(1));
        assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
    }

    @Test
    void timeoutDoesNotConsumeRequestScopedDecodedSuffix() {
        ProtocolSessionSettings options = atomicLineOptions(2, 8, new SplitAtomicLineCharset());
        ProtocolTextDecoderState decoder = streamDecoder(options);
        ProtocolOutputQueue output = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        output.offer(new byte[] {1, 2});
        output.eof();
        assertEquals(
                "a",
                requestReader(output, options, decoder, Duration.ofSeconds(2)).readLine(1));

        ProtocolResponseReader expired = requestReader(output, options, decoder, Duration.ZERO);
        ProtocolSessionException timeout = assertThrows(ProtocolSessionException.class, () -> expired.readLine(1));
        assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
        assertEquals(1, output.pendingBytes());

        assertEquals(
                "b",
                requestReader(output, options, decoder, Duration.ofSeconds(2)).readLine(1));
        assertEquals(0, output.pendingBytes());
    }

    @Test
    void closeDoesNotDiscardAlreadyDecodedRequestScopedSuffix() {
        ProtocolSessionSettings options = atomicLineOptions(2, 8, AtomicLinesCharset.duringDecode("b"));
        ProtocolTextDecoderState decoder = streamDecoder(options);
        ProtocolOutputQueue output = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        output.offer(new byte[] {1});
        assertEquals(
                "a",
                requestReader(output, options, decoder, Duration.ofSeconds(2)).readLine(1));

        output.close();

        assertEquals(
                "b",
                requestReader(output, options, decoder, Duration.ofSeconds(2)).readLine(1));
        ProtocolSessionException closed = assertThrows(
                ProtocolSessionException.class, () -> requestReader(output, options, decoder, Duration.ofSeconds(2))
                        .readLine(1));
        assertEquals(ProtocolSessionException.Reason.CLOSED, closed.reason());
    }

    @Test
    void rawAndByteDelimitedMethodsRejectRequestScopedDecodedSuffixWithoutConsumingIt() {
        List<Consumer<ProtocolResponseReader>> incompatibleReads = List.of(
                ProtocolResponseReader::readByte,
                reader -> reader.read(new byte[1], 0, 1),
                reader -> reader.readExactly(1),
                reader -> reader.readUntil((byte) 'z', 1),
                reader -> reader.readTextExactly(1, 1),
                reader -> reader.readTextUntil((byte) 'z', 1));

        for (Consumer<ProtocolResponseReader> incompatibleRead : incompatibleReads) {
            ProtocolSessionSettings options = atomicLineOptions(2, 8, AtomicLinesCharset.duringDecode("b"));
            ProtocolTextDecoderState decoder = streamDecoder(options);
            ProtocolOutputQueue output = atomicLineOutput(8);
            assertEquals(
                    "a",
                    requestReader(output, options, decoder, Duration.ofSeconds(2))
                            .readLine(1));

            ProtocolSessionException failure = assertThrows(
                    ProtocolSessionException.class,
                    () -> incompatibleRead.accept(requestReader(output, options, decoder, Duration.ofSeconds(2))));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, failure.reason());
            assertEquals(0, output.pendingBytes());
            assertEquals(
                    "b",
                    requestReader(output, options, decoder, Duration.ofSeconds(2))
                            .readLine(1));
        }
    }

    @Test
    void zeroLengthReadsPreserveRequestScopedDecodedSuffixAndQueuedBytes() {
        for (Consumer<ProtocolResponseReader> zeroLengthRead : zeroLengthReads()) {
            ProtocolSessionSettings options = atomicLineOptions(2, 8, AtomicLinesCharset.duringDecode("b"));
            ProtocolTextDecoderState decoder = streamDecoder(options);
            ProtocolOutputQueue output = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
            output.offer(new byte[] {1});
            assertEquals(
                    "a",
                    requestReader(output, options, decoder, Duration.ofSeconds(2))
                            .readLine(1));
            output.offer(new byte[] {'X'});
            ProtocolResponseBudget budget = new ProtocolResponseBudget(1, 2, FAILURES);
            ProtocolResponseReader reader =
                    requestReader(output, options, budget, decoder, readerScope(), Duration.ofSeconds(2));

            zeroLengthRead.accept(reader);

            assertEquals(1, budget.remainingBytes());
            assertEquals(2, budget.remainingChars());
            assertEquals(1, output.pendingBytes());
            assertEquals("b", reader.readLine(1));
            assertEquals(1, output.pendingBytes());
            assertEquals((byte) 'X', reader.readByte());
        }
    }

    @Test
    void decodedSuffixIsBoundedByBothResponseAndBacklogLimits() {
        for (ProtocolSessionSettings options : List.of(
                atomicLineOptions(2, 8, AtomicLinesCharset.duringDecode("bc")),
                atomicLineOptions(4, 2, AtomicLinesCharset.duringDecode("bc")))) {
            ProtocolTextDecoderState decoder = streamDecoder(options);
            ProtocolOutputQueue output = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
            output.offer(new byte[] {1});

            ProtocolSessionException failure = assertThrows(
                    ProtocolSessionException.class, () -> requestReader(output, options, decoder, Duration.ofSeconds(2))
                            .readLine(1));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            assertEquals(0, output.pendingBytes());
            assertFalse(decoder.hasPendingLineOutput());
        }
    }

    @Test
    void failedOutputCommitRollsBackDecodedSuffix() {
        ProtocolOutputQueue output = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        IllegalStateException terminalCause = new IllegalStateException("terminal replaced decoded source");
        Charset charset = new AtomicLinesTerminalCharset(
                () -> output.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, terminalCause));
        ProtocolSessionSettings options = atomicLineOptions(4, 8, charset);
        ProtocolTextDecoderState decoder = streamDecoder(options);
        output.offer(new byte[] {1});

        ProtocolSessionException failure = assertThrows(
                ProtocolSessionException.class, () -> requestReader(output, options, decoder, Duration.ofSeconds(2))
                        .readLine(1));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure.reason());
        assertSame(terminalCause, failure.getCause());
        assertFalse(decoder.hasPendingLineOutput());
    }

    @Test
    void persistentTextDecoderCannotAccumulateUndecodedBytesAcrossRequests() throws Exception {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withCharsetPolicy(CharsetPolicy.report(new IncrementalTextDecoderTest.NoProgressCharset()));
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(options.charsetPolicy(), 4);
        StringBuilder firstRequest = new StringBuilder();
        decoder.decode((byte) 1, firstRequest);
        decoder.decode((byte) 2, firstRequest);
        ProtocolOutputQueue secondOutput = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        secondOutput.offer(new byte[] {3, 4, 5});
        ProtocolResponseReader secondRequest = new ProtocolResponseReader(
                secondOutput,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(8, 8, FAILURES),
                decoder,
                FAILURES,
                readerScope());

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> secondRequest.readTextUntil((byte) 0, 8));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
    }

    @Test
    void persistentTextDecoderBoundsOutputOnlyOverflowBeforeAppendingIt() {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.report(new IncrementalTextDecoderTest.OutputOnlyOverflowCharset()), 64, 256);
        StringBuilder target = new StringBuilder();

        CharacterCodingException exception =
                assertThrows(CharacterCodingException.class, () -> decoder.decode((byte) 1, target));

        assertEquals("Decoder produced more than 256 chars without consuming input", exception.getMessage());
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderBoundsOutputOnlyFlushBeforeAppendingIt() throws Exception {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.report(new IncrementalTextDecoderTest.OutputOnlyFlushCharset()), 64, 256);
        StringBuilder target = new StringBuilder();
        decoder.decode((byte) 1, target);

        CharacterCodingException exception = assertThrows(CharacterCodingException.class, () -> decoder.finish(target));

        assertEquals("Decoder produced more than 256 chars without consuming input", exception.getMessage());
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderAllowsOutputBufferBeforeLaterInputConsumption() throws Exception {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.report(new IncrementalTextDecoderTest.OneOutputBufferBeforeConsumptionCharset()),
                64,
                128);
        StringBuilder target = new StringBuilder();

        decoder.decode((byte) 1, target);

        assertEquals(129, target.length());
        assertEquals('y', target.charAt(128));
    }

    @Test
    void persistentTextDecoderRejectsInputRewindBeforeAppendingMoreOutput() {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.report(new IncrementalTextDecoderTest.FiniteRewindingCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();

        IncrementalTextDecoder.DecoderStateException exception = assertThrows(
                IncrementalTextDecoder.DecoderStateException.class, () -> decoder.decode((byte) 1, target));

        assertEquals("Decoder moved input position backwards from 1 to 0", exception.getMessage());
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderNormalizesInvalidReplacementLengthBeforeAppendingOutput() {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.replace(new IncrementalTextDecoderTest.FiniteErrorAfterExhaustionCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();

        IncrementalTextDecoder.DecoderStateException exception = assertThrows(
                IncrementalTextDecoder.DecoderStateException.class, () -> decoder.decode((byte) 1, target));

        assertEquals("Charset decoder failed during decode", exception.getMessage());
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderNormalizesFlushRuntimeFailureBeforeAppendingOutput() throws Exception {
        IllegalArgumentException cause = new IllegalArgumentException("invalid flush state");
        ProtocolTextDecoderState decoder =
                new ProtocolTextDecoderState(CharsetPolicy.replace(new RuntimeFailureOnFlushCharset(cause)), 64, 1024);
        StringBuilder target = new StringBuilder();
        decoder.decode((byte) 1, target);

        IncrementalTextDecoder.DecoderStateException exception =
                assertThrows(IncrementalTextDecoder.DecoderStateException.class, () -> decoder.finish(target));

        assertEquals("Charset decoder failed during flush", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderNormalizesCoderMalfunctionBeforeAppendingOutput() {
        ProtocolTextDecoderState decoder =
                new ProtocolTextDecoderState(CharsetPolicy.report(new CoderMalfunctionCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();

        IncrementalTextDecoder.DecoderStateException exception = assertThrows(
                IncrementalTextDecoder.DecoderStateException.class, () -> decoder.decode((byte) 1, target));

        CoderMalfunctionError cause = assertInstanceOf(CoderMalfunctionError.class, exception.getCause());
        assertInstanceOf(BufferUnderflowException.class, cause.getCause());
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderDoesNotMaskCodingErrorResult() {
        ProtocolTextDecoderState decoder =
                new ProtocolTextDecoderState(CharsetPolicy.report(StandardCharsets.UTF_8), 64, 1024);
        StringBuilder target = new StringBuilder();

        CharacterCodingException exception =
                assertThrows(CharacterCodingException.class, () -> decoder.decode((byte) 0xff, target));

        assertInstanceOf(MalformedInputException.class, exception);
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderDoesNotMaskFlushCodingErrorResult() throws Exception {
        ProtocolTextDecoderState decoder =
                new ProtocolTextDecoderState(CharsetPolicy.report(new CodingErrorOnFlushCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();
        decoder.decode((byte) 1, target);

        CharacterCodingException exception = assertThrows(CharacterCodingException.class, () -> decoder.finish(target));

        assertInstanceOf(MalformedInputException.class, exception);
        assertEquals(0, target.length());
    }

    @Test
    void persistentTextDecoderDoesNotAppendPrefixProducedWithMalformedResult() {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.report(new IncrementalTextDecoderTest.OutputThenMalformedCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();

        assertThrows(CharacterCodingException.class, () -> decoder.decode((byte) 1, target));

        assertEquals("", target.toString());
    }

    @Test
    void persistentTextDecoderDoesNotPublishOverflowOutputBeforeLaterMalformedResult() {
        ProtocolTextDecoderState decoder =
                new ProtocolTextDecoderState(CharsetPolicy.report(new OverflowThenMalformedCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();

        CharacterCodingException exception =
                assertThrows(CharacterCodingException.class, () -> decoder.decode((byte) 1, target));

        assertInstanceOf(MalformedInputException.class, exception);
        assertEquals("", target.toString());
    }

    @Test
    void persistentTextDecoderDoesNotAppendFlushOutputProducedWithMalformedResult() throws Exception {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.report(new IncrementalTextDecoderTest.OutputThenMalformedFlushCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();
        decoder.decode((byte) 1, target);

        assertThrows(CharacterCodingException.class, () -> decoder.finish(target));

        assertEquals("", target.toString());
    }

    @Test
    void persistentTextDecoderDoesNotPublishFlushOverflowBeforeLaterMalformedResult() throws Exception {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                CharsetPolicy.report(new IncrementalTextDecoderTest.OverflowThenMalformedFlushCharset()), 64, 1024);
        StringBuilder target = new StringBuilder();
        decoder.decode((byte) 1, target);

        CharacterCodingException exception = assertThrows(CharacterCodingException.class, () -> decoder.finish(target));

        assertInstanceOf(MalformedInputException.class, exception);
        assertEquals("", target.toString());
    }

    @Test
    void strictTextDecoderReportsTruncatedMultibyteSequenceAtEof() {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withCharsetPolicy(io.github.ulviar.procwright.command.CharsetPolicy.report(StandardCharsets.UTF_8));
        ProtocolOutputQueue queue = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xc3});
        queue.eof();
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(8, 8, FAILURES),
                streamDecoder(options),
                FAILURES,
                readerScope());

        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> reader.readLine(8));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
    }
}

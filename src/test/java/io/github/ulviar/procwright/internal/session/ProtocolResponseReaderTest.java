/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ProtocolResponseReaderTest {

    private static final int FORMER_STAGED_OUTPUT_LIMIT = 2 * 1024 * 1024;

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
        public ProtocolSessionException processExited(OptionalInt exitCode) {
            return new ProtocolSessionException(
                    ProtocolSessionException.Reason.PROCESS_EXITED,
                    new ProtocolTranscript("", false, false),
                    exitCode,
                    "process exited",
                    null);
        }

        @Override
        public ProtocolSessionException failure(
                ProtocolSessionException.Reason reason, String message, Throwable cause) {
            return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), message, cause);
        }
    };

    @Test
    void expiredReaderCapabilityRejectsBeforeQueuedOutputOrDecoderStateCanChange() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("queued\n".getBytes(StandardCharsets.UTF_8));
        RequestCapabilityScope scope = new RequestCapabilityScope("expired protocol reader");
        scope.activate();
        scope.invalidate();
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(1)),
                new ProtocolResponseBudget(16, 16, FAILURES),
                streamDecoder(options),
                FAILURES,
                scope);
        long pendingBeforeRead = queue.pendingBytes();

        assertThrows(IllegalStateException.class, () -> reader.readLine(16));

        assertEquals(pendingBeforeRead, queue.pendingBytes());
    }

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
    void readLineLimitFailureCommitsOnlyThroughTerminatorAndPreservesSuffix() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("ab\nnext\n".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader reader = reader(queue);

        ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> reader.readLine(1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(5, queue.pendingBytes());
    }

    @Test
    void readLineClassifiesTerminatorAwareLimitBeforeTerminalReplacesItsWindow() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(8, ProtocolOutputQueue.OverflowPolicy.STRICT);
        AssertionError terminalCause = new AssertionError("fatal output failure");
        CharsetPolicy policy = CharsetPolicy.report(new TerminalAfterLineCharset(
                () -> queue.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, terminalCause)));
        queue.offer(new byte[] {1, 's'});
        ProtocolResponseReader reader = reader(queue, 8, 8, Duration.ofSeconds(2), policy);

        ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> reader.readLine(1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(1, failure.getSuppressed().length);
        ProtocolSessionException terminal =
                assertInstanceOf(ProtocolSessionException.class, failure.getSuppressed()[0]);
        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, terminal.reason());
        assertSame(terminalCause, terminal.getCause());
    }

    @Test
    void readLineKeepsLoneCarriageReturnInsideLineContent() {
        ProtocolResponseReader reader = readerFor("ab\rcd\n");

        assertEquals("ab\rcd", reader.readLine(5));
    }

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
    void readTextExactlyAcceptsExactByteAndCharacterLimits() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("é".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader reader =
                reader(queue, 2, 1, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        assertEquals("é", reader.readTextExactly(2, 1));
    }

    @Test
    void readTextExactlySaturatesFirstExcessArithmeticAtIntegerMaximum() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a'});
        ProtocolResponseReader reader = reader(queue, 1, Integer.MAX_VALUE, Duration.ofSeconds(2));

        assertEquals("a", reader.readTextExactly(1, Integer.MAX_VALUE));
    }

    @Test
    void readTextExactlyDoesNotEagerlyAllocateNearMaximumDeclaredField() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a'});
        queue.eof();
        ProtocolResponseReader reader = reader(queue, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(2));

        ProtocolSessionException exception = assertThrows(
                ProtocolSessionException.class, () -> reader.readTextExactly(Integer.MAX_VALUE, Integer.MAX_VALUE));

        assertEquals(ProtocolSessionException.Reason.EOF, exception.reason());
    }

    @Test
    void readTextExactlyAcceptsValidAsciiBeyondFormerStagedOutputLimit() {
        int fieldLength = FORMER_STAGED_OUTPUT_LIMIT + 1;
        byte[] ascii = new byte[fieldLength];
        Arrays.fill(ascii, (byte) 'a');
        ProtocolOutputQueue queue = new ProtocolOutputQueue(fieldLength, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(ascii);
        ProtocolResponseReader reader = reader(queue, fieldLength, fieldLength, Duration.ofSeconds(5));

        String decoded = reader.readTextExactly(fieldLength, fieldLength);

        assertEquals(fieldLength, decoded.length());
        assertEquals('a', decoded.charAt(0));
        assertEquals('a', decoded.charAt(fieldLength - 1));
    }

    @Test
    void readTextExactlyRejectsFirstCharacterBeyondConfiguredLimitWithoutHiddenDecoderFailure() {
        int fieldLength = FORMER_STAGED_OUTPUT_LIMIT + 1;
        byte[] ascii = new byte[fieldLength];
        Arrays.fill(ascii, (byte) 'a');
        ProtocolOutputQueue queue = new ProtocolOutputQueue(fieldLength, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(ascii);
        ProtocolResponseReader reader = reader(queue, fieldLength, fieldLength - 1, Duration.ofSeconds(5));

        ProtocolSessionException exception = assertThrows(
                ProtocolSessionException.class, () -> reader.readTextExactly(fieldLength, fieldLength - 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void readTextExactlyRejectsOneCharacterBeyondPerCallLimit() {
        ProtocolResponseReader reader = readerFor("ab");

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(2, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void readTextExactlyClassifiesAtomicSurrogatePairBeyondRemainingLimitAsTooLarge() {
        byte[] encoded = "a😀".getBytes(StandardCharsets.UTF_8);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(encoded.length, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(encoded);
        ProtocolResponseReader reader = reader(queue, encoded.length, 1, Duration.ofSeconds(2));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(encoded.length, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void readTextExactlyAccumulatesTheGlobalCharacterBudgetAcrossCalls() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("abcd".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader reader = reader(queue, 4, 3, Duration.ofSeconds(2));

        assertEquals("ab", reader.readTextExactly(2, 2));
        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(2, 2));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void failedExactFieldChargesItsDecodedPrefixAndFirstExcessCharacter() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(3, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a', 'b', 'X'});
        ProtocolResponseReader reader = reader(queue, 3, 1, Duration.ofSeconds(2));

        ProtocolSessionException first =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(2, 1));
        ProtocolSessionException retry =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, first.reason());
        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, retry.reason());
        assertEquals(1, queue.pendingBytes());
    }

    @Test
    void failedLocalExactFieldChargesDecodedCharactersExactlyOnce() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(5, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a', 'b', 'X', 'Y', 'Z'});
        ProtocolResponseReader reader = reader(queue, 5, 4, Duration.ofSeconds(2));

        ProtocolSessionException localFailure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(2, 1));
        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, localFailure.reason());

        assertEquals("X", reader.readTextExactly(1, 1));
        assertEquals("Y", reader.readTextExactly(1, 1));
        ProtocolSessionException globalFailure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, globalFailure.reason());
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void exactFieldStopsAfterTheFirstCharacterBeyondItsLimit() {
        int maxChars = 8192;
        byte[] ascii = new byte[maxChars * 2];
        Arrays.fill(ascii, (byte) 'a');
        ProtocolOutputQueue queue = new ProtocolOutputQueue(ascii.length, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(ascii);
        ProtocolResponseReader reader = reader(queue, ascii.length, maxChars, Duration.ofSeconds(2));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(ascii.length, maxChars));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertEquals(maxChars - 1, queue.pendingBytes());
    }

    @Test
    void continuousDecoderAcceptsFiniteOutputBeyondFormerInternalCeiling() {
        int characterBudget = 2_000_000;
        int outputChars = 1_048_577;
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        CharsetPolicy policy =
                CharsetPolicy.report(new IncrementalTextDecoderTest.FiniteLargeOutputCharset(outputChars));
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withCharsetPolicy(policy)
                .withMaxResponseBytes(1)
                .withMaxResponseChars(characterBudget)
                .withOutputBacklogLimit(1);
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(5)),
                new ProtocolResponseBudget(1, characterBudget, FAILURES),
                streamDecoder(options),
                FAILURES,
                readerScope());

        String decoded = reader.readTextUntil((byte) 1, characterBudget);

        assertEquals(outputChars, decoded.length());
    }

    @Test
    void continuousDecoderOutputGuardComesOnlyFromTheCharacterBudget() {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withMaxResponseBytes(1)
                .withOutputBacklogLimit(1)
                .withMaxResponseChars(2_000_000);
        ProtocolSessionSettings maximum = options.withMaxResponseChars(Integer.MAX_VALUE);

        assertEquals(2_000_001, ProtocolResponseReader.outputWithoutInputLimit(options));
        assertEquals(Integer.MAX_VALUE, ProtocolResponseReader.outputWithoutInputLimit(maximum));
    }

    @Test
    void readTextExactlySharesTheGlobalCharacterBudgetWithContinuousTextReads() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("h\nxy".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader reader = reader(queue, 4, 3, Duration.ofSeconds(2));

        assertEquals("h", reader.readLine(1));
        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(2, 2));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void readTextExactlyReportsMalformedInputUnderStrictPolicy() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xff});
        ProtocolResponseReader reader =
                reader(queue, 1, 1, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
        assertInstanceOf(MalformedInputException.class, exception.getCause());
    }

    @Test
    void readTextExactlyReportsUnmappableInputUnderStrictPolicy() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader =
                reader(queue, 1, 1, Duration.ofSeconds(2), CharsetPolicy.report(new UnmappableInputCharset()));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
        assertInstanceOf(UnmappableCharacterException.class, exception.getCause());
    }

    @Test
    void readTextExactlyReplacesMalformedInputUnderReplacingPolicy() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xff});
        ProtocolResponseReader reader =
                reader(queue, 1, 1, Duration.ofSeconds(2), CharsetPolicy.replace(StandardCharsets.UTF_8));

        assertEquals("\uFFFD", reader.readTextExactly(1, 1));
    }

    @Test
    void readTextExactlyAppliesCharacterLimitToEachReplacement() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xff, (byte) 0xff});
        ProtocolResponseReader reader =
                reader(queue, 2, 2, Duration.ofSeconds(2), CharsetPolicy.replace(StandardCharsets.UTF_8));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(2, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void readTextExactlyClassifiesMalformedCustomReplacementExpansionAsTooLarge() {
        assertReplacementExpansionIsTooLarge(new CharsetPolicy(
                new ReplacementErrorCharset(ReplacementError.MALFORMED),
                CodingErrorAction.REPLACE,
                CodingErrorAction.REPORT));
    }

    @Test
    void readTextExactlyClassifiesUnmappableCustomReplacementExpansionAsTooLarge() {
        assertReplacementExpansionIsTooLarge(new CharsetPolicy(
                new ReplacementErrorCharset(ReplacementError.UNMAPPABLE),
                CodingErrorAction.REPORT,
                CodingErrorAction.REPLACE));
    }

    @Test
    void readTextExactlyUsesMalformedReportIndependentlyFromUnmappableReplacement() {
        assertExactReplacementPolicyReports(new CharsetPolicy(
                new ReplacementErrorCharset(ReplacementError.MALFORMED),
                CodingErrorAction.REPORT,
                CodingErrorAction.REPLACE));
    }

    @Test
    void readTextExactlyUsesUnmappableReportIndependentlyFromMalformedReplacement() {
        assertExactReplacementPolicyReports(new CharsetPolicy(
                new ReplacementErrorCharset(ReplacementError.UNMAPPABLE),
                CodingErrorAction.REPLACE,
                CodingErrorAction.REPORT));
    }

    @Test
    void continuousTextClassifiesMalformedCustomReplacementExpansionAsTooLarge() {
        assertContinuousReplacementExpansionIsTooLarge(new CharsetPolicy(
                new ReplacementErrorCharset(ReplacementError.MALFORMED),
                CodingErrorAction.REPLACE,
                CodingErrorAction.REPORT));
    }

    @Test
    void continuousTextClassifiesUnmappableCustomReplacementExpansionAsTooLarge() {
        assertContinuousReplacementExpansionIsTooLarge(new CharsetPolicy(
                new ReplacementErrorCharset(ReplacementError.UNMAPPABLE),
                CodingErrorAction.REPORT,
                CodingErrorAction.REPLACE));
    }

    @Test
    void readTextExactlyPreservesTheConfiguredCustomReplacement() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        CharsetPolicy policy = new CharsetPolicy(
                new ReplacementErrorCharset(ReplacementError.MALFORMED),
                CodingErrorAction.REPLACE,
                CodingErrorAction.REPORT);
        ProtocolResponseReader reader = reader(queue, 1, 3, Duration.ofSeconds(2), policy);

        assertEquals("XYZ", reader.readTextExactly(1, 3));
    }

    @Test
    void readTextExactlyReportsIncompleteSequenceAtFieldBoundary() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xc3});
        ProtocolResponseReader reader =
                reader(queue, 1, 1, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
    }

    @Test
    void readTextExactlyReportsEofBeforeDeclaredByteLength() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a'});
        queue.eof();
        ProtocolResponseReader reader = reader(queue);

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(2, 2));

        assertEquals(ProtocolSessionException.Reason.EOF, exception.reason());
    }

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
    void readTextExactlyDoesNotLetQueuedBytesBypassExpiredDeadline() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a'});
        ProtocolResponseReader reader = reader(queue, 1, 1, Duration.ZERO);

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));

        assertEquals(ProtocolSessionException.Reason.TIMEOUT, exception.reason());
    }

    @Test
    void pendingTerminalPrecedesReadTextExactlyBudgetAndDeadlineChecks() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        queue.offer(new byte[] {1, 2});
        ProtocolResponseReader reader = reader(queue, 1, 1, Duration.ZERO);

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(Integer.MAX_VALUE, 1));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
    }

    @Test
    void readTextExactlyRejectsOversizedDeclaredLengthBeforeAllocationOrConsumption() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a'});
        ProtocolResponseReader reader = reader(queue, 1, 1, Duration.ofSeconds(2));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(Integer.MAX_VALUE, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertEquals((byte) 'a', reader.readByte());
    }

    @Test
    void readTextExactlyRejectsBodyDeclarationThatIgnoresAlreadyConsumedHeaderBytes() {
        byte[] output = "8\nabcdefgh".getBytes(StandardCharsets.UTF_8);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(output.length, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(output);
        ProtocolResponseReader reader = reader(queue, 8, 16, Duration.ofSeconds(2));
        assertEquals("8", reader.readLine(1));

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(8, 8));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertEquals('a', queue.readUnsignedByte(DurationSupport.deadlineFromNow(Duration.ofSeconds(2)), FAILURES));
    }

    @Test
    void readTextExactlyAcceptsEmptyFieldAndRejectsInvalidArgumentsBeforeReading() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a'});
        ProtocolResponseReader reader = reader(queue);

        assertEquals("", reader.readTextExactly(0, 1));
        assertThrows(IllegalArgumentException.class, () -> reader.readTextExactly(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> reader.readTextExactly(1, 0));
        assertEquals((byte) 'a', reader.readByte());
    }

    @Test
    void emptyExactTextFieldDoesNotCreateOrFlushAFieldDecoder() {
        FlushProducingCharset charset = new FlushProducingCharset();
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        ProtocolResponseReader reader = reader(queue, 1, 1, Duration.ofSeconds(2), CharsetPolicy.report(charset));
        int decoderCreationsBeforeRead = charset.decoderCreations();

        assertEquals("", reader.readTextExactly(0, 1));
        assertEquals(decoderCreationsBeforeRead, charset.decoderCreations());
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
    void readLineUsesDecodedLineFeedForMultibyteCharset() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(64, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("alpha\r\nbeta\n".getBytes(StandardCharsets.UTF_16LE));
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withCharsetPolicy(io.github.ulviar.procwright.command.CharsetPolicy.report(StandardCharsets.UTF_16LE));
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(64, 64, FAILURES),
                streamDecoder(options),
                FAILURES,
                readerScope());

        assertEquals("alpha", reader.readLine(16));
        assertEquals("beta", reader.readLine(16));
    }

    @Test
    void readLineSplitsAtomicDecoderOutputAndChargesItsSourceOnce() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1, 'z'});
        ProtocolResponseReader reader =
                reader(queue, 2, 4, Duration.ofSeconds(2), CharsetPolicy.report(AtomicLinesCharset.duringDecode("b")));

        assertEquals("a", reader.readLine(1));
        assertEquals(1, queue.pendingBytes());
        assertEquals("b", reader.readLine(1));
        assertEquals(1, queue.pendingBytes());
        assertEquals('z', reader.readByte());
    }

    @Test
    void readLineSplitsAtomicDecoderOutputProducedWhileFinishingAtEof() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        queue.eof();
        ProtocolResponseReader reader =
                reader(queue, 1, 4, Duration.ofSeconds(2), CharsetPolicy.report(AtomicLinesCharset.duringFlush("b")));

        assertEquals("a", reader.readLine(1));
        assertEquals("b", reader.readLine(1));
        ProtocolSessionException eof = assertThrows(ProtocolSessionException.class, () -> reader.readLine(1));
        assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
    }

    @Test
    void processExitStillFinishesAtomicDecoderSuffixBeforeTerminalFailure() {
        AtomicReference<OptionalInt> exitCode = new AtomicReference<>(OptionalInt.empty());
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                1,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                System::nanoTime,
                () -> {},
                () -> {},
                exitCode::get,
                null);
        queue.offer(new byte[] {1});
        queue.eof();
        ProtocolResponseReader reader =
                reader(queue, 1, 4, Duration.ofSeconds(2), CharsetPolicy.report(AtomicLinesCharset.duringFlush("b")));

        assertEquals("a", reader.readLine(1));
        exitCode.set(OptionalInt.of(17));
        assertEquals("b", reader.readLine(1));
        ProtocolSessionException exited = assertThrows(ProtocolSessionException.class, () -> reader.readLine(1));
        assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, exited.reason());
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
    void atomicDecodedSuffixIsChargedWhenEachLineIsConsumed() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader =
                reader(queue, 1, 3, Duration.ofSeconds(2), CharsetPolicy.report(AtomicLinesCharset.duringDecode("b")));

        assertEquals("a", reader.readLine(1));
        ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> reader.readLine(1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void retainedAtomicSuffixAppliesEachReadLineContentLimitWithoutReadingMoreBytes() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader =
                reader(queue, 1, 5, Duration.ofSeconds(2), CharsetPolicy.report(AtomicLinesCharset.duringDecode("bc")));

        assertEquals("a", reader.readLine(1));
        ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> reader.readLine(1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void textDecoderCarriesBomSelectedEndiannessAcrossLineReads() {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withCharsetPolicy(io.github.ulviar.procwright.command.CharsetPolicy.report(StandardCharsets.UTF_16));
        ProtocolOutputQueue queue = new ProtocolOutputQueue(64, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(utf16LeWithBom("alpha\nbeta\n"));
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(2)),
                new ProtocolResponseBudget(64, 64, FAILURES),
                streamDecoder(options),
                FAILURES,
                readerScope());

        assertEquals("alpha", reader.readLine(16));
        assertEquals("beta", reader.readLine(16));
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
    void largeTextFrameUsesBoundedQueueTransactionsInsteadOfOneReadPerByte() {
        int contentLength = 1024 * 1024;
        byte[] frame = new byte[contentLength + 1];
        Arrays.fill(frame, 0, contentLength, (byte) 'a');
        frame[contentLength] = '\n';
        AtomicInteger readTransactions = new AtomicInteger();
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                frame.length,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                System::nanoTime,
                () -> {},
                () -> {},
                readTransactions::incrementAndGet);
        queue.offer(frame);
        ProtocolResponseReader reader = reader(queue, frame.length, frame.length, Duration.ofSeconds(5));

        assertEquals(contentLength, reader.readLine(contentLength).length());
        assertTrue(readTransactions.get() <= 129, "one 8 KiB transaction per window is sufficient");
    }

    @Test
    void continuousDecoderReusesItsTransactionalCharacterStaging() {
        int contentLength = 64 * 1024;
        byte[] frame = new byte[contentLength + 1];
        Arrays.fill(frame, 0, contentLength, (byte) 'a');
        frame[contentLength] = '|';
        AtomicInteger stagingAllocations = new AtomicInteger();
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withMaxResponseBytes(frame.length)
                .withMaxResponseChars(frame.length)
                .withOutputBacklogLimit(frame.length);
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                options.charsetPolicy(),
                ProtocolResponseReader.pendingByteLimit(options),
                ProtocolResponseReader.outputWithoutInputLimit(options),
                length -> {
                    stagingAllocations.incrementAndGet();
                    return new char[length];
                });
        ProtocolOutputQueue queue = new ProtocolOutputQueue(frame.length, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(frame);
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(5)),
                new ProtocolResponseBudget(frame.length, frame.length, FAILURES),
                decoder,
                FAILURES,
                readerScope());

        assertEquals(
                contentLength + 1,
                reader.readTextUntil((byte) '|', frame.length).length());
        assertEquals(1, stagingAllocations.get(), "ASCII decoding must not allocate staging per input byte");
    }

    @Test
    void textWindowStopsAtSplitMultibyteDelimiterAndLeavesCoalescedSuffix() {
        byte[] first = new byte[] {'p', 'r', 'e', 'f', 'i', 'x', '-', (byte) 0xc3};
        byte[] second = new byte[] {
            (byte) 0xa9, '|', 's', 'u', 'f', 'f', 'i', 'x', '-', (byte) 0xce, (byte) 0xb2, '|', 't', 'a', 'i', 'l'
        };
        ProtocolOutputQueue queue = new ProtocolOutputQueue(64, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(first);
        queue.offer(second);
        ProtocolResponseReader reader =
                reader(queue, 64, 64, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        assertEquals("prefix-é|", reader.readTextUntil((byte) '|', 16));
        assertEquals("suffix-β|", reader.readTextUntil((byte) '|', 16));
        assertEquals('t', reader.readByte());
    }

    @Test
    void textWindowCarriesMultibyteInputAcrossItsInternalBoundary() {
        String prefix = "a".repeat(8191);
        byte[] frame = (prefix + "é|tail|").getBytes(StandardCharsets.UTF_8);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(frame.length, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(frame);
        ProtocolResponseReader reader = reader(
                queue, frame.length, frame.length, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        assertEquals(prefix + "é|", reader.readTextUntil((byte) '|', 8193));
        assertEquals("tail|", reader.readTextUntil((byte) '|', 5));
    }

    @Test
    void continuousTextPreservesStrictAndReplacingMalformedInputPolicies() {
        ProtocolOutputQueue strictQueue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        strictQueue.offer(new byte[] {(byte) 0xff, '|'});
        ProtocolResponseReader strict =
                reader(strictQueue, 2, 2, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> strict.readTextUntil((byte) '|', 2));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
        assertEquals(1, strictQueue.pendingBytes());

        ProtocolOutputQueue replacingQueue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        replacingQueue.offer(new byte[] {(byte) 0xff, '|'});
        ProtocolResponseReader replacing =
                reader(replacingQueue, 2, 2, Duration.ofSeconds(2), CharsetPolicy.replace(StandardCharsets.UTF_8));
        assertEquals("�|", replacing.readTextUntil((byte) '|', 2));
    }

    @Test
    void decoderErrorKeepsIdentityWhenItPublishesATerminalBeforeWindowCommit() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        AssertionError fatal = new AssertionError("fatal decoder failure");
        IllegalStateException terminalCause = new IllegalStateException("terminal output failure");
        CharsetPolicy policy = CharsetPolicy.report(new FatalTerminalRaceCharset(() -> {
            queue.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, terminalCause);
            throw fatal;
        }));
        queue.offer(new byte[] {1, '|'});
        ProtocolResponseReader reader = reader(queue, 2, 2, Duration.ofSeconds(2), policy);

        AssertionError observed = assertThrows(AssertionError.class, () -> reader.readTextUntil((byte) '|', 2));

        assertSame(fatal, observed);
        assertEquals(1, fatal.getSuppressed().length);
        ProtocolSessionException terminal = assertInstanceOf(ProtocolSessionException.class, fatal.getSuppressed()[0]);
        assertSame(terminalCause, terminal.getCause());
    }

    @Test
    void continuousTextInputWindowIsSharedByRequestScopedReadersForOneStream() {
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(CharsetPolicy.report(StandardCharsets.UTF_8));

        assertSame(decoder.inputWindow(), decoder.inputWindow());
    }

    @Test
    void textWindowKeepsGlobalCharacterBudgetAcrossSplitReads() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("ab|cd|".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader reader = reader(queue, 16, 5, Duration.ofSeconds(2));

        assertEquals("ab|", reader.readTextUntil((byte) '|', 3));
        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextUntil((byte) '|', 3));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(0, queue.pendingBytes());
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

    private static ProtocolResponseReader readerFor(String output) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(64 * 1024, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(output.getBytes(StandardCharsets.UTF_8));
        queue.eof();
        return reader(queue);
    }

    private static ProtocolSessionSettings atomicLineOptions(
            int maxResponseChars, int outputBacklogLimit, Charset charset) {
        return ProtocolSessionSettings.defaults()
                .withMaxResponseBytes(1)
                .withMaxResponseChars(maxResponseChars)
                .withOutputBacklogLimit(outputBacklogLimit)
                .withCharsetPolicy(CharsetPolicy.report(charset));
    }

    private static ProtocolTextDecoderState streamDecoder(ProtocolSessionSettings options) {
        return new ProtocolTextDecoderState(
                options.charsetPolicy(),
                ProtocolResponseReader.pendingByteLimit(options),
                ProtocolResponseReader.outputWithoutInputLimit(options),
                ProtocolResponseReader.decodedLineSuffixLimit(options));
    }

    private static ProtocolOutputQueue atomicLineOutput(int limit) {
        ProtocolOutputQueue output = new ProtocolOutputQueue(limit, ProtocolOutputQueue.OverflowPolicy.STRICT);
        output.offer(new byte[] {1});
        output.eof();
        return output;
    }

    private static ProtocolResponseReader requestReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            ProtocolTextDecoderState decoder,
            Duration timeout) {
        return requestReader(
                output,
                options,
                new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), FAILURES),
                decoder,
                readerScope(),
                timeout);
    }

    private static ProtocolResponseReader requestReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            ProtocolResponseBudget budget,
            ProtocolTextDecoderState decoder,
            RequestCapabilityScope scope,
            Duration timeout) {
        return new ProtocolResponseReader(
                output, options, DurationSupport.deadlineFromNow(timeout), budget, decoder, FAILURES, scope);
    }

    private static PendingInputFixture pendingInputFixture() {
        return pendingInputFixture(4);
    }

    private static PendingInputFixture pendingInputFixture(int maxBytes) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xc3, 'X', (byte) 0xa9, '\n'});
        ProtocolResponseReader reader =
                reader(queue, maxBytes, 4, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));
        assertEquals("", reader.readTextUntil((byte) 0xc3, 1));
        return new PendingInputFixture(queue, reader);
    }

    private static byte[] utf16LeWithBom(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[encoded.length + 2];
        withBom[0] = (byte) 0xff;
        withBom[1] = (byte) 0xfe;
        System.arraycopy(encoded, 0, withBom, 2, encoded.length);
        return withBom;
    }

    private static ProtocolResponseReader reader(ProtocolOutputQueue queue) {
        return reader(queue, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(2));
    }

    private static ProtocolResponseReader reader(
            ProtocolOutputQueue queue, int maxBytes, int maxChars, Duration timeout) {
        return reader(
                queue,
                maxBytes,
                maxChars,
                timeout,
                ProtocolSessionSettings.defaults().charsetPolicy());
    }

    private static ProtocolResponseReader reader(
            ProtocolOutputQueue queue, int maxBytes, int maxChars, Duration timeout, CharsetPolicy charsetPolicy) {
        long deadlineNanos = DurationSupport.deadlineFromNow(timeout);
        ProtocolResponseBudget budget = new ProtocolResponseBudget(maxBytes, maxChars, FAILURES);
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults().withCharsetPolicy(charsetPolicy);
        return new ProtocolResponseReader(
                queue, options, deadlineNanos, budget, streamDecoder(options), FAILURES, readerScope());
    }

    private static RequestCapabilityScope readerScope() {
        return RequestCapabilityScope.unrestricted("ProtocolReader unit test");
    }

    private static List<Consumer<ProtocolResponseReader>> zeroLengthReads() {
        return List.of(
                reader -> assertEquals(0, reader.read(new byte[] {9}, 1, 0)),
                reader -> assertArrayEquals(new byte[0], reader.readExactly(0)),
                reader -> assertEquals("", reader.readTextExactly(0, 1)));
    }

    private static void assertReplacementExpansionIsTooLarge(CharsetPolicy policy) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1, 'X'});
        ProtocolResponseReader reader = reader(queue, 2, 1, Duration.ofSeconds(2), policy);

        ProtocolSessionException first =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));
        ProtocolSessionException retry =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, first.reason());
        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, retry.reason());
        assertEquals(1, queue.pendingBytes());
    }

    private static void assertContinuousReplacementExpansionIsTooLarge(CharsetPolicy policy) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader = reader(queue, 1, 1, Duration.ofSeconds(2), policy);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextUntil((byte) 1, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
    }

    private static void assertExactReplacementPolicyReports(CharsetPolicy policy) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader = reader(queue, 1, 3, Duration.ofSeconds(2), policy);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 3));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
    }

    private static final class RuntimeFailureOnFlushCharset extends Charset {

        private final RuntimeException failure;

        private RuntimeFailureOnFlushCharset(RuntimeException failure) {
            super("X-Procwright-Runtime-Failure-On-Flush", new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    input.position(input.limit());
                    return CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    throw failure;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class AtomicLinesCharset extends Charset {

        private final boolean emitDuringFlush;
        private final String emittedText;

        private AtomicLinesCharset(boolean emitDuringFlush, String secondLine) {
            super("X-Procwright-Atomic-Lines-" + (emitDuringFlush ? "Flush-" : "Decode-") + secondLine, new String[0]);
            this.emitDuringFlush = emitDuringFlush;
            this.emittedText = "a\n" + secondLine + '\n';
        }

        private static AtomicLinesCharset duringDecode(String secondLine) {
            return new AtomicLinesCharset(false, secondLine);
        }

        private static AtomicLinesCharset duringFlush(String secondLine) {
            return new AtomicLinesCharset(true, secondLine);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, emittedText.length()) {
                private boolean emitted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    if (emitDuringFlush) {
                        input.position(input.limit());
                        return CoderResult.UNDERFLOW;
                    }
                    if (emitted) {
                        input.position(input.limit());
                        return CoderResult.UNDERFLOW;
                    }
                    if (output.remaining() < emittedText.length()) {
                        return CoderResult.OVERFLOW;
                    }
                    input.get();
                    output.put(emittedText);
                    emitted = true;
                    return CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    if (!emitDuringFlush || emitted) {
                        return CoderResult.UNDERFLOW;
                    }
                    if (output.remaining() < emittedText.length()) {
                        return CoderResult.OVERFLOW;
                    }
                    output.put(emittedText);
                    emitted = true;
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class AtomicLinesTerminalCharset extends Charset {

        private final Runnable terminalPublisher;

        private AtomicLinesTerminalCharset(Runnable terminalPublisher) {
            super("X-Procwright-Atomic-Lines-Terminal", new String[0]);
            this.terminalPublisher = terminalPublisher;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 4) {
                private boolean emitted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining() || emitted) {
                        return CoderResult.UNDERFLOW;
                    }
                    if (output.remaining() < 4) {
                        return CoderResult.OVERFLOW;
                    }
                    input.get();
                    output.put("a\nb\n");
                    emitted = true;
                    terminalPublisher.run();
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class SplitAtomicLineCharset extends Charset {

        private SplitAtomicLineCharset() {
            super("X-Procwright-Split-Atomic-Line", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                private int decodedBytes;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining()) {
                        int required = decodedBytes == 0 ? 3 : 1;
                        if (output.remaining() < required) {
                            return CoderResult.OVERFLOW;
                        }
                        input.get();
                        if (decodedBytes++ == 0) {
                            output.put("a\nb");
                        } else {
                            output.put('\n');
                        }
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class FatalTerminalRaceCharset extends Charset {

        private final Runnable failure;

        private FatalTerminalRaceCharset(Runnable failure) {
            super("X-Procwright-Fatal-Terminal-Race", new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    failure.run();
                    throw new AssertionError("failure hook returned");
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class TerminalAfterLineCharset extends Charset {

        private final Runnable terminalPublisher;

        private TerminalAfterLineCharset(Runnable terminalPublisher) {
            super("X-Procwright-Terminal-After-Line", new String[0]);
            this.terminalPublisher = terminalPublisher;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                private boolean emitted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining() || emitted) {
                        return CoderResult.UNDERFLOW;
                    }
                    input.get();
                    output.put('o').put('k').put('\n');
                    emitted = true;
                    terminalPublisher.run();
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class OverflowThenMalformedCharset extends Charset {

        private OverflowThenMalformedCharset() {
            super("X-Procwright-Protocol-Overflow-Then-Malformed", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                private boolean overflowed;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!overflowed) {
                        while (output.hasRemaining()) {
                            output.put('\n');
                        }
                        overflowed = true;
                        return CoderResult.OVERFLOW;
                    }
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class CodingErrorOnFlushCharset extends Charset {

        private CodingErrorOnFlushCharset() {
            super("X-Procwright-Coding-Error-On-Flush", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    input.position(input.limit());
                    return CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class CoderMalfunctionCharset extends Charset {

        private CoderMalfunctionCharset() {
            super("X-Procwright-Coder-Malfunction", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    throw new BufferUnderflowException();
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class UnmappableInputCharset extends Charset {

        private UnmappableInputCharset() {
            super("X-Procwright-Unmappable-Input", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    return input.hasRemaining() ? CoderResult.unmappableForLength(1) : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class FlushProducingCharset extends Charset {

        private final AtomicInteger decoderCreations = new AtomicInteger();

        private FlushProducingCharset() {
            super("X-Procwright-Flush-Producing", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            decoderCreations.incrementAndGet();
            return new CharsetDecoder(this, 1, 1) {
                private boolean flushed;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    input.position(input.limit());
                    return CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    if (!flushed && output.hasRemaining()) {
                        output.put('x');
                        flushed = true;
                    }
                    return flushed ? CoderResult.UNDERFLOW : CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int decoderCreations() {
            return decoderCreations.get();
        }
    }

    private enum ReplacementError {
        MALFORMED,
        UNMAPPABLE
    }

    private static final class ReplacementErrorCharset extends Charset {

        private final ReplacementError error;

        private ReplacementErrorCharset(ReplacementError error) {
            super("X-Procwright-Replacement-" + error, new String[0]);
            this.error = error;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    return error == ReplacementError.MALFORMED
                            ? CoderResult.malformedForLength(1)
                            : CoderResult.unmappableForLength(1);
                }
            }.replaceWith("XYZ");
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private record PendingInputFixture(ProtocolOutputQueue queue, ProtocolResponseReader reader) {}
}

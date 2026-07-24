/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ProtocolResponseReaderCharsetFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ProtocolResponseReaderExactTextTest extends ProtocolResponseReaderTestSupport {

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
}

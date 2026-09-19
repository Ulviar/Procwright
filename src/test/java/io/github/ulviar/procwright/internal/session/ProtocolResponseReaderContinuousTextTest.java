/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static io.github.ulviar.procwright.internal.session.ProtocolResponseReaderCharsetFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolResponseReaderContinuousTextTest extends ProtocolResponseReaderTestSupport {

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
                streamText(options),
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
        assertEquals(0, failure.getSuppressed().length);
        ProtocolSessionException terminal = assertThrows(ProtocolSessionException.class, () -> reader.readLine(1));
        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, terminal.reason());
        assertSame(terminalCause, terminal.getCause());
    }

    @Test
    void readLineKeepsLoneCarriageReturnInsideLineContent() {
        ProtocolResponseReader reader = readerFor("ab\rcd\n");

        assertEquals("ab\rcd", reader.readLine(5));
    }

    @Test
    void continuousDecoderAcceptsFiniteOutputBeyondFormerInternalCeiling() {
        int characterBudget = 2_000_000;
        int outputChars = 1_048_577;
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        CharsetPolicy policy =
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.FiniteLargeOutputCharset(outputChars));
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults()
                .withCharsetPolicy(policy)
                .withMaxResponseBytes(1)
                .withMaxResponseChars(characterBudget);
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(5)),
                new ProtocolResponseBudget(1, characterBudget, FAILURES),
                streamText(options),
                FAILURES,
                readerScope());

        String decoded = reader.readTextUntil((byte) 1, characterBudget);

        assertEquals(outputChars, decoded.length());
    }

    @Test
    void continuousDecoderOutputGuardComesOnlyFromTheCharacterBudget() {
        ProtocolSessionSettings options =
                ProtocolSessionSettings.defaults().withMaxResponseBytes(1).withMaxResponseChars(2_000_000);
        ProtocolSessionSettings maximum = options.withMaxResponseChars(Integer.MAX_VALUE);

        assertEquals(2_000_001, ProtocolTextReader.outputWithoutInputLimit(options));
        assertEquals(Integer.MAX_VALUE, ProtocolTextReader.outputWithoutInputLimit(maximum));
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
                streamText(options),
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
                streamText(options),
                FAILURES,
                readerScope());

        assertEquals("alpha", reader.readLine(16));
        assertEquals("beta", reader.readLine(16));
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
                .withMaxResponseChars(frame.length);
        ProtocolTextDecoderState decoder = new ProtocolTextDecoderState(
                options.charsetPolicy(),
                ProtocolTextReader.pendingByteLimit(options),
                ProtocolTextReader.outputWithoutInputLimit(options),
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
                streamText(options, decoder),
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
        assertEquals(0, fatal.getSuppressed().length);
        ProtocolSessionException terminal =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextUntil((byte) '|', 2));
        assertSame(terminalCause, terminal.getCause());
    }

    @Test
    void decoderFailureDoesNotWaitForTheTerminalFailureMonitorDuringWindowCommit() throws Exception {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(2, ProtocolOutputQueue.OverflowPolicy.STRICT);
        AssertionError decoderFailure = new AssertionError("fatal decoder failure");
        IllegalStateException terminalCause = new IllegalStateException("terminal output failure");
        CharsetPolicy policy = CharsetPolicy.report(new FatalTerminalRaceCharset(() -> {
            queue.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, terminalCause);
            throw decoderFailure;
        }));
        queue.offer(new byte[] {1, '|'});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var monitor = hold(decoderFailure)) {
            monitor.verifyHeld();
            Future<Throwable> outcome = executor.submit(() -> {
                ProtocolResponseReader reader = reader(queue, 2, 2, Duration.ofSeconds(2), policy);
                AssertionError observed = assertThrows(AssertionError.class, () -> reader.readTextUntil((byte) '|', 2));
                ProtocolSessionException terminal =
                        assertThrows(ProtocolSessionException.class, () -> reader.readTextUntil((byte) '|', 2));
                assertSame(terminalCause, terminal.getCause());
                return observed;
            });

            assertSame(decoderFailure, outcome.get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, decoderFailure.getSuppressed().length);
    }

    @Test
    void continuousTextInputWindowIsLazyForRawOnlyAdapters() {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        ProtocolTextReader.StreamState textStream = streamText(options);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("xy|".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader reader = new ProtocolResponseReader(
                queue,
                options,
                DurationSupport.deadlineFromNow(Duration.ofSeconds(1)),
                new ProtocolResponseBudget(16, 16, FAILURES),
                textStream,
                FAILURES,
                readerScope());

        assertFalse(textStream.inputWindowAllocated());
        assertEquals('x', reader.readByte());
        assertFalse(textStream.inputWindowAllocated());
        assertEquals("y|", reader.readTextUntil((byte) '|', 2));
        assertTrue(textStream.inputWindowAllocated());
    }

    @Test
    void globalCharacterBudgetSpansMultipleTextReads() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer("ab|cd|".getBytes(StandardCharsets.UTF_8));
        ProtocolResponseReader reader = reader(queue, 16, 5, Duration.ofSeconds(2));

        assertEquals("ab|", reader.readTextUntil((byte) '|', 3));
        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextUntil((byte) '|', 3));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(0, queue.pendingBytes());
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

abstract class ProtocolResponseReaderTestSupport {

    static final int FORMER_STAGED_OUTPUT_LIMIT = 2 * 1024 * 1024;

    static final ProtocolRuntimeFailures FAILURES = new ProtocolRuntimeFailures() {
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

    static ProtocolResponseReader readerFor(String output) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(64 * 1024, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(output.getBytes(StandardCharsets.UTF_8));
        queue.eof();
        return reader(queue);
    }

    static ProtocolSessionSettings atomicLineOptions(int maxResponseChars, Charset charset) {
        return ProtocolSessionSettings.defaults()
                .withMaxResponseBytes(1)
                .withMaxResponseChars(maxResponseChars)
                .withCharsetPolicy(CharsetPolicy.report(charset));
    }

    static ProtocolTextDecoderState streamDecoder(ProtocolSessionSettings options) {
        return new ProtocolTextDecoderState(
                options.charsetPolicy(),
                ProtocolTextReader.pendingByteLimit(options),
                ProtocolTextReader.outputWithoutInputLimit(options));
    }

    static ProtocolTextReader.StreamState streamText(ProtocolSessionSettings options) {
        return streamText(options, streamDecoder(options));
    }

    static ProtocolTextReader.StreamState streamText(
            ProtocolSessionSettings options, ProtocolTextDecoderState decoder) {
        return new ProtocolTextReader.StreamState(options, decoder);
    }

    static ProtocolOutputQueue atomicLineOutput(int limit) {
        ProtocolOutputQueue output = new ProtocolOutputQueue(limit, ProtocolOutputQueue.OverflowPolicy.STRICT);
        output.offer(new byte[] {1});
        output.eof();
        return output;
    }

    static ProtocolResponseReader requestReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            ProtocolTextDecoderState decoder,
            Duration timeout) {
        return requestReader(
                output,
                options,
                new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), FAILURES),
                streamText(options, decoder),
                readerScope(),
                timeout);
    }

    static ProtocolResponseReader requestReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            ProtocolTextReader.StreamState textStream,
            Duration timeout) {
        return requestReader(
                output,
                options,
                new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), FAILURES),
                textStream,
                readerScope(),
                timeout);
    }

    static ProtocolResponseReader requestReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            ProtocolResponseBudget budget,
            ProtocolTextDecoderState decoder,
            RequestCapabilityScope scope,
            Duration timeout) {
        return requestReader(output, options, budget, streamText(options, decoder), scope, timeout);
    }

    static ProtocolResponseReader requestReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            ProtocolResponseBudget budget,
            ProtocolTextReader.StreamState textStream,
            RequestCapabilityScope scope,
            Duration timeout) {
        return new ProtocolResponseReader(
                output, options, DurationSupport.deadlineFromNow(timeout), budget, textStream, FAILURES, scope);
    }

    static PendingInputFixture pendingInputFixture() {
        return pendingInputFixture(4);
    }

    static PendingInputFixture pendingInputFixture(int maxBytes) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {(byte) 0xc3, 'X', (byte) 0xa9, '\n'});
        ProtocolResponseReader reader =
                reader(queue, maxBytes, 4, Duration.ofSeconds(2), CharsetPolicy.report(StandardCharsets.UTF_8));
        assertEquals("", reader.readTextUntil((byte) 0xc3, 1));
        return new PendingInputFixture(queue, reader);
    }

    static byte[] utf16LeWithBom(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[encoded.length + 2];
        withBom[0] = (byte) 0xff;
        withBom[1] = (byte) 0xfe;
        System.arraycopy(encoded, 0, withBom, 2, encoded.length);
        return withBom;
    }

    static ProtocolResponseReader reader(ProtocolOutputQueue queue) {
        return reader(queue, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(2));
    }

    static ProtocolResponseReader reader(ProtocolOutputQueue queue, int maxBytes, int maxChars, Duration timeout) {
        return reader(
                queue,
                maxBytes,
                maxChars,
                timeout,
                ProtocolSessionSettings.defaults().charsetPolicy());
    }

    static ProtocolResponseReader reader(
            ProtocolOutputQueue queue, int maxBytes, int maxChars, Duration timeout, CharsetPolicy charsetPolicy) {
        long deadlineNanos = DurationSupport.deadlineFromNow(timeout);
        ProtocolResponseBudget budget = new ProtocolResponseBudget(maxBytes, maxChars, FAILURES);
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults().withCharsetPolicy(charsetPolicy);
        return new ProtocolResponseReader(
                queue, options, deadlineNanos, budget, streamText(options), FAILURES, readerScope());
    }

    static RequestCapabilityScope readerScope() {
        RequestCapabilityScope scope = new RequestCapabilityScope("ProtocolReader unit test");
        scope.activate();
        return scope;
    }

    static List<Consumer<ProtocolResponseReader>> zeroLengthReads() {
        return List.of(
                reader -> assertEquals(0, reader.read(new byte[] {9}, 1, 0)),
                reader -> assertArrayEquals(new byte[0], reader.readExactly(0)),
                reader -> assertEquals("", reader.readTextExactly(0, 1)));
    }

    static void assertReplacementExpansionIsTooLarge(CharsetPolicy policy) {
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

    static void assertContinuousReplacementExpansionIsTooLarge(CharsetPolicy policy) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader = reader(queue, 1, 1, Duration.ofSeconds(2), policy);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextUntil((byte) 1, 1));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
    }

    static void assertExactReplacementPolicyReports(CharsetPolicy policy) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {1});
        ProtocolResponseReader reader = reader(queue, 1, 3, Duration.ofSeconds(2), policy);

        ProtocolSessionException failure =
                assertThrows(ProtocolSessionException.class, () -> reader.readTextExactly(1, 3));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
    }

    record PendingInputFixture(ProtocolOutputQueue queue, ProtocolResponseReader reader) {}
}

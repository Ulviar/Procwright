/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class IncrementalTextDecoderFailureAtomicityTest {

    @Test
    void replaceRejectsErrorLengthBeyondRemainingInputBeforePublishingReplacement() {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.replace(new IncrementalTextDecoderTestCharsets.FiniteErrorAfterExhaustionCharset()),
                64,
                1024);
        AtomicInteger emitted = new AtomicInteger();

        IncrementalTextDecoder.DecoderStateException exception = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> assertThrows(
                        IncrementalTextDecoder.DecoderStateException.class,
                        () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> emitted.addAndGet(count))));

        assertEquals("Decoder reported error length 1 with only 0 input bytes remaining", exception.getMessage());
        assertEquals(0, emitted.get());
    }

    @Test
    void replaceAcceptsErrorLengthEqualToRemainingInput() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.replace(new IncrementalTextDecoderTestCharsets.ExactRemainingMalformedCharset()),
                64,
                1024);
        StringBuilder decoded = new StringBuilder();

        decoder.decode(new byte[] {1, 2}, 2, (chars, count) -> decoded.append(chars, 0, count));

        assertEquals("\uFFFD", decoded.toString());
        assertEquals(true, decoder.malformed());
    }

    @Test
    void decodeDoesNotPublishPrefixProducedWithMalformedResult() {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OutputThenMalformedCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();

        assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> decoded.append(chars, 0, count)));

        assertEquals("", decoded.toString());
    }

    @Test
    void decodeDoesNotPublishOverflowOutputBeforeLaterMalformedResult() {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OverflowThenMalformedCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();

        assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1, 2}, 2, (chars, count) -> decoded.append(chars, 0, count)));

        assertEquals("", decoded.toString());
    }

    @Test
    void decodeDoesNotPublishOverflowLineBeforeLaterInputRewind() {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OverflowThenRewindCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();

        assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1, 2}, 2, (chars, count) -> decoded.append(chars, 0, count)));

        assertEquals("", decoded.toString());
    }

    @Test
    void flushDoesNotPublishOutputProducedWithMalformedResult() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OutputThenMalformedFlushCharset()),
                64,
                1024);
        StringBuilder decoded = new StringBuilder();
        IncrementalTextDecoder.Sink sink = (chars, count) -> decoded.append(chars, 0, count);
        decoder.decode(new byte[] {1}, 1, sink);

        assertThrows(CharacterCodingException.class, () -> decoder.end(sink));

        assertEquals("", decoded.toString());
    }

    @Test
    void endDoesNotPublishFlushOverflowBeforeLaterMalformedResult() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OverflowThenMalformedFlushCharset()),
                64,
                1024);
        StringBuilder decoded = new StringBuilder();
        IncrementalTextDecoder.Sink sink = (chars, count) -> decoded.append(chars, 0, count);
        decoder.decode(new byte[] {1}, 1, sink);

        assertThrows(CharacterCodingException.class, () -> decoder.end(sink));

        assertEquals("", decoded.toString());
    }
}

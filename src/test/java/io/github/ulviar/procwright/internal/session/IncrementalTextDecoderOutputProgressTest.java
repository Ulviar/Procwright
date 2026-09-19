/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class IncrementalTextDecoderOutputProgressTest {

    @Test
    void derivedOutputOnlyLimitPreservesTheConfiguredCharacterBudget() {
        assertEquals(17, IncrementalTextDecoder.outputWithoutInputLimitFor(17));
        assertEquals(Integer.MAX_VALUE, IncrementalTextDecoder.outputWithoutInputLimitFor(Integer.MAX_VALUE));
    }

    @Test
    void stagedOutputLimitSaturatesWithoutAllocatingNearIntegerMaximum() {
        int limit = IncrementalTextDecoder.stagingCharacterLimit(
                StandardCharsets.UTF_8.newDecoder(), Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, limit);
    }

    @Test
    void finiteDecoderOutputBeyondFormerInternalCeilingIsNotRejected() throws Exception {
        int characterBudget = 2_000_000;
        int outputChars = 1_048_577;
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.FiniteLargeOutputCharset(outputChars)),
                IncrementalTextDecoder.pendingByteLimitFor(characterBudget),
                IncrementalTextDecoder.outputWithoutInputLimitFor(characterBudget));
        AtomicInteger emitted = new AtomicInteger();

        decoder.decode(new byte[] {1}, 1, (chars, count) -> emitted.addAndGet(count));

        assertEquals(outputChars, emitted.get());
    }

    @Test
    void decoderThatNeverConsumesInputCannotRetainBeyondHardLimit() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.NoProgressCharset()), 4);
        byte[] bytes = new byte[] {1, 2, 3, 4};

        decoder.decode(bytes, bytes.length, (chars, count) -> {});

        CharacterCodingException exception = assertThrows(
                CharacterCodingException.class, () -> decoder.decode(new byte[] {5}, 1, (chars, count) -> {}));
        assertEquals("Decoder retained more than 4 undecoded bytes", exception.getMessage());
    }

    @Test
    void decoderRetainsAValidUtf8SequenceSplitAtTheHardLimit() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(CharsetPolicy.report(StandardCharsets.UTF_8), 2);
        StringBuilder decoded = new StringBuilder();
        IncrementalTextDecoder.Sink sink = (chars, count) -> decoded.append(chars, 0, count);
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);

        decoder.decode(euro, 2, sink);
        decoder.decode(new byte[] {euro[2]}, 1, sink);

        assertEquals("€", decoded.toString());
    }

    @Test
    void decoderCannotSpinOnOverflowWithoutProgress() {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OverflowWithoutProgressCharset()), 64);

        CharacterCodingException exception = assertThrows(
                CharacterCodingException.class, () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> {}));

        assertEquals("Decoder reported overflow without consuming input or producing output", exception.getMessage());
    }

    @Test
    void outputOnlyOverflowCannotEmitBeyondHardLimit() {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OutputOnlyOverflowCharset()), 64, 256);
        AtomicInteger emitted = new AtomicInteger();

        CharacterCodingException exception = assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> emitted.addAndGet(count)));

        assertEquals("Decoder produced more than 256 chars without consuming input", exception.getMessage());
        assertEquals(0, emitted.get());
    }

    @Test
    void outputOnlyFlushCannotEmitBeyondHardLimit() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OutputOnlyFlushCharset()), 64, 256);
        AtomicInteger emitted = new AtomicInteger();
        IncrementalTextDecoder.Sink sink = (chars, count) -> emitted.addAndGet(count);
        decoder.decode(new byte[] {1}, 1, sink);

        CharacterCodingException exception = assertThrows(CharacterCodingException.class, () -> decoder.end(sink));

        assertEquals("Decoder produced more than 256 chars without consuming input", exception.getMessage());
        assertEquals(0, emitted.get());
    }

    @Test
    void outputOnlyAllowanceResetsWhenDecoderConsumesInput() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.OneOutputBufferBeforeConsumptionCharset()),
                64,
                128);
        StringBuilder decoded = new StringBuilder();

        decoder.decode(new byte[] {1}, 1, (chars, count) -> decoded.append(chars, 0, count));

        assertEquals(129, decoded.length());
        assertEquals('y', decoded.charAt(128));
    }

    @Test
    void decoderCannotRewindInputToResetOutputOnlyAllowance() {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new IncrementalTextDecoderTestCharsets.FiniteRewindingCharset()), 64, 1024);
        AtomicInteger emitted = new AtomicInteger();

        IncrementalTextDecoder.DecoderStateException exception = assertThrows(
                IncrementalTextDecoder.DecoderStateException.class,
                () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> emitted.addAndGet(count)));

        assertEquals("Decoder moved input position backwards from 1 to 0", exception.getMessage());
        assertEquals(0, emitted.get());
    }
}

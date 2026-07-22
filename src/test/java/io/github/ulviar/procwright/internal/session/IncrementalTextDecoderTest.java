/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class IncrementalTextDecoderTest {

    @Test
    void derivedOutputOnlyLimitPreservesTheConfiguredCharacterBudget() {
        assertEquals(17, IncrementalTextDecoder.outputWithoutInputLimitFor(17));
        assertEquals(Integer.MAX_VALUE, IncrementalTextDecoder.outputWithoutInputLimitFor(Integer.MAX_VALUE));
    }

    @Test
    void stagedOutputLimitSaturatesWithoutAllocatingNearIntegerMaximum() {
        IncrementalTextDecoder.StagedOutput staged = IncrementalTextDecoder.stagedOutputFor(
                StandardCharsets.UTF_8.newDecoder(), Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, staged.remainingCapacity());
    }

    @Test
    void finiteDecoderOutputBeyondFormerInternalCeilingIsNotRejected() throws Exception {
        int characterBudget = 2_000_000;
        int outputChars = 1_048_577;
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                CharsetPolicy.report(new FiniteLargeOutputCharset(outputChars)),
                IncrementalTextDecoder.pendingByteLimitFor(characterBudget),
                IncrementalTextDecoder.outputWithoutInputLimitFor(characterBudget));
        AtomicInteger emitted = new AtomicInteger();

        decoder.decode(new byte[] {1}, 1, (chars, count) -> emitted.addAndGet(count));

        assertEquals(outputChars, emitted.get());
    }

    @Test
    void decoderThatNeverConsumesInputCannotRetainBeyondHardLimit() throws Exception {
        IncrementalTextDecoder decoder = new IncrementalTextDecoder(CharsetPolicy.report(new NoProgressCharset()), 4);
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
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OverflowWithoutProgressCharset()), 64);

        CharacterCodingException exception = assertThrows(
                CharacterCodingException.class, () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> {}));

        assertEquals("Decoder reported overflow without consuming input or producing output", exception.getMessage());
    }

    @Test
    void outputOnlyOverflowCannotEmitBeyondHardLimit() {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OutputOnlyOverflowCharset()), 64, 256);
        AtomicInteger emitted = new AtomicInteger();

        CharacterCodingException exception = assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> emitted.addAndGet(count)));

        assertEquals("Decoder produced more than 256 chars without consuming input", exception.getMessage());
        assertEquals(0, emitted.get());
    }

    @Test
    void outputOnlyFlushCannotEmitBeyondHardLimit() throws Exception {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OutputOnlyFlushCharset()), 64, 256);
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
                CharsetPolicy.report(new OneOutputBufferBeforeConsumptionCharset()), 64, 128);
        StringBuilder decoded = new StringBuilder();

        decoder.decode(new byte[] {1}, 1, (chars, count) -> decoded.append(chars, 0, count));

        assertEquals(129, decoded.length());
        assertEquals('y', decoded.charAt(128));
    }

    @Test
    void decoderCannotRewindInputToResetOutputOnlyAllowance() {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new FiniteRewindingCharset()), 64, 1024);
        AtomicInteger emitted = new AtomicInteger();

        IncrementalTextDecoder.DecoderStateException exception = assertThrows(
                IncrementalTextDecoder.DecoderStateException.class,
                () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> emitted.addAndGet(count)));

        assertEquals("Decoder moved input position backwards from 1 to 0", exception.getMessage());
        assertEquals(0, emitted.get());
    }

    @Test
    void replaceRejectsErrorLengthBeyondRemainingInputBeforePublishingReplacement() {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.replace(new FiniteErrorAfterExhaustionCharset()), 64, 1024);
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
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.replace(new ExactRemainingMalformedCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();

        decoder.decode(new byte[] {1, 2}, 2, (chars, count) -> decoded.append(chars, 0, count));

        assertEquals("\uFFFD", decoded.toString());
        assertEquals(true, decoder.malformed());
    }

    @Test
    void decodeDoesNotPublishPrefixProducedWithMalformedResult() {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OutputThenMalformedCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();

        assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1}, 1, (chars, count) -> decoded.append(chars, 0, count)));

        assertEquals("", decoded.toString());
    }

    @Test
    void decodeDoesNotPublishOverflowOutputBeforeLaterMalformedResult() {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OverflowThenMalformedCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();

        assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1, 2}, 2, (chars, count) -> decoded.append(chars, 0, count)));

        assertEquals("", decoded.toString());
    }

    @Test
    void decodeDoesNotPublishOverflowLineBeforeLaterInputRewind() {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OverflowThenRewindCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();

        assertThrows(
                CharacterCodingException.class,
                () -> decoder.decode(new byte[] {1, 2}, 2, (chars, count) -> decoded.append(chars, 0, count)));

        assertEquals("", decoded.toString());
    }

    @Test
    void flushDoesNotPublishOutputProducedWithMalformedResult() throws Exception {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OutputThenMalformedFlushCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();
        IncrementalTextDecoder.Sink sink = (chars, count) -> decoded.append(chars, 0, count);
        decoder.decode(new byte[] {1}, 1, sink);

        assertThrows(CharacterCodingException.class, () -> decoder.end(sink));

        assertEquals("", decoded.toString());
    }

    @Test
    void endDoesNotPublishFlushOverflowBeforeLaterMalformedResult() throws Exception {
        IncrementalTextDecoder decoder =
                new IncrementalTextDecoder(CharsetPolicy.report(new OverflowThenMalformedFlushCharset()), 64, 1024);
        StringBuilder decoded = new StringBuilder();
        IncrementalTextDecoder.Sink sink = (chars, count) -> decoded.append(chars, 0, count);
        decoder.decode(new byte[] {1}, 1, sink);

        assertThrows(CharacterCodingException.class, () -> decoder.end(sink));

        assertEquals("", decoded.toString());
    }

    static final class NoProgressCharset extends Charset {

        NoProgressCharset() {
            super("X-Procwright-No-Progress", new String[0]);
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
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class OverflowWithoutProgressCharset extends Charset {

        private OverflowWithoutProgressCharset() {
            super("X-Procwright-Overflow-Without-Progress", new String[0]);
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
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class OutputOnlyOverflowCharset extends Charset {

        OutputOnlyOverflowCharset() {
            super("X-Procwright-Output-Only-Overflow", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (output.hasRemaining()) {
                        output.put('x');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class OutputOnlyFlushCharset extends Charset {

        OutputOnlyFlushCharset() {
            super("X-Procwright-Output-Only-Flush", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    input.position(input.limit());
                    return CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    while (output.hasRemaining()) {
                        output.put('f');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class FiniteRewindingCharset extends Charset {

        FiniteRewindingCharset() {
            super("X-Procwright-Finite-Rewinding", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                private int calls;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    calls++;
                    if (calls > 4) {
                        return CoderResult.malformedForLength(1);
                    }
                    input.position((calls & 1) == 1 ? 1 : 0);
                    while (output.hasRemaining()) {
                        output.put('r');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class FiniteErrorAfterExhaustionCharset extends Charset {

        FiniteErrorAfterExhaustionCharset() {
            super("X-Procwright-Finite-Error-After-Exhaustion", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                private boolean consumed;
                private int exhaustedErrors;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!consumed) {
                        input.position(input.limit());
                        consumed = true;
                        return CoderResult.OVERFLOW;
                    }
                    if (exhaustedErrors++ < 4) {
                        return CoderResult.malformedForLength(1);
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

    static final class OutputThenMalformedCharset extends Charset {

        OutputThenMalformedCharset() {
            super("X-Procwright-Output-Then-Malformed", new String[0]);
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
                    output.put("ok\n");
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class OverflowThenMalformedCharset extends Charset {

        OverflowThenMalformedCharset() {
            super("X-Procwright-Overflow-Then-Malformed", new String[0]);
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
                        input.get();
                        fillWithLines(output);
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

    static final class OverflowThenRewindCharset extends Charset {

        OverflowThenRewindCharset() {
            super("X-Procwright-Overflow-Then-Rewind", new String[0]);
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
                        input.get();
                        fillWithLines(output);
                        overflowed = true;
                        return CoderResult.OVERFLOW;
                    }
                    input.position(0);
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class OutputThenMalformedFlushCharset extends Charset {

        OutputThenMalformedFlushCharset() {
            super("X-Procwright-Output-Then-Malformed-Flush", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 4) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    input.position(input.limit());
                    return CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    output.put("tail");
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class OverflowThenMalformedFlushCharset extends Charset {

        OverflowThenMalformedFlushCharset() {
            super("X-Procwright-Overflow-Then-Malformed-Flush", new String[0]);
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
                    input.position(input.limit());
                    return CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    if (!overflowed) {
                        fillWithLines(output);
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

    private static final class ExactRemainingMalformedCharset extends Charset {

        private ExactRemainingMalformedCharset() {
            super("X-Procwright-Exact-Remaining-Malformed", new String[0]);
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
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    return CoderResult.malformedForLength(input.remaining());
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class OneOutputBufferBeforeConsumptionCharset extends Charset {

        OneOutputBufferBeforeConsumptionCharset() {
            super("X-Procwright-One-Output-Buffer-Before-Consumption", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 129) {
                private boolean emittedWithoutConsumption;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!emittedWithoutConsumption) {
                        while (output.hasRemaining()) {
                            output.put('x');
                        }
                        emittedWithoutConsumption = true;
                        return CoderResult.OVERFLOW;
                    }
                    input.get();
                    output.put('y');
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class FiniteLargeOutputCharset extends Charset {

        private final int outputChars;

        FiniteLargeOutputCharset(int outputChars) {
            super("X-Procwright-Finite-Large-Output-" + outputChars, new String[0]);
            this.outputChars = outputChars;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                private int emitted;
                private boolean outputCompleted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (outputCompleted) {
                        input.get();
                        return CoderResult.UNDERFLOW;
                    }
                    while (output.hasRemaining() && emitted < outputChars) {
                        output.put('x');
                        emitted++;
                    }
                    if (emitted == outputChars) {
                        outputCompleted = true;
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static void fillWithLines(CharBuffer output) {
        char[] line = {'o', 'k', '\n'};
        int index = 0;
        while (output.hasRemaining()) {
            output.put(line[index++ % line.length]);
        }
    }
}

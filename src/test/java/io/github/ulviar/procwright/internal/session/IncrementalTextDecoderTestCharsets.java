/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

final class IncrementalTextDecoderTestCharsets {

    private IncrementalTextDecoderTestCharsets() {}

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

    static final class OverflowWithoutProgressCharset extends Charset {

        OverflowWithoutProgressCharset() {
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

    static final class ExactRemainingMalformedCharset extends Charset {

        ExactRemainingMalformedCharset() {
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

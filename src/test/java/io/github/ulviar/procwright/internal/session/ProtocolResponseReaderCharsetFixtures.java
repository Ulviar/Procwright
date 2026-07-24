/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

final class ProtocolResponseReaderCharsetFixtures {

    private ProtocolResponseReaderCharsetFixtures() {}

    static final class RuntimeFailureOnFlushCharset extends Charset {

        private final RuntimeException failure;

        RuntimeFailureOnFlushCharset(RuntimeException failure) {
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

    static final class AtomicLinesCharset extends Charset {

        private final boolean emitDuringFlush;
        private final String emittedText;

        AtomicLinesCharset(boolean emitDuringFlush, String secondLine) {
            super("X-Procwright-Atomic-Lines-" + (emitDuringFlush ? "Flush-" : "Decode-") + secondLine, new String[0]);
            this.emitDuringFlush = emitDuringFlush;
            this.emittedText = "a\n" + secondLine + '\n';
        }

        static AtomicLinesCharset duringDecode(String secondLine) {
            return new AtomicLinesCharset(false, secondLine);
        }

        static AtomicLinesCharset duringFlush(String secondLine) {
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

    static final class AtomicLinesTerminalCharset extends Charset {

        private final Runnable terminalPublisher;

        AtomicLinesTerminalCharset(Runnable terminalPublisher) {
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

    static final class SplitAtomicLineCharset extends Charset {

        SplitAtomicLineCharset() {
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

    static final class FatalTerminalRaceCharset extends Charset {

        private final Runnable failure;

        FatalTerminalRaceCharset(Runnable failure) {
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

    static final class TerminalAfterLineCharset extends Charset {

        private final Runnable terminalPublisher;

        TerminalAfterLineCharset(Runnable terminalPublisher) {
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

    static final class OverflowThenMalformedCharset extends Charset {

        OverflowThenMalformedCharset() {
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

    static final class CodingErrorOnFlushCharset extends Charset {

        CodingErrorOnFlushCharset() {
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

    static final class CoderMalfunctionCharset extends Charset {

        CoderMalfunctionCharset() {
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

    static final class UnmappableInputCharset extends Charset {

        UnmappableInputCharset() {
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

    static final class FlushProducingCharset extends Charset {

        private final AtomicInteger decoderCreations = new AtomicInteger();

        FlushProducingCharset() {
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

        int decoderCreations() {
            return decoderCreations.get();
        }
    }

    enum ReplacementError {
        MALFORMED,
        UNMAPPABLE
    }

    static final class ReplacementErrorCharset extends Charset {

        private final ReplacementError error;

        ReplacementErrorCharset(ReplacementError error) {
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
}

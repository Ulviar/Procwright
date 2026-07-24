/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.parseLength;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

final class ProtocolFailureIntegrationSupport {

    private ProtocolFailureIntegrationSupport() {}

    static final class FramedBytesAsLineAdapter implements ProtocolAdapter<byte[], String> {

        @Override
        public void writeRequest(byte[] request, ProtocolWriter writer) {
            writer.writeLine(Integer.toString(request.length));
            writer.write(Arrays.copyOf(request, request.length));
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            parseLength(stdout.readLine(32));
            String body = stdout.readTextUntil((byte) '\n', 32);
            assertEquals("END", stdout.readLine(8));
            return body;
        }
    }

    static final class FailingDecoderAdapter implements ProtocolAdapter<String, String> {

        private final IllegalArgumentException failure;

        FailingDecoderAdapter(IllegalArgumentException failure) {
            this.failure = failure;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            throw failure;
        }
    }

    static final class NoProgressCharset extends Charset {

        NoProgressCharset() {
            super("X-Procwright-Protocol-No-Progress", new String[0]);
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

    static final class FiniteErrorAfterExhaustionCharset extends Charset {

        FiniteErrorAfterExhaustionCharset() {
            super("X-Procwright-Protocol-Finite-Error-After-Exhaustion", new String[0]);
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

    static final class PersistentResponseInvalidReplacementCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();

        PersistentResponseInvalidReplacementCharset() {
            super("X-Procwright-Persistent-Response-Invalid-Replacement", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.getAndIncrement() == 2) {
                return invalidReplacementDecoder();
            }
            return passthroughDecoder();
        }

        private CharsetDecoder invalidReplacementDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                private boolean consumed;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!consumed) {
                        input.position(input.limit());
                        consumed = true;
                        return CoderResult.OVERFLOW;
                    }
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class PersistentTranscriptRuntimeFailureCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("transcript decoder failed");
        private final CountDownLatch beforeFailure;

        PersistentTranscriptRuntimeFailureCharset(CountDownLatch beforeFailure) {
            super("X-Procwright-Persistent-Transcript-Runtime-Failure", new String[0]);
            this.beforeFailure = beforeFailure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.getAndIncrement() < 2) {
                return new CharsetDecoder(this, 1, 1) {
                    @Override
                    protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                        beforeFailure.countDown();
                        throw failure;
                    }
                };
            }
            return passthroughDecoder();
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        IllegalArgumentException failure() {
            return failure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class PersistentTranscriptNewDecoderFailureCharset extends Charset {

        private final int failingCreation;
        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("new decoder failed");

        PersistentTranscriptNewDecoderFailureCharset(int failingCreation) {
            super("X-Procwright-Persistent-Transcript-New-Decoder-Failure", new String[0]);
            this.failingCreation = failingCreation;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.incrementAndGet() == failingCreation) {
                throw failure;
            }
            return passthroughDecoder();
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        IllegalArgumentException failure() {
            return failure;
        }

        int decoderCreations() {
            return createdDecoders.get();
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class PersistentTranscriptFlushFailureCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("decoder flush failed");

        PersistentTranscriptFlushFailureCharset() {
            super("X-Procwright-Persistent-Transcript-Flush-Failure", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.getAndIncrement() < 2) {
                return new CharsetDecoder(this, 1, 1) {
                    @Override
                    protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                        while (input.hasRemaining() && output.hasRemaining()) {
                            output.put((char) Byte.toUnsignedInt(input.get()));
                        }
                        return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                    }

                    @Override
                    protected CoderResult implFlush(CharBuffer output) {
                        throw failure;
                    }
                };
            }
            return passthroughDecoder();
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        IllegalArgumentException failure() {
            return failure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class NewlineWithoutConsumptionCharset extends Charset {

        NewlineWithoutConsumptionCharset() {
            super("X-Procwright-Newline-Without-Consumption", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                private int emittedLines;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    int availableLines = 0;
                    for (int index = input.position(); index < input.limit(); index++) {
                        if (input.get(index) == (byte) '\n') {
                            availableLines++;
                        }
                    }
                    if (availableLines <= emittedLines) {
                        return CoderResult.UNDERFLOW;
                    }
                    if (output.remaining() < 3) {
                        return CoderResult.OVERFLOW;
                    }
                    output.put("ok\n");
                    emittedLines++;
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }
}

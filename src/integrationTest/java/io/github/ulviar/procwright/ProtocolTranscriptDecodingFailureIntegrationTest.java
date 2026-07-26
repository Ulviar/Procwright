/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.StdoutLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolTranscriptDecodingFailureIntegrationTest {

    @Test
    void concurrentTranscriptFailureWinsBeforeProtocolCallbackSuccess() throws Exception {
        CountDownLatch beforeFailure = new CountDownLatch(1);
        AtomicReference<ProtocolSession<String, String>> sessionReference = new AtomicReference<>();
        PersistentTranscriptRuntimeFailureCharset charset =
                new PersistentTranscriptRuntimeFailureCharset(beforeFailure);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                awaitIgnoringInterrupts(beforeFailure);
                sessionReference.get().onExit().join();
                return "fallback";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(charset)));
        sessionReference.set(session);
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("trigger"));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, charset.failure()));
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void transcriptDecoderInitializationFailureClosesProtocolSessionForEitherStream() throws Exception {
        for (int failingCreation : List.of(1, 2)) {
            PersistentTranscriptNewDecoderFailureCharset charset =
                    new PersistentTranscriptNewDecoderFailureCharset(failingCreation);

            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class,
                    () -> openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                    "partial", "--stdout=", "--stderr=", "--hold-millis=5000")
                            .withTranscriptLimit(32)
                            .withCharsetPolicy(CharsetPolicy.report(charset))));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, charset.failure()));
            assertTrue(exception.transcript().text().length() <= 32);
            assertEquals(failingCreation, charset.decoderCreations());
        }
    }

    @Test
    void transcriptDecoderFlushRuntimeFailureClosesProtocolSession() throws Exception {
        PersistentTranscriptFlushFailureCharset charset = new PersistentTranscriptFlushFailureCharset();
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=x", "--stderr=", "--hold-millis=100")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(charset)));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, charset.failure()));
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void transcriptDecoderWithoutProgressFailsAndClosesProtocolSession() throws Exception {
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=", "--stderr=" + "e".repeat(4096), "--hold-millis=5000")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(new NoProgressCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void invalidReplacementLengthInTranscriptFailsAndClosesProtocolSession() throws Exception {
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=", "--stderr=x", "--hold-millis=5000")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(new FiniteErrorAfterExhaustionCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    private static boolean causeChainContains(Throwable failure, Throwable expected) {
        Throwable current = failure;
        while (current != null) {
            if (current == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class PersistentTranscriptRuntimeFailureCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("transcript decoder failed");
        private final CountDownLatch beforeFailure;

        private PersistentTranscriptRuntimeFailureCharset(CountDownLatch beforeFailure) {
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

        private IllegalArgumentException failure() {
            return failure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class PersistentTranscriptNewDecoderFailureCharset extends Charset {

        private final int failingCreation;
        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("new decoder failed");

        private PersistentTranscriptNewDecoderFailureCharset(int failingCreation) {
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

        private IllegalArgumentException failure() {
            return failure;
        }

        private int decoderCreations() {
            return createdDecoders.get();
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class PersistentTranscriptFlushFailureCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("decoder flush failed");

        private PersistentTranscriptFlushFailureCharset() {
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

        private IllegalArgumentException failure() {
            return failure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class NoProgressCharset extends Charset {

        private NoProgressCharset() {
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

    private static final class FiniteErrorAfterExhaustionCharset extends Charset {

        private FiniteErrorAfterExhaustionCharset() {
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
}

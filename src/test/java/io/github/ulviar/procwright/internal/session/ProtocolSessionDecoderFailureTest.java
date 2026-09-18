/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolSessionDecoderFailureTest extends ProtocolSessionContractSupport {

    @Test
    void fatalTranscriptDecoderErrorFailStopsActiveAndFollowUpRequestsForEitherStream() throws Exception {
        for (boolean fatalStdout : List.of(true, false)) {
            AssertionError fatalError =
                    new AssertionError("fatal protocol " + (fatalStdout ? "stdout" : "stderr") + " decoder failure");
            LatchingFatalTranscriptCharset charset = new LatchingFatalTranscriptCharset(fatalError);
            GatedByteInputStream fatalStream = new GatedByteInputStream((byte) '!');
            BlockingUntilClosedInputStream blockedStdout = new BlockingUntilClosedInputStream();
            InputStream stdout = fatalStdout ? fatalStream : blockedStdout;
            InputStream stderr = fatalStdout ? InputStream.nullInputStream() : fatalStream;
            CountingOutputStream stdin = new CountingOutputStream();
            ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
            ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
                @Override
                public void writeRequest(String request, ProtocolWriter writer) {
                    writer.write(new byte[] {1});
                    writer.flush();
                }

                @Override
                public Byte readResponse(ProtocolReaders readers) {
                    return readers.stdout().readByte();
                }
            };
            DefaultProtocolSession<String, Byte> protocol =
                    protocolSession(process, adapter, options(charset).withTranscriptLimit(32));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
                assertTrue(stdin.awaitWrite(), "the request must be active before transcript decoding fails");
                fatalStream.releaseByte();
                assertTrue(charset.awaitBeforeFailure());
                charset.releaseFailure();

                assertSame(fatalError, request.get(2, TimeUnit.SECONDS));
                assertExitFailedWith(protocol, fatalError);
                assertFalse(process.isAlive());
                assertTrue(protocol.transcript().text().length() <= 32);
                int writesAfterFailure = stdin.writeCalls();

                AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
                assertSame(fatalError, followUp);
                assertEquals(writesAfterFailure, stdin.writeCalls());
            } finally {
                fatalStream.releaseByte();
                charset.releaseFailure();
                try {
                    protocol.close();
                } finally {
                    blockedStdout.close();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void lateProtocolStderrDecoderErrorDoesNotReplaceEarlierResponseLimitFailure() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated(true);
    }

    @Test
    void protocolStderrDecoderErrorSelectedFirstRemainsPrimary() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated(false);
    }

    private static void assertResponseLimitAndFatalErrorAreArbitrated(boolean responseFailureFirst) throws Exception {
        AssertionError fatalError = new AssertionError("fatal protocol stderr decoder failure");
        RacingProtocolDecoderCharset charset = new RacingProtocolDecoderCharset(fatalError);
        GatedByteInputStream stdout = new GatedByteInputStream((byte) 'x');
        GatedByteInputStream stderr = new GatedByteInputStream((byte) '!');
        CountingOutputStream stdin = new CountingOutputStream();
        AtomicReference<ProtocolSessionException> observedResponseFailure = new AtomicReference<>();
        CountDownLatch responseFailureCaught = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.write(new byte[] {1});
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readLine(1);
                    throw new AssertionError("response limit was not enforced");
                } catch (ProtocolSessionException failure) {
                    observedResponseFailure.set(failure);
                    responseFailureCaught.countDown();
                    awaitUninterruptibly(allowCallbackReturn);
                    return "fallback";
                }
            }
        };
        ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
        DefaultProtocolSession<String, String> protocol = protocolSession(process, adapter, options(charset));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(stdin.awaitWrite(), "the request must be active before either output failure");

            stdout.releaseByte();
            assertTrue(charset.awaitResponseDecoder(), "response decoder did not reach its controlled boundary");
            if (responseFailureFirst) {
                charset.releaseResponseDecoder();
                assertTrue(
                        responseFailureCaught.await(1, TimeUnit.SECONDS),
                        "response limit must occupy the terminal outcome before stderr fails");
            }

            stderr.releaseByte();
            assertTrue(charset.awaitFatalDecoder(), "stderr decoder did not reach its controlled boundary");
            charset.releaseFatalDecoder();
            if (!responseFailureFirst) {
                assertExitFailedWith(protocol, fatalError);
                charset.releaseResponseDecoder();
            }

            allowCallbackReturn.countDown();
            Throwable thrown = request.get(2, TimeUnit.SECONDS);
            ProtocolSessionException responseFailure = observedResponseFailure.get();
            if (responseFailureFirst) {
                assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
                assertSame(responseFailure, thrown);
                assertExitFailedWith(protocol, responseFailure);
            } else {
                assertSame(fatalError, thrown);
                assertExitFailedWith(protocol, fatalError);
                if (responseFailure != null) {
                    assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
                }
            }
            assertFalse(process.isAlive());
            assertEquals(0, fatalError.getSuppressed().length);

            int writesAfterFailure = stdin.writeCalls();
            Throwable followUp = captureFailure(() -> protocol.request("retry"));
            if (responseFailureFirst) {
                ProtocolSessionException followUpFailure = assertInstanceOf(ProtocolSessionException.class, followUp);
                assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUpFailure.reason());
            } else {
                assertSame(fatalError, followUp);
            }
            assertEquals(writesAfterFailure, stdin.writeCalls());
        } finally {
            stdout.releaseByte();
            stderr.releaseByte();
            charset.releaseResponseDecoder();
            charset.releaseFatalDecoder();
            allowCallbackReturn.countDown();
            try {
                protocol.close();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertExitFailedWith(DefaultProtocolSession<?, ?> protocol, Throwable expectedFailure) {
        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> protocol.onExit().get(1, TimeUnit.SECONDS));
        assertSame(expectedFailure, exitFailure.getCause());
    }

    private static final class LatchingFatalTranscriptCharset extends Charset {

        private final AssertionError failure;
        private final AtomicInteger decoderCreations = new AtomicInteger();
        private final CountDownLatch beforeFailure = new CountDownLatch(1);
        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        private LatchingFatalTranscriptCharset(AssertionError failure) {
            super("X-Procwright-Protocol-Fatal-Transcript-Decoder", new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (decoderCreations.getAndIncrement() >= 2) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    input.get();
                    beforeFailure.countDown();
                    awaitUninterruptibly(releaseFailure);
                    throw failure;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitBeforeFailure() throws InterruptedException {
            return beforeFailure.await(1, TimeUnit.SECONDS);
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static final class RacingProtocolDecoderCharset extends Charset {

        private final AssertionError fatalError;
        private final AtomicInteger decoderCreations = new AtomicInteger();
        private final CountDownLatch responseDecoderEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResponseDecoder = new CountDownLatch(1);
        private final CountDownLatch fatalDecoderEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFatalDecoder = new CountDownLatch(1);

        private RacingProtocolDecoderCharset(AssertionError fatalError) {
            super("X-Procwright-Protocol-First-Outcome-Race", new String[0]);
            this.fatalError = fatalError;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            int creation = decoderCreations.incrementAndGet();
            return switch (creation) {
                case 1, 4 -> passthroughDecoder(this);
                case 2 -> delayedFatalDecoder();
                case 3 -> delayedResponseDecoder();
                default -> throw new AssertionError("unexpected decoder creation " + creation);
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private CharsetDecoder delayedResponseDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                private boolean emitted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining() || emitted) {
                        return CoderResult.UNDERFLOW;
                    }
                    input.get();
                    responseDecoderEntered.countDown();
                    awaitUninterruptibly(releaseResponseDecoder);
                    output.put('o').put('k').put('\n');
                    emitted = true;
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        private CharsetDecoder delayedFatalDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    input.get();
                    fatalDecoderEntered.countDown();
                    awaitUninterruptibly(releaseFatalDecoder);
                    throw fatalError;
                }
            };
        }

        private boolean awaitResponseDecoder() throws InterruptedException {
            return responseDecoderEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseResponseDecoder() {
            releaseResponseDecoder.countDown();
        }

        private boolean awaitFatalDecoder() throws InterruptedException {
            return fatalDecoderEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseFatalDecoder() {
            releaseFatalDecoder.countDown();
        }
    }

    private static final class GatedByteInputStream extends InputStream {

        private final byte value;
        private final CountDownLatch releaseByte = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();

        private GatedByteInputStream(byte value) {
            this.value = value;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseByte);
            return delivered.compareAndSet(false, true) ? Byte.toUnsignedInt(value) : -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            int next = read();
            if (next < 0) {
                return -1;
            }
            bytes[offset] = (byte) next;
            return 1;
        }

        private void releaseByte() {
            releaseByte.countDown();
        }
    }
}

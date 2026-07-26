/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionFatalOutputFailureTest extends DefaultLineSessionTestSupport {

    @Test
    void fatalOutputDecoderErrorFailStopsActiveAndFollowUpRequestsForEitherStream() throws Exception {
        for (boolean fatalStdout : List.of(true, false)) {
            AssertionError fatalError =
                    new AssertionError("fatal " + (fatalStdout ? "stdout" : "stderr") + " decoder failure");
            LatchingFatalDecoderCharset charset = new LatchingFatalDecoderCharset(fatalError);
            GatedByteInputStream fatalStream = new GatedByteInputStream((byte) '!');
            BlockingUntilClosedInputStream blockedStdout = new BlockingUntilClosedInputStream();
            InputStream stdout = fatalStdout ? fatalStream : blockedStdout;
            InputStream stderr = fatalStdout ? InputStream.nullInputStream() : fatalStream;
            CountingOutputStream stdin = new CountingOutputStream();
            ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultLineSession lineSession =
                    new DefaultLineSession(rawSession, options(charset).withTranscriptLimit(32));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request = executor.submit(() -> captureFailure(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2))));
                assertTrue(stdin.awaitWrite(), "the request must be active before output decoding fails");
                fatalStream.releaseByte();
                assertTrue(charset.awaitBeforeFailure());
                charset.releaseFailure();

                assertSame(fatalError, request.get(2, TimeUnit.SECONDS));
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
                assertTrue(lineSession.transcript().text().length() <= 32);
                int writesAfterFailure = stdin.writeCalls();

                AssertionError followUp = assertThrows(
                        AssertionError.class,
                        () -> lineSession.requestEncoded(
                                "retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
                assertSame(fatalError, followUp);
                assertEquals(writesAfterFailure, stdin.writeCalls());
            } finally {
                fatalStream.releaseByte();
                charset.releaseFailure();
                try {
                    lineSession.close();
                } finally {
                    blockedStdout.close();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void fatalStderrDecoderFailureReplacesAnEarlierResponseLimit() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated();
    }

    private static void assertResponseLimitAndFatalErrorAreArbitrated() throws Exception {
        AssertionError fatalError = new AssertionError("fatal stderr decoder failure");
        RacingLineDecoderCharset charset = new RacingLineDecoderCharset(fatalError);
        GatedByteInputStream stdout = new GatedByteInputStream((byte) 'x');
        GatedByteInputStream stderr = new GatedByteInputStream((byte) '!');
        CountingOutputStream stdin = new CountingOutputStream();
        AtomicReference<LineSessionException> observedResponseFailure = new AtomicReference<>();
        CountDownLatch responseFailureCaught = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        LineSessionSettings options = options(charset).withMaxResponseChars(1).withResponseDecoder(reader -> {
            try {
                reader.readLine();
                throw new AssertionError("response limit was not enforced");
            } catch (LineSessionException failure) {
                observedResponseFailure.set(failure);
                responseFailureCaught.countDown();
                awaitUninterruptibly(allowCallbackReturn);
                return List.of("fallback");
            }
        });
        ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
        DefaultSession rawSession = session(process);
        DefaultLineSession lineSession = new DefaultLineSession(rawSession, options);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() ->
                    lineSession.requestEncoded("request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5))));
            assertTrue(stdin.awaitWrite(), "the request must be active before either output failure");

            stdout.releaseByte();
            assertTrue(charset.awaitResponseDecoder(), "stdout decoder did not reach its controlled boundary");
            charset.releaseResponseDecoder();
            assertTrue(
                    responseFailureCaught.await(1, TimeUnit.SECONDS),
                    "response limit must occupy the terminal outcome before stderr fails");

            stderr.releaseByte();
            assertTrue(charset.awaitFatalDecoder(), "stderr decoder did not reach its controlled boundary");
            charset.releaseFatalDecoder();

            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            allowCallbackReturn.countDown();

            Throwable thrown = request.get(2, TimeUnit.SECONDS);
            LineSessionException responseFailure = observedResponseFailure.get();
            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
            assertSame(fatalError, thrown);
            assertEquals(0, fatalError.getSuppressed().length);
            assertEquals(0, responseFailure.getSuppressed().length);

            int writesAfterFailure = stdin.writeCalls();
            Throwable followUp = captureFailure(() ->
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertSame(fatalError, followUp);
            assertEquals(writesAfterFailure, stdin.writeCalls());
        } finally {
            stdout.releaseByte();
            stderr.releaseByte();
            charset.releaseResponseDecoder();
            charset.releaseFatalDecoder();
            allowCallbackReturn.countDown();
            try {
                lineSession.close();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    private static final class LatchingFatalDecoderCharset extends Charset {

        final AssertionError failure;
        final CountDownLatch beforeFailure = new CountDownLatch(1);
        final CountDownLatch releaseFailure = new CountDownLatch(1);

        LatchingFatalDecoderCharset(AssertionError failure) {
            super("X-Procwright-Line-Fatal-Decoder", new String[0]);
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

        boolean awaitBeforeFailure() throws InterruptedException {
            return beforeFailure.await(1, TimeUnit.SECONDS);
        }

        void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static final class RacingLineDecoderCharset extends Charset {

        final AssertionError fatalError;
        final AtomicInteger decoderCreations = new AtomicInteger();
        final CountDownLatch responseDecoderEntered = new CountDownLatch(1);
        final CountDownLatch releaseResponseDecoder = new CountDownLatch(1);
        final CountDownLatch fatalDecoderEntered = new CountDownLatch(1);
        final CountDownLatch releaseFatalDecoder = new CountDownLatch(1);

        RacingLineDecoderCharset(AssertionError fatalError) {
            super("X-Procwright-Line-First-Outcome-Race", new String[0]);
            this.fatalError = fatalError;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            int creation = decoderCreations.incrementAndGet();
            if (creation == 1) {
                return delayedResponseDecoder();
            }
            if (creation == 2) {
                return delayedFatalDecoder();
            }
            throw new AssertionError("unexpected decoder creation " + creation);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        CharsetDecoder delayedResponseDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                boolean emitted;

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

        CharsetDecoder delayedFatalDecoder() {
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

        boolean awaitResponseDecoder() throws InterruptedException {
            return responseDecoderEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseResponseDecoder() {
            releaseResponseDecoder.countDown();
        }

        boolean awaitFatalDecoder() throws InterruptedException {
            return fatalDecoderEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseFatalDecoder() {
            releaseFatalDecoder.countDown();
        }
    }

    private static final class CountingOutputStream extends OutputStream {

        final CountDownLatch firstWrite = new CountDownLatch(1);
        final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public void write(int value) {
            writeCalls.incrementAndGet();
            firstWrite.countDown();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            firstWrite.countDown();
        }

        boolean awaitWrite() throws InterruptedException {
            return firstWrite.await(1, TimeUnit.SECONDS);
        }

        int writeCalls() {
            return writeCalls.get();
        }
    }

    private static final class GatedByteInputStream extends InputStream {

        final byte value;
        final CountDownLatch releaseByte = new CountDownLatch(1);
        final AtomicBoolean delivered = new AtomicBoolean();

        GatedByteInputStream(byte value) {
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

        void releaseByte() {
            releaseByte.countDown();
        }
    }
}

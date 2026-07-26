/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StreamRuntimeOutputPumpTest extends StreamRuntimeTestSupport {

    @Test
    void outputReadFailureHasStableReason() throws Exception {
        IOException readFailure = new IOException("read failed");
        ControllableProcess process =
                new ControllableProcess(new FailingInputStream(readFailure), InputStream.nullInputStream(), null);
        StreamSession stream = openStream(process, plan());
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
            assertSame(readFailure, streamFailure.getCause());
        } finally {
            stream.close();
        }
    }

    @Test
    void decoderInitializationFailureHasStableReasonForEitherPump() throws Exception {
        for (String source : List.of("stdout", "stderr")) {
            IllegalArgumentException cause = new IllegalArgumentException(source + " decoder initialization failed");
            Charset charset = new ThreadSelectedNewDecoderFailureCharset(source, cause);
            ControllableProcess process = new ControllableProcess();
            StreamSession stream = openStream(process, plan(charset, 16));
            try {
                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
                assertSame(cause, streamFailure.getCause());
                assertEquals(true, streamFailure.diagnostics().text().length() <= 16);
                assertFalse(process.isAlive());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    void decoderRuntimeFailureRemainsAFatalCoderMalfunction() throws Exception {
        IllegalArgumentException cause = new IllegalArgumentException("stream decoder failed");
        Charset charset = new RuntimeFailureCharset(cause);
        ControllableProcess process =
                new ControllableProcess(new ByteArrayInputStream(new byte[] {1}), InputStream.nullInputStream(), null);
        StreamSession stream = openStream(process, plan(charset, 16));
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            CoderMalfunctionError decoderFailure = assertInstanceOf(CoderMalfunctionError.class, failure.getCause());

            assertSame(cause, decoderFailure.getCause());
            assertFalse(process.isAlive());
        } finally {
            stream.close();
        }
    }

    @Test
    void outputOnlyDecoderIsBoundedForEitherPump() throws Exception {
        for (String failingSource : List.of("stdout", "stderr")) {
            Charset charset = new ThreadSelectedOutputOnlyCharset(failingSource);
            CloseTrackingInputStream failing = new CloseTrackingInputStream(new byte[] {1});
            BlockingUntilClosedInputStream other = new BlockingUntilClosedInputStream();
            InputStream stdout = failingSource.equals("stdout") ? failing : other;
            InputStream stderr = failingSource.equals("stderr") ? failing : other;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            AtomicInteger listenerCalls = new AtomicInteger();
            StreamSession stream = openStream(process, plan(charset, 16, chunk -> listenerCalls.incrementAndGet()));
            try {
                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
                assertInstanceOf(IncrementalTextDecoder.DecoderStateException.class, streamFailure.getCause());
                assertTrue(streamFailure.diagnostics().text().length() <= 16);
                assertEquals(0, listenerCalls.get(), "transactional decoding must not publish hostile staged output");
                assertTrue(failing.awaitClose());
                assertEquals(1, failing.closeCalls());
                assertEquals(1, other.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    void zeroLengthPumpsBackOffAndStopAfterCloseForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            CloseTrackingInputStream eofStream = new CloseTrackingInputStream(new byte[0]);
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : eofStream;
            InputStream stderr = zeroStdout ? eofStream : zeroStream;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            StreamSession stream = openStream(
                    process,
                    plan(),
                    diagnostics(),
                    StreamSessionTestDependencies.withBackoffAndPumpStarter(backoff, PumpStarter.threading()));
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads());

                stream.close();
                backoff.release();
                stream.onExit().get(1, TimeUnit.SECONDS);

                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive());
                assertEquals(1, zeroStream.reads());
                assertTrue(zeroStream.awaitClose());
                assertTrue(eofStream.awaitClose());
                assertEquals(1, zeroStream.closeCalls());
                assertEquals(1, eofStream.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                backoff.release();
                stream.close();
            }
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

    private static final class FailingInputStream extends InputStream {

        private final IOException failure;

        private FailingInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw failure;
        }
    }

    private static final class BlockingUntilClosedInputStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() {
            awaitUninterruptibly(closed);
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private int closeCalls() {
            return closes.get();
        }
    }

    private static final class ZeroForeverInputStream extends InputStream {

        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
        }

        private int reads() {
            return reads.get();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private Thread readerThread() {
            return readerThread;
        }
    }

    private static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class ThreadSelectedNewDecoderFailureCharset extends Charset {

        private final String failingThreadFragment;
        private final RuntimeException failure;

        private ThreadSelectedNewDecoderFailureCharset(String failingThreadFragment, RuntimeException failure) {
            super("X-Procwright-Stream-New-Decoder-Failure-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (Thread.currentThread().getName().contains(failingThreadFragment)) {
                throw failure;
            }
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class RuntimeFailureCharset extends Charset {

        private final RuntimeException failure;

        private RuntimeFailureCharset(RuntimeException failure) {
            super("X-Procwright-Stream-Runtime-Failure", new String[0]);
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
                    if (input.hasRemaining()) {
                        throw failure;
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

    private static final class ThreadSelectedOutputOnlyCharset extends Charset {

        private final String failingThreadFragment;

        private ThreadSelectedOutputOnlyCharset(String failingThreadFragment) {
            super("X-Procwright-Stream-Output-Only-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains(failingThreadFragment)) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
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
}

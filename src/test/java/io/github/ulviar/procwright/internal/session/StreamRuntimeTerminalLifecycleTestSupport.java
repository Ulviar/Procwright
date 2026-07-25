/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class StreamRuntimeTerminalLifecycleTestSupport extends StreamRuntimeTestSupport {

    protected static void assertTypedAndFatalOutputFailuresAreArbitrated(boolean typedFirst) throws Exception {
        IOException readFailure = new IOException("controlled stdout read failure");
        AssertionError fatalError = new AssertionError("controlled stderr fatal failure");
        GatedFailureInputStream typedStream = new GatedFailureInputStream(readFailure);
        GatedFailureInputStream fatalStream = new GatedFailureInputStream(fatalError);
        ControllableProcess process = new ControllableProcess(typedStream, fatalStream, null);
        DefaultSession rawSession = session(process);
        CopyOnWriteArrayList<Throwable> reported = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> reported.add(failure));
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(typedStream.awaitReadEntered());
            assertTrue(fatalStream.awaitReadEntered());
            if (typedFirst) {
                typedStream.release();
                assertTrue(typedStream.awaitThrow());
            } else {
                fatalStream.release();
                assertTrue(fatalStream.awaitThrow());
            }
            assertTrue(process.awaitDestroyed(), "the first failure must complete fail-stop cleanup");

            if (typedFirst) {
                fatalStream.release();
                assertTrue(fatalStream.awaitThrow());
            } else {
                typedStream.release();
                assertTrue(typedStream.awaitThrow());
            }

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            if (typedFirst) {
                StreamException primary = assertInstanceOf(StreamException.class, failure.getCause());
                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, primary.reason());
                assertSame(readFailure, primary.getCause());
                assertEquals(0, primary.getSuppressed().length);
            } else {
                assertSame(fatalError, failure.getCause());
                assertEquals(0, fatalError.getSuppressed().length);
            }
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(1, reported.size());
            if (typedFirst) {
                assertSame(fatalError, reported.get(0));
            } else {
                StreamException typedFailure = assertInstanceOf(StreamException.class, reported.get(0));
                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, typedFailure.reason());
                assertSame(readFailure, typedFailure.getCause());
            }
        } finally {
            typedStream.release();
            fatalStream.release();
            stream.close();
            BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1));
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    protected static final class HostileCompletionException extends CompletionException {

        protected static final long serialVersionUID = 1L;

        protected final AssertionError causeAccessFailure;

        protected HostileCompletionException(AssertionError causeAccessFailure) {
            super("hostile completion", null);
            this.causeAccessFailure = causeAccessFailure;
        }

        @Override
        public synchronized Throwable getCause() {
            throw causeAccessFailure;
        }
    }

    protected static final class GatedFailureInputStream extends InputStream {

        protected final Throwable failure;
        protected final CountDownLatch readEntered = new CountDownLatch(1);
        protected final CountDownLatch release = new CountDownLatch(1);
        protected final CountDownLatch beforeThrow = new CountDownLatch(1);

        protected GatedFailureInputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            awaitUninterruptibly(release);
            beforeThrow.countDown();
            if (failure instanceof IOException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public void close() {
            // The test releases failures explicitly to prove first-occurrence arbitration.
        }

        protected void release() {
            release.countDown();
        }

        protected boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        protected boolean awaitThrow() throws InterruptedException {
            return beforeThrow.await(1, TimeUnit.SECONDS);
        }
    }

    protected static final class CloseFailingInputStream extends InputStream {

        protected final InputStream reads;
        protected final Error closeFailure;
        protected final AtomicInteger closes = new AtomicInteger();
        protected final CountDownLatch closed = new CountDownLatch(1);

        protected CloseFailingInputStream(InputStream reads, Error closeFailure) {
            this.reads = reads;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() throws IOException {
            return reads.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return reads.read(buffer, offset, length);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
            throw closeFailure;
        }

        protected boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        protected int closeCalls() {
            return closes.get();
        }
    }

    protected static final class GatedByteInputStream extends InputStream {

        protected final byte value;
        protected final CountDownLatch release = new CountDownLatch(1);
        protected final AtomicBoolean delivered = new AtomicBoolean();

        protected GatedByteInputStream(byte value) {
            this.value = value;
        }

        @Override
        public int read() {
            awaitUninterruptibly(release);
            return delivered.compareAndSet(false, true) ? Byte.toUnsignedInt(value) : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int next = read();
            if (next < 0) {
                return -1;
            }
            buffer[offset] = (byte) next;
            return 1;
        }

        @Override
        public void close() {
            // The test releases the byte after terminal selection.
        }

        protected void release() {
            release.countDown();
        }
    }

    protected static final class BlockingCloseInputStream extends InputStream {

        protected final AtomicBoolean processAlive;
        protected final CountDownLatch readStarted = new CountDownLatch(1);
        protected final CountDownLatch closeStarted = new CountDownLatch(1);
        protected final CountDownLatch closeRelease = new CountDownLatch(1);
        protected final CountDownLatch closeCompleted = new CountDownLatch(1);
        protected final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        protected final AtomicInteger closes = new AtomicInteger();

        protected BlockingCloseInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(closeCompleted);
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            destroyedBeforeClose.set(!processAlive.get());
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
        }

        protected boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        protected boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        protected boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        protected void releaseClose() {
            closeRelease.countDown();
        }

        protected boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        protected boolean closeCompleted() {
            return closeCompleted.getCount() == 0;
        }

        protected int closeCalls() {
            return closes.get();
        }
    }

    protected static final class ThreadSelectedFatalDecoderCharset extends Charset {

        protected final String failingThreadFragment;
        protected final AssertionError failure;

        protected ThreadSelectedFatalDecoderCharset(String failingThreadFragment, AssertionError failure) {
            super("X-Procwright-Stream-Fatal-Decoder-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
            this.failure = failure;
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
                    if (input.hasRemaining()) {
                        input.get();
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
}

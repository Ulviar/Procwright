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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
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

final class StreamRuntimeTerminalLifecycleTest extends StreamRuntimeTestSupport {

    @Test
    void outputDecoderErrorIsTerminalByIdentityAndPreventsLaterListenerCallsForEitherPump() throws Exception {
        for (String fatalSource : List.of("stdout", "stderr")) {
            AssertionError fatalError = new AssertionError("fatal " + fatalSource + " decoder failure");
            Charset charset = new ThreadSelectedFatalDecoderCharset(fatalSource, fatalError);
            GatedByteInputStream gatedOther = new GatedByteInputStream((byte) 'x');
            CloseTrackingInputStream fatalInput = new CloseTrackingInputStream(new byte[] {1});
            InputStream stdout = fatalSource.equals("stdout") ? fatalInput : gatedOther;
            InputStream stderr = fatalSource.equals("stderr") ? fatalInput : gatedOther;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            AtomicInteger listenerCalls = new AtomicInteger();
            StreamSession stream = openStream(process, plan(charset, 16, chunk -> listenerCalls.incrementAndGet()));
            try {
                assertTrue(process.awaitDestroyed());
                gatedOther.release();

                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                assertSame(fatalError, failure.getCause());
                assertEquals(0, listenerCalls.get(), "no listener invocation may begin after terminal selection");
                assertFalse(process.isAlive());
            } finally {
                gatedOther.release();
                stream.close();
            }
        }
    }

    @Test
    void coderMalfunctionErrorRemainsFatalByIdentity() throws Exception {
        CoderMalfunctionError decoderFailure =
                new CoderMalfunctionError(new IllegalStateException("broken stream decoder"));
        Charset charset = new ThreadSelectedFatalDecoderCharset("stdout", decoderFailure);
        ControllableProcess process = new ControllableProcess(
                new CloseTrackingInputStream(new byte[] {1}), InputStream.nullInputStream(), null);
        StreamSession stream = openStream(process, plan(charset, 16));
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));

            assertSame(decoderFailure, failure.getCause());
            assertFalse(process.isAlive());
        } finally {
            stream.close();
        }
    }

    @Test
    void laterFatalErrorCannotReplaceAnAcceptedTypedOutputFailure() throws Exception {
        IOException readFailure = new IOException("controlled stdout read failure");
        AssertionError fatalError = new AssertionError("controlled stderr fatal failure");
        GatedFailureInputStream typedStream = new GatedFailureInputStream(readFailure);
        GatedFailureInputStream fatalStream = new GatedFailureInputStream(fatalError);
        ControllableProcess process = new ControllableProcess(typedStream, fatalStream, null);
        StreamSession stream = openStream(process, plan());
        try {
            assertTrue(typedStream.awaitReadEntered());
            assertTrue(fatalStream.awaitReadEntered());

            typedStream.release();
            assertTrue(typedStream.awaitThrow());
            assertTrue(process.awaitDestroyed(), "the first failure must complete fail-stop cleanup");
            fatalStream.release();
            assertTrue(fatalStream.awaitThrow());

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException primary = assertInstanceOf(StreamException.class, failure.getCause());
            assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, primary.reason());
            assertSame(readFailure, primary.getCause());
            ExecutionException repeated =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            assertSame(primary, repeated.getCause());
        } finally {
            typedStream.release();
            fatalStream.release();
            stream.close();
        }
    }

    @Test
    void laterTypedOutputFailureCannotReplaceAnAcceptedFatalError() throws Exception {
        IOException readFailure = new IOException("controlled stdout read failure");
        AssertionError fatalError = new AssertionError("controlled stderr fatal failure");
        GatedFailureInputStream typedStream = new GatedFailureInputStream(readFailure);
        GatedFailureInputStream fatalStream = new GatedFailureInputStream(fatalError);
        ControllableProcess process = new ControllableProcess(typedStream, fatalStream, null);
        DefaultStreamSession stream = openStream(process, plan());
        try {
            assertTrue(typedStream.awaitReadEntered());
            assertTrue(fatalStream.awaitReadEntered());

            fatalStream.release();
            assertTrue(fatalStream.awaitThrow());
            assertTrue(process.awaitDestroyed(), "the first failure must complete fail-stop cleanup");
            typedStream.release();
            assertTrue(typedStream.awaitThrow());

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            assertSame(fatalError, failure.getCause());
            ExecutionException repeated =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            assertSame(fatalError, repeated.getCause());
        } finally {
            typedStream.release();
            fatalStream.release();
            stream.close();
        }
    }

    @Test
    void closeStopsProcessBeforeBlockingOutputClosesAndDoesNotWaitForThem() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        BlockingCloseInputStream stderr = new BlockingCloseInputStream(processAlive);
        ControllableProcess process =
                new ControllableProcess(stdout, stderr, null, OutputStream.nullOutputStream(), processAlive);
        AtomicReference<DefaultSession> nestedSession = new AtomicReference<>();
        StreamSession stream = openStream(
                process, plan(), diagnostics(), DefaultStreamSession.Dependencies.defaults(), nestedSession::set);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> close = null;
        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            close = executor.submit(stream::close);

            assertTrue(process.awaitDestroyed(), "process cleanup must precede helper output closure");
            close.get(1, TimeUnit.SECONDS);
            assertTrue(nestedSession.get().terminationPublished());
            assertTrue(nestedSession.get().onExit().isDone());
            assertTrue(stream.onExit().isDone());
            assertTrue(stdout.awaitCloseStarted());
            assertTrue(stderr.awaitCloseStarted());
            assertTrue(stdout.destroyedBeforeClose());
            assertTrue(stderr.destroyedBeforeClose());
            assertFalse(stdout.closeCompleted());
            assertFalse(stderr.closeCompleted());
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            if (close != null) {
                close.get(1, TimeUnit.SECONDS);
            }
            stream.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(stdout.awaitCloseCompleted());
        assertTrue(stderr.awaitCloseCompleted());
        nestedSession.get().onExit().get(1, TimeUnit.SECONDS);
        stream.onExit().get(1, TimeUnit.SECONDS);
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
    }

    @Test
    void stdinCloseFailureAbandonsBlockedOutputReadsAndSettlesExit() throws Exception {
        IOException stdinFailure = new IOException("stdin close failed");
        GatedFailingCloseOutputStream stdin = new GatedFailingCloseOutputStream(stdinFailure);
        NonCooperativeInputStream stdout = new NonCooperativeInputStream();
        NonCooperativeInputStream stderr = new NonCooperativeInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr, null, stdin);
        StreamSession stream = openStream(process, plan());
        try {
            assertTrue(stdin.awaitCloseStarted());
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            stdin.releaseClose();

            assertTrue(process.awaitDestroyed());
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(1, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());
            assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
            assertSame(stdinFailure, streamFailure.getCause());
        } finally {
            stdin.releaseClose();
            stdout.releaseRead();
            stderr.releaseRead();
            stream.close();
        }
    }

    @Test
    void configuredTimeoutBoundsOutputDrainAfterNaturalRootExit() throws Exception {
        NonCooperativeInputStream stdout = new NonCooperativeInputStream();
        NonCooperativeInputStream stderr = new NonCooperativeInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr, null);
        StreamSession stream =
                openStream(process, DefaultStreamSessionTestSupport.plan(chunk -> {}, Duration.ofMillis(100)));
        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            process.alive.set(false);
            process.exit.complete(0);

            assertTrue(stream.onExit().get(1, TimeUnit.SECONDS).timedOut());
        } finally {
            stdout.releaseRead();
            stderr.releaseRead();
            stream.close();
        }
    }

    @Test
    void ordinaryCloseRemainsStableWhenPumpsFailDuringPhysicalCleanup() throws Exception {
        AssertionError fatalFailure = new AssertionError("fatal pump failure after close");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        GatedFailureInputStream fatalReads = new GatedFailureInputStream(fatalFailure);
        CloseFailingInputStream stdout = new CloseFailingInputStream(fatalReads, stdoutCloseFailure);
        CloseFailingInputStream stderr = new CloseFailingInputStream(InputStream.nullInputStream(), stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr, null);
        StreamSession stream = openStream(process, plan());
        try {
            assertTrue(fatalReads.awaitReadEntered());

            stream.close();
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());

            fatalReads.release();
            assertTrue(fatalReads.awaitThrow());
            var exit = stream.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.closed());
            assertFalse(exit.timedOut());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            fatalReads.release();
            stream.close();
        }
    }

    @Test
    void processFailureHasStableReason() throws Exception {
        IllegalStateException processFailure = new IllegalStateException("wait failed");
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        StreamSession stream = openStream(process, plan());
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
            assertSame(processFailure, streamFailure.getCause());
        } finally {
            process.releaseWaitFailure();
            stream.close();
        }
    }

    @Test
    void processWaitErrorIsTerminalByIdentity() throws Exception {
        AssertionError processFailure = new AssertionError("fatal wait failure");
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        StreamSession stream = openStream(process, plan());
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            assertSame(processFailure, failure.getCause());
            assertFalse(process.isAlive());
        } finally {
            process.releaseWaitFailure();
            stream.close();
        }
    }

    @Test
    void hostileCompletionCauseAccessorStillFailStopsAndCompletesStreamExit() throws Exception {
        AssertionError causeAccessFailure = new AssertionError("hostile getCause");
        HostileCompletionException processFailure = new HostileCompletionException(causeAccessFailure);
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        StreamSession stream = openStream(process, plan());
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());
            assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
            assertSame(processFailure, streamFailure.getCause());
            assertFalse(process.isAlive());
        } finally {
            process.releaseWaitFailure();
            stream.close();
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

    private static final class GatedFailingCloseOutputStream extends OutputStream {

        private final IOException failure;
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);

        private GatedFailingCloseOutputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() throws IOException {
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            throw failure;
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            closeRelease.countDown();
        }
    }

    private static final class NonCooperativeInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch readRelease = new CountDownLatch(1);

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(readRelease);
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            // The fixture deliberately keeps the read blocked until the test releases it.
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseRead() {
            readRelease.countDown();
        }
    }

    protected static final class ThreadSelectedFatalDecoderCharset extends Charset {

        protected final String failingThreadFragment;
        protected final Error failure;

        protected ThreadSelectedFatalDecoderCharset(String failingThreadFragment, Error failure) {
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

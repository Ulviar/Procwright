/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class DefaultLineSessionOutputLifecycleTestSupport extends DefaultLineSessionTestSupport {

    static BoundedCloseDispatcher outputStartFailingDispatcher(int capacity) {
        return new BoundedCloseDispatcher(2, capacity - 2, capacity, (name, task) -> {
            if (name.contains("stdout-close") || name.contains("stderr-close")) {
                throw new IllegalStateException("output close starter failed: " + name);
            }
            io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
    }

    static void closeIgnoringTerminal(DefaultLineSession session) {
        try {
            session.close();
        } catch (RuntimeException | Error ignored) {
            // The fixture deliberately makes every helper output close report a terminal failure.
        }
    }

    static final class LatchingFatalDecoderCharset extends Charset {

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

    static final class RacingLineDecoderCharset extends Charset {

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

    static final class OverflowThenMalformedCharset extends Charset {

        final CountDownLatch beforeMalformed = new CountDownLatch(1);
        final CountDownLatch releaseMalformed = new CountDownLatch(1);

        OverflowThenMalformedCharset() {
            super("X-Procwright-Line-Transactional-Overflow-Malformed", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                boolean overflowed;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!overflowed) {
                        if (!input.hasRemaining()) {
                            return CoderResult.UNDERFLOW;
                        }
                        input.get();
                        char[] line = {'o', 'k', '\n'};
                        int index = 0;
                        while (output.hasRemaining()) {
                            output.put(line[index++ % line.length]);
                        }
                        overflowed = true;
                        return CoderResult.OVERFLOW;
                    }
                    beforeMalformed.countDown();
                    awaitUninterruptibly(releaseMalformed);
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        boolean awaitBeforeMalformed() throws InterruptedException {
            return beforeMalformed.await(1, TimeUnit.SECONDS);
        }

        void releaseMalformed() {
            releaseMalformed.countDown();
        }
    }

    static final class CountingOutputStream extends OutputStream {

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

    static final class GatedByteInputStream extends InputStream {

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

    static final class ControlledPumpFailureInputStream extends InputStream {

        final Error readFailure;
        final Error closeFailure;
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        volatile Thread closeThread;

        ControlledPumpFailureInputStream(Error readFailure, Error closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            if (readFailure == null) {
                return -1;
            }
            readEntered.countDown();
            awaitUninterruptibly(releaseRead);
            throw readFailure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closeThread = Thread.currentThread();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            throw closeFailure;
        }

        boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseReadFailure() {
            releaseRead.countDown();
        }

        boolean awaitCloseEntered() throws InterruptedException {
            return closeEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseCloseFailure() {
            releaseClose.countDown();
        }

        boolean awaitCloseWorkerStopped() throws InterruptedException {
            Thread worker = closeThread;
            if (worker == null) {
                return false;
            }
            worker.join(TimeUnit.SECONDS.toMillis(1));
            return !worker.isAlive();
        }
    }

    static final class GatedEofCloseFailureInputStream extends InputStream {

        final AssertionError closeFailure;
        final byte[] payload;
        final CountDownLatch releaseEof = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        int offset;

        GatedEofCloseFailureInputStream(AssertionError closeFailure) {
            this(new byte[0], closeFailure);
        }

        GatedEofCloseFailureInputStream(byte[] payload, AssertionError closeFailure) {
            this.payload = payload.clone();
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseEof);
            return offset < payload.length ? payload[offset++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            awaitUninterruptibly(releaseEof);
            if (this.offset == payload.length) {
                return -1;
            }
            int count = Math.min(length, payload.length - this.offset);
            System.arraycopy(payload, this.offset, bytes, offset, count);
            this.offset += count;
            return count;
        }

        @Override
        public void close() {
            closed.countDown();
            throw closeFailure;
        }

        void releaseEof() {
            releaseEof.countDown();
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    static final class ZeroForeverInputStream extends InputStream {

        final CountDownLatch firstRead = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            // Deliberately ignore close so the pump must observe its owning session state.
        }

        void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
            firstRead.countDown();
        }

        boolean awaitFirstRead() throws InterruptedException {
            return firstRead.await(1, TimeUnit.SECONDS);
        }

        int reads() {
            return reads.get();
        }

        Thread readerThread() {
            return readerThread;
        }
    }

    static final class BlockingPhysicalCloseInputStream extends InputStream {

        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
        }
    }
}

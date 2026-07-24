/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class ExpectOutputTestSupport extends ExpectTestSupport {
    static final class ControlledPumpFailureInputStream extends InputStream {

        final Error readFailure;
        final Error closeFailure;
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        volatile Thread readThread;
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
            readThread = Thread.currentThread();
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

        Thread readThread() {
            return readThread;
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

    static final class GatedPublicationCharset extends Charset {

        final CountDownLatch publicationReady = new CountDownLatch(1);
        final CountDownLatch publicationRelease = new CountDownLatch(1);

        GatedPublicationCharset() {
            super("X-Procwright-Expect-Gated-Publication", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains("stdout")) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    publicationReady.countDown();
                    awaitUninterruptibly(publicationRelease);
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        boolean awaitPublicationReady() throws InterruptedException {
            return publicationReady.await(1, TimeUnit.SECONDS);
        }

        void releasePublication() {
            publicationRelease.countDown();
        }
    }

    static final class TrackingPumpStarter implements PumpStarter {

        final List<Thread> threads = new ArrayList<>();

        @Override
        public Thread start(String namePrefix, Runnable task) {
            Thread thread = Threading.start(namePrefix, task);
            threads.add(thread);
            return thread;
        }

        boolean awaitStopped() throws InterruptedException {
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }
    }

    static final class ThreadSelectedOutputOnlyCharset extends Charset {

        final String failingThreadFragment;

        ThreadSelectedOutputOnlyCharset(String failingThreadFragment) {
            super("X-Procwright-Expect-Output-Only-" + failingThreadFragment, new String[0]);
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

    static final class CloseTrackingInputStream extends InputStream {

        final byte[] bytes;
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        int index;

        CloseTrackingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            return index < bytes.length ? Byte.toUnsignedInt(bytes[index++]) : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            int remaining = bytes.length - index;
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            System.arraycopy(bytes, index, target, offset, count);
            index += count;
            return count;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        int closeCalls() {
            return closes.get();
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    static final class BlockingUntilClosedInputStream extends InputStream {

        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() {
            awaitUninterruptibly(closed);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        int closeCalls() {
            return closes.get();
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    static final class BlockingCloseInputStream extends InputStream {

        final AtomicBoolean processAlive;
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeRelease = new CountDownLatch(1);
        final CountDownLatch closeCompleted = new CountDownLatch(1);
        final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();

        BlockingCloseInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(closeCompleted);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
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

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        void releaseClose() {
            closeRelease.countDown();
        }

        boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        boolean closeCompleted() {
            return closeCompleted.getCount() == 0;
        }

        int closeCalls() {
            return closes.get();
        }
    }

    static final class ZeroForeverInputStream extends InputStream {

        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
        }

        int reads() {
            return reads.get();
        }

        int closeCalls() {
            return closes.get();
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        Thread readerThread() {
            return readerThread;
        }
    }
}

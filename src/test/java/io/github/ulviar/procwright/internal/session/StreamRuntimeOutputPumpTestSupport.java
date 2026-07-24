/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

abstract class StreamRuntimeOutputPumpTestSupport extends StreamRuntimeTestSupport {

    protected static boolean causeChainContains(Throwable failure, Throwable expected) {
        Throwable current = failure;
        while (current != null) {
            if (current == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    protected static final class FailingInputStream extends InputStream {

        protected final IOException failure;

        protected FailingInputStream(IOException failure) {
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

    protected static final class BlockingUntilClosedInputStream extends InputStream {

        protected final CountDownLatch closed = new CountDownLatch(1);
        protected final AtomicInteger closes = new AtomicInteger();

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

        protected int closeCalls() {
            return closes.get();
        }
    }

    protected static final class ZeroForeverInputStream extends InputStream {

        protected final AtomicInteger reads = new AtomicInteger();
        protected final AtomicInteger closes = new AtomicInteger();
        protected final CountDownLatch closed = new CountDownLatch(1);
        protected volatile Thread readerThread;

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

        protected void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
        }

        protected int reads() {
            return reads.get();
        }

        protected int closeCalls() {
            return closes.get();
        }

        protected boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        protected Thread readerThread() {
            return readerThread;
        }
    }

    protected static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        protected final CountDownLatch entered = new CountDownLatch(1);
        protected final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        protected boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        protected void release() {
            release.countDown();
        }
    }

    protected static final class ThreadSelectedNewDecoderFailureCharset extends Charset {

        protected final String failingThreadFragment;
        protected final RuntimeException failure;

        protected ThreadSelectedNewDecoderFailureCharset(String failingThreadFragment, RuntimeException failure) {
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

    protected static final class RuntimeFailureCharset extends Charset {

        protected final RuntimeException failure;

        protected RuntimeFailureCharset(RuntimeException failure) {
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

    protected static final class ThreadSelectedOutputOnlyCharset extends Charset {

        protected final String failingThreadFragment;

        protected ThreadSelectedOutputOnlyCharset(String failingThreadFragment) {
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

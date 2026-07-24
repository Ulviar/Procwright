/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class DefaultLineSessionRequestAdmissionTestSupport extends DefaultLineSessionTestSupport {

    static final class PrefixThenThrowingOutputStream extends OutputStream {

        final Throwable failure;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        int writeCalls;

        PrefixThenThrowingOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writeCalls++;
            written.write(bytes, offset, Math.min(2, length));
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        int writeCalls() {
            return writeCalls;
        }

        String writtenText() {
            return written.toString(StandardCharsets.UTF_8);
        }
    }

    static final class BlockingReplyOutputStream extends OutputStream {

        static final byte[] RESPONSE = "ok\n".getBytes(StandardCharsets.UTF_8);

        final ResponseInputStream responses;
        final CountDownLatch callbackStarted;
        final CountDownLatch releaseCallback;
        final CountDownLatch closed = new CountDownLatch(1);

        BlockingReplyOutputStream(
                ResponseInputStream responses, CountDownLatch callbackStarted, CountDownLatch releaseCallback) {
            this.responses = responses;
            this.callbackStarted = callbackStarted;
            this.releaseCallback = releaseCallback;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            callbackStarted.countDown();
            awaitUninterruptibly(releaseCallback);
            responses.publish(RESPONSE);
        }

        @Override
        public void close() {
            closed.countDown();
        }

        boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }
    }

    static final class BlockingAfterFirstByteOutputStream extends OutputStream {

        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final CountDownLatch firstByte = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);
        final CountDownLatch writerStopped = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            if (length > 0) {
                synchronized (written) {
                    written.write(bytes[offset]);
                }
            }
            firstByte.countDown();
            try {
                releaseWriter.await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                writerStopped.countDown();
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }

        boolean awaitFirstByte() throws InterruptedException {
            return firstByte.await(5, TimeUnit.SECONDS);
        }

        boolean awaitWriterStopped() throws InterruptedException {
            return writerStopped.await(1, TimeUnit.SECONDS);
        }

        boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }

        byte[] writtenBytes() {
            synchronized (written) {
                return written.toByteArray();
            }
        }

        boolean wasInterrupted() {
            return interrupted.get();
        }

        int writeCalls() {
            return writeCalls.get();
        }

        void releaseWriter() {
            releaseWriter.countDown();
        }
    }

    static final class GatedErrorInputStream extends InputStream {

        final Error failure;
        final CountDownLatch releaseFailure = new CountDownLatch(1);

        GatedErrorInputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseFailure);
            throw failure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    static final class ControlledRequestLockWaiter implements SerializedRequestGate.Waiter {

        final CountDownLatch contended = new CountDownLatch(1);
        final CountDownLatch expired = new CountDownLatch(1);

        @Override
        public boolean acquire(java.util.concurrent.locks.ReentrantLock lock, long remainingNanos)
                throws InterruptedException {
            if (lock.tryLock()) {
                return true;
            }
            contended.countDown();
            expired.await();
            return false;
        }

        boolean awaitContended() throws InterruptedException {
            return contended.await(1, TimeUnit.SECONDS);
        }

        void expire() {
            expired.countDown();
        }
    }

    static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }
}

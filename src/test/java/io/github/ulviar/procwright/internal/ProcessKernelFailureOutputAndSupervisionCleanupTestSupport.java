/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class ProcessKernelFailureOutputAndSupervisionCleanupTestSupport extends ProcessKernelProcessFixtureSupport {

    static final class CleanupProcess extends Process {

        final OutputStream stdin;
        final AtomicBoolean alive = new AtomicBoolean(true);

        CleanupProcess(OutputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            alive.set(false);
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    static final class CloseCountingOutputStream extends OutputStream {

        final Error failure;
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        CloseCountingOutputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
            if (failure != null) {
                throw failure;
            }
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        final int closeCalls() {
            return closes.get();
        }
    }

    static final class BlockingCloseOutputStream extends TrackingOutputStream {

        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            super.close();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitClose() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    static final class BlockingCleanupInputStream extends TrackingInputStream {

        final AtomicBoolean cleanupFinished;
        final AtomicBoolean byteReturned = new AtomicBoolean();
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);

        BlockingCleanupInputStream(AtomicBoolean cleanupFinished) {
            this.cleanupFinished = cleanupFinished;
        }

        @Override
        public int read() {
            return byteReturned.compareAndSet(false, true) ? 0xC3 : -1;
        }

        @Override
        public void close() {
            super.close();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseClose.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            cleanupFinished.set(true);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitClose() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        void releaseClose() {
            releaseClose.countDown();
        }
    }

    static final class FailingReadInputStream extends TrackingInputStream {

        final AssertionError readFailure;
        final AssertionError closeFailure;

        FailingReadInputStream(AssertionError readFailure, AssertionError closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            throw readFailure;
        }

        @Override
        public void close() {
            super.close();
            throw closeFailure;
        }
    }

    static final class HostileDisplayNameCharset extends Charset {

        final RuntimeException decoderFailure;
        final Error displayNameFailure;
        final AtomicInteger displayNameCalls = new AtomicInteger();

        HostileDisplayNameCharset(RuntimeException decoderFailure, Error displayNameFailure) {
            super("x-procwright-hostile-display-name", null);
            this.decoderFailure = decoderFailure;
            this.displayNameFailure = displayNameFailure;
        }

        @Override
        public boolean contains(Charset charset) {
            return charset == this;
        }

        @Override
        public String displayName() {
            displayNameCalls.incrementAndGet();
            throw displayNameFailure;
        }

        @Override
        public CharsetDecoder newDecoder() {
            throw decoderFailure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        int displayNameCalls() {
            return displayNameCalls.get();
        }
    }

    static final class LivenessRestrictedProcess extends Process {

        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicInteger forceDestroyCalls = new AtomicInteger();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return stopped.get();
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            stopped.set(true);
        }

        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            stopped.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (!stopped.get()) {
                throw new SecurityException("root liveness observation is denied");
            }
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new SecurityException("descendant enumeration is denied");
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    static final class SelfFailingCleanupProcess extends Process {

        final AssertionError primaryFailure;
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicBoolean stdoutClosed = new AtomicBoolean();
        final AtomicBoolean stderrClosed = new AtomicBoolean();

        SelfFailingCleanupProcess(AssertionError primaryFailure) {
            this.primaryFailure = primaryFailure;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() {
                    return -1;
                }

                @Override
                public void close() throws IOException {
                    stdoutClosed.set(true);
                    throw primaryFailure;
                }
            };
        }

        @Override
        public InputStream getErrorStream() {
            return new InputStream() {
                @Override
                public int read() {
                    return -1;
                }

                @Override
                public void close() {
                    stderrClosed.set(true);
                }
            };
        }

        @Override
        public int waitFor() {
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return stopped.get();
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            throw primaryFailure;
        }

        @Override
        public Process destroyForcibly() {
            stopped.set(true);
            throw primaryFailure;
        }

        @Override
        public boolean isAlive() {
            if (!stopped.get()) {
                throw new SecurityException("root liveness observation is denied");
            }
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new SecurityException("descendant enumeration is denied");
        }

        boolean stdoutClosed() {
            return stdoutClosed.get();
        }

        boolean stderrClosed() {
            return stderrClosed.get();
        }
    }
}

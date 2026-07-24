/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class ProcessKernelProcessFixtureSupport extends ProcessKernelTestSupport {

    static class TrackingInputStream extends InputStream {

        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        final int closeCalls() {
            return closeCalls.get();
        }
    }

    static class TrackingOutputStream extends OutputStream {

        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        final int closeCalls() {
            return closeCalls.get();
        }
    }

    static final class NonCooperativeOutputStream extends TrackingOutputStream {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(byte[] bytes, int offset, int length) {
            entered.countDown();
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException interruption) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class TerminalProcess extends Process {

        final TrackingInputStream stdout;
        final TrackingInputStream stderr;
        final TrackingOutputStream stdin;
        final boolean stayAliveUntilDestroyed;
        final AtomicBoolean alive;
        final AtomicInteger stdinGets = new AtomicInteger();
        final AtomicInteger stdoutGets = new AtomicInteger();
        final AtomicInteger stderrGets = new AtomicInteger();

        TerminalProcess(TrackingInputStream stdout, TrackingInputStream stderr, TrackingOutputStream stdin) {
            this(stdout, stderr, stdin, false);
        }

        TerminalProcess(
                TrackingInputStream stdout,
                TrackingInputStream stderr,
                TrackingOutputStream stdin,
                boolean stayAliveUntilDestroyed) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdin = stdin;
            this.stayAliveUntilDestroyed = stayAliveUntilDestroyed;
            alive = new AtomicBoolean(stayAliveUntilDestroyed);
        }

        @Override
        public OutputStream getOutputStream() {
            stdinGets.incrementAndGet();
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            stdoutGets.incrementAndGet();
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrGets.incrementAndGet();
            return stderr;
        }

        @Override
        public int waitFor() {
            alive.set(false);
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (stdin instanceof NonCooperativeOutputStream nonCooperative) {
                nonCooperative.entered.await(timeout, unit);
            }
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("alive");
            }
            return stayAliveUntilDestroyed ? 143 : 0;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public long pid() {
            return 123L;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}

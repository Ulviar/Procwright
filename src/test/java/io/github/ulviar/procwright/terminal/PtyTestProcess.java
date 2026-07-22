/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class PtyTestProcess extends Process {

    private static final byte[] HANDSHAKE =
            ("\u001ePROCWRIGHT_PTY_READY\u001f\u001ePROCWRIGHT_PTY_STARTED\u001f").getBytes(StandardCharsets.US_ASCII);

    private final CloseAwareOutputStream stdin = new CloseAwareOutputStream();
    private final CloseAwareInputStream stdout;
    private final CloseAwareInputStream stderr = new CloseAwareInputStream(new byte[0]);
    private final int exitCode;
    private final boolean completesOnWait;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final AtomicInteger stdinGets = new AtomicInteger();
    private final AtomicInteger stdoutGets = new AtomicInteger();
    private final AtomicInteger stderrGets = new AtomicInteger();

    private PtyTestProcess(CloseAwareInputStream stdout, int exitCode, boolean completesOnWait) {
        this.stdout = stdout;
        this.exitCode = exitCode;
        this.completesOnWait = completesOnWait;
    }

    static PtyTestProcess completed(int exitCode) {
        return completedWithOutput(exitCode, HANDSHAKE);
    }

    static PtyTestProcess completedWithOutput(int exitCode, byte[] output) {
        return new PtyTestProcess(new CloseAwareInputStream(output), exitCode, true);
    }

    static PtyTestProcess hanging() {
        return new PtyTestProcess(new BlockingInputStream(), 143, false);
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
    public int waitFor() throws InterruptedException {
        if (completesOnWait) {
            alive.set(false);
        }
        while (alive.get()) {
            Thread.sleep(1);
        }
        return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        if (completesOnWait) {
            alive.set(false);
            return true;
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (alive.get() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        return !alive.get();
    }

    @Override
    public int exitValue() {
        if (alive.get()) {
            throw new IllegalThreadStateException("still running");
        }
        return exitCode;
    }

    @Override
    public void destroy() {
        destroyed.set(true);
        alive.set(false);
        try {
            stdin.close();
            stdout.close();
            stderr.close();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
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

    boolean destroyed() {
        return destroyed.get();
    }

    boolean streamsClosed() {
        return stdin.closed() && stdout.closed() && stderr.closed();
    }

    boolean stdinClosed() {
        return stdin.closed();
    }

    byte[] stdinBytes() {
        return stdin.toByteArray();
    }

    int stdinGetCount() {
        return stdinGets.get();
    }

    int stdoutGetCount() {
        return stdoutGets.get();
    }

    int stderrGetCount() {
        return stderrGets.get();
    }

    private static class CloseAwareInputStream extends InputStream {

        private final InputStream delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseAwareInputStream(byte[] output) {
            delegate = new ByteArrayInputStream(output);
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            delegate.close();
        }

        final boolean closed() {
            return closed.get();
        }
    }

    private static final class BlockingInputStream extends CloseAwareInputStream {

        private BlockingInputStream() {
            super(new byte[0]);
        }

        @Override
        public synchronized int read() throws IOException {
            while (!closed()) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
            }
            return -1;
        }

        @Override
        public synchronized void close() throws IOException {
            super.close();
            notifyAll();
        }
    }

    private static final class CloseAwareOutputStream extends ByteArrayOutputStream {

        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }

        private boolean closed() {
            return closed.get();
        }
    }
}

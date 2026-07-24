/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class ProcessIoResourcesTestSupport {

    static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static TrackingInputStream failingInput(IOException failure) {
        return new TrackingInputStream() {
            @Override
            public void close() throws IOException {
                closeCalls.incrementAndGet();
                throw failure;
            }
        };
    }

    static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    static class TrackingProcess extends Process {

        final TrackingOutputStream stdin = new TrackingOutputStream();
        final TrackingInputStream stdout;
        final TrackingInputStream stderr;
        final AtomicInteger stdinGets = new AtomicInteger();
        final AtomicInteger stdoutGets = new AtomicInteger();
        final AtomicInteger stderrGets = new AtomicInteger();
        private final int failedOrdinal;
        private final Throwable getterFailure;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        TrackingProcess() {
            this(new TrackingInputStream(), new TrackingInputStream());
        }

        TrackingProcess(TrackingInputStream stdout, TrackingInputStream stderr) {
            this(0, null, stdout, stderr);
        }

        TrackingProcess(int failedOrdinal, Throwable getterFailure) {
            this(failedOrdinal, getterFailure, new TrackingInputStream(), new TrackingInputStream());
        }

        private TrackingProcess(
                int failedOrdinal, Throwable getterFailure, TrackingInputStream stdout, TrackingInputStream stderr) {
            this.failedOrdinal = failedOrdinal;
            this.getterFailure = getterFailure;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            failGetter(stdinGets.incrementAndGet());
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            failGetter(stdoutGets.incrementAndGet() + 1);
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            failGetter(stderrGets.incrementAndGet() + 2);
            return stderr;
        }

        private void failGetter(int ordinal) {
            if (ordinal != failedOrdinal) {
                return;
            }
            if (getterFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) getterFailure;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("alive");
            }
            return 143;
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
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    static final class TrackingOutputStream extends OutputStream {

        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    static class TrackingInputStream extends InputStream {

        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
        }
    }
}

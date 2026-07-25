/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

final class PooledWorkerPhysicalCleanupTestSupport {

    static final Duration CLOSE_TIMEOUT = Duration.ofMillis(40);

    private PooledWorkerPhysicalCleanupTestSupport() {}

    static DefaultSession openSession(Process process, BoundedCloseDispatcher dispatcher) {
        return openSession(process, dispatcher, ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO));
    }

    static DefaultSession openSession(
            Process process, BoundedCloseDispatcher dispatcher, ShutdownPolicy shutdownPolicy) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                shutdownPolicy,
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "pool-output-cleanup-test", CommandEcho.empty()),
                () -> {},
                dispatcher,
                io.github.ulviar.procwright.internal.Threading::start);
    }

    static ProtocolAdapter<String, String> noOpAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {}

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
    }

    static void assertNoDispatcherLeak(BoundedCloseDispatcher dispatcher) {
        assertNoDispatcherLeak(dispatcher, 3);
    }

    static void assertNoDispatcherLeak(BoundedCloseDispatcher dispatcher, int capacity) {
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
        dispatcher.reserve(capacity).release();
    }

    static final class BlockingReadFailingCloseInputStream extends InputStream {

        private final Object operationLock = new Object();
        private final Throwable closeFailure;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch readFinished = new CountDownLatch(1);
        private final CountDownLatch closeInvoked = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);

        BlockingReadFailingCloseInputStream(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            synchronized (operationLock) {
                readStarted.countDown();
                try {
                    awaitUninterruptibly(releaseRead);
                    return -1;
                } finally {
                    readFinished.countDown();
                }
            }
        }

        @Override
        public void close() throws IOException {
            closeInvoked.countDown();
            try {
                synchronized (operationLock) {
                    if (closeFailure != null) {
                        throwUnchecked(closeFailure);
                    }
                }
            } finally {
                closeFinished.countDown();
            }
        }

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        boolean awaitReadFinished() throws InterruptedException {
            return readFinished.await(1, TimeUnit.SECONDS);
        }

        boolean awaitCloseInvoked() throws InterruptedException {
            return closeInvoked.await(1, TimeUnit.SECONDS);
        }

        boolean awaitCloseFinished(Duration timeout) throws InterruptedException {
            return closeFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        boolean closeFinished() {
            return closeFinished.getCount() == 0;
        }

        void releaseRead() {
            releaseRead.countDown();
        }
    }

    static final class TrackingInputStream extends InputStream {

        private final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeFinished.countDown();
        }

        boolean awaitCloseFinished(Duration timeout) throws InterruptedException {
            return closeFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    static final class ImmediateFailingCloseInputStream extends InputStream {

        private final Throwable closeFailure;

        ImmediateFailingCloseInputStream(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            throwUnchecked(closeFailure);
        }
    }

    static final class TrackingOutputStream extends OutputStream {

        private final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeFinished.countDown();
        }

        boolean awaitCloseFinished(Duration timeout) throws InterruptedException {
            return closeFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    static class TestProcess extends Process {

        private final OutputStream stdin;
        private final InputStream stdout;
        private final InputStream stderr;
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicReference<Integer> exitCode = new AtomicReference<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountDownLatch destroyInvoked = new CountDownLatch(1);

        TestProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode.get();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            Integer value = exitCode.get();
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {
            recordDestroyInvoked();
            complete(143);
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
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        protected final void recordDestroyInvoked() {
            destroyInvoked.countDown();
        }

        final boolean awaitDestroyInvoked() throws InterruptedException {
            return destroyInvoked.await(1, TimeUnit.SECONDS);
        }

        protected void complete(int exitCode) {
            if (this.exitCode.compareAndSet(null, exitCode)) {
                alive.set(false);
                exited.countDown();
            }
        }
    }

    static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void throwUnchecked(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("unsupported test failure", failure);
    }
}

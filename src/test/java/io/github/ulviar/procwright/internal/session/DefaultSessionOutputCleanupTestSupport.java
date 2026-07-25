/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class DefaultSessionOutputCleanupTestSupport {

    protected static DefaultSession openSession(Process process, BoundedCloseDispatcher dispatcher) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "output-cleanup-test", CommandEcho.empty()),
                () -> {},
                dispatcher,
                io.github.ulviar.procwright.internal.Threading::start);
    }

    protected static final class TrackingInputStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }

        protected boolean awaitClosed() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        protected int closeCalls() {
            return closeCalls.get();
        }
    }

    protected static final class TrackingOutputStream extends OutputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }

        protected boolean awaitClosed() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        protected int closeCalls() {
            return closeCalls.get();
        }
    }

    protected static final class MatrixProcess extends Process {

        private final OutputStream stdin;
        private final InputStream stdout;
        private final InputStream stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicInteger stdinGetterCalls = new AtomicInteger();
        private final AtomicInteger stdoutGetterCalls = new AtomicInteger();
        private final AtomicInteger stderrGetterCalls = new AtomicInteger();

        protected MatrixProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            stdinGetterCalls.incrementAndGet();
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            stdoutGetterCalls.incrementAndGet();
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrGetterCalls.incrementAndGet();
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException ignored) {
                return false;
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {
            complete(143);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        protected void complete(int exitCode) {
            exit.complete(exitCode);
        }

        protected int stdinGetterCalls() {
            return stdinGetterCalls.get();
        }

        protected int stdoutGetterCalls() {
            return stdoutGetterCalls.get();
        }

        protected int stderrGetterCalls() {
            return stderrGetterCalls.get();
        }
    }
}

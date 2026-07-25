/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class DefaultLineSessionTestSupport {

    static LineSessionSettings options(Charset charset) {
        return LineSessionSettings.defaults().withCharsetPolicy(CharsetPolicy.report(charset));
    }

    static DefaultSession session(Process process) {
        return SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8);
    }

    static DefaultSession session(Process process, BoundedCloseDispatcher closeDispatcher) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "line-cleanup-test", CommandEcho.empty()),
                () -> {},
                closeDispatcher,
                DefaultSession.WatcherStarter.threading());
    }

    static final class ReplyingOutputStream extends OutputStream {

        static final byte[] RESPONSE = "ok\n".getBytes(StandardCharsets.UTF_8);

        final ResponseInputStream responses;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger writeCalls = new AtomicInteger();

        ReplyingOutputStream(ResponseInputStream responses) {
            this.responses = responses;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            written.write(bytes, offset, length);
            responses.publish(RESPONSE);
        }

        @Override
        public void close() {
            closed.countDown();
        }

        int writeCalls() {
            return writeCalls.get();
        }

        synchronized String writtenText() {
            return written.toString(StandardCharsets.UTF_8);
        }

        boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }
    }

    static final class ResponseInputStream extends InputStream {

        final ArrayDeque<Byte> bytes = new ArrayDeque<>();
        boolean closed;

        @Override
        public synchronized int read() {
            awaitData();
            return bytes.isEmpty() ? -1 : Byte.toUnsignedInt(bytes.removeFirst());
        }

        @Override
        public synchronized int read(byte[] destination, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, destination.length);
            if (length == 0) {
                return 0;
            }
            awaitData();
            if (bytes.isEmpty()) {
                return -1;
            }
            int count = Math.min(length, bytes.size());
            for (int index = 0; index < count; index++) {
                destination[offset + index] = bytes.removeFirst();
            }
            return count;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        synchronized void publish(byte[] response) {
            for (byte value : response) {
                bytes.addLast(value);
            }
            notifyAll();
        }

        void awaitData() {
            boolean interrupted = false;
            while (bytes.isEmpty() && !closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class BlockingUntilClosedInputStream extends InputStream {

        final CountDownLatch closed = new CountDownLatch(1);

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
            closed.countDown();
        }
    }

    static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
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

    static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static final class ControllableProcess extends Process {

        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicBoolean alive = new AtomicBoolean(true);
        final OutputStream stdin;
        final InputStream stdout;
        final InputStream stderr;

        ControllableProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        void complete(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
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
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException exception) {
                return false;
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer exitCode = exit.getNow(null);
            if (exitCode == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            alive.set(false);
            exit.complete(143);
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
    }
}

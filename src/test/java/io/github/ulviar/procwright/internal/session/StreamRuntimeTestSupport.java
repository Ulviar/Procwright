/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchMode;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class StreamRuntimeTestSupport {

    protected static DefaultSession session(Process process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    protected static StreamExecutionPlan plan() {
        return plan(StandardCharsets.UTF_8, 1024);
    }

    protected static StreamExecutionPlan plan(Charset charset, int diagnosticLimit) {
        return plan(charset, diagnosticLimit, chunk -> {});
    }

    protected static StreamExecutionPlan plan(Charset charset, int diagnosticLimit, StreamListener listener) {
        LaunchPlan launchPlan = new LaunchPlan(
                LaunchMode.DIRECT,
                List.of("stub"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.DISABLED);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                charset,
                PtyProvider.unavailable(),
                TerminalSize.defaults());
        return new StreamExecutionPlan(
                sessionPlan, Duration.ZERO, diagnosticLimit, listener, DiagnosticsSettings.disabled());
    }

    protected static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "stream-runtime-test", CommandEcho.empty());
    }

    protected static final class ControllableProcess extends Process {

        protected final CompletableFuture<Integer> exit = new CompletableFuture<>();
        protected final AtomicBoolean alive;
        protected final CountDownLatch destroyed = new CountDownLatch(1);
        protected final CountDownLatch waitEntered = new CountDownLatch(1);
        protected final CountDownLatch waitFailureRelease = new CountDownLatch(1);
        protected final InputStream stdout;
        protected final InputStream stderr;
        protected final Throwable waitFailure;
        protected final OutputStream stdin;

        protected ControllableProcess() {
            this(InputStream.nullInputStream(), InputStream.nullInputStream(), null);
        }

        protected ControllableProcess(InputStream stdout, InputStream stderr, Throwable waitFailure) {
            this(stdout, stderr, waitFailure, OutputStream.nullOutputStream());
        }

        protected ControllableProcess(
                InputStream stdout, InputStream stderr, Throwable waitFailure, OutputStream stdin) {
            this(stdout, stderr, waitFailure, stdin, new AtomicBoolean(true));
        }

        protected ControllableProcess(
                InputStream stdout,
                InputStream stderr,
                Throwable waitFailure,
                OutputStream stdin,
                AtomicBoolean alive) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.waitFailure = waitFailure;
            this.stdin = stdin;
            this.alive = alive;
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
            throwWaitFailure();
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            throwWaitFailure();
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
            destroyed.countDown();
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

        protected boolean awaitDestroyed() throws InterruptedException {
            return destroyed.await(1, TimeUnit.SECONDS);
        }

        protected boolean awaitWaitFailure() throws InterruptedException {
            return waitEntered.await(1, TimeUnit.SECONDS);
        }

        protected void releaseWaitFailure() {
            waitFailureRelease.countDown();
        }

        protected void throwWaitFailure() throws InterruptedException {
            if (waitFailure == null) {
                return;
            }
            waitEntered.countDown();
            waitFailureRelease.await();
            if (waitFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) waitFailure;
        }
    }

    protected static final class CloseTrackingInputStream extends InputStream {

        protected final byte[] bytes;
        protected final AtomicInteger closes = new AtomicInteger();
        protected final CountDownLatch closed = new CountDownLatch(1);
        protected int index;

        protected CloseTrackingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            return index < bytes.length ? Byte.toUnsignedInt(bytes[index++]) : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int remaining = bytes.length - index;
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            System.arraycopy(bytes, index, buffer, offset, count);
            index += count;
            return count;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        protected int closeCalls() {
            return closes.get();
        }

        protected boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    protected static void awaitUninterruptibly(CountDownLatch latch) {
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

    protected static CharsetDecoder passthroughDecoder(Charset charset) {
        return new CharsetDecoder(charset, 1, 1) {
            @Override
            protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                while (input.hasRemaining() && output.hasRemaining()) {
                    output.put((char) Byte.toUnsignedInt(input.get()));
                }
                return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
            }
        };
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

abstract class ProtocolSessionContractSupport {

    static ProtocolSessionSettings options(Charset charset) {
        return ProtocolSessionSettings.defaults().withCharsetPolicy(CharsetPolicy.report(charset));
    }

    static ProtocolAdapter<String, String> noOpAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
    }

    static <I, O> DefaultProtocolSession<I, O> protocolSession(
            Process process, ProtocolAdapter<I, O> adapter, ProtocolSessionSettings settings) {
        return protocolSession(
                process, adapter, settings, DefaultProtocolSession.Dependencies.defaults(), ignored -> {});
    }

    static <I, O> DefaultProtocolSession<I, O> protocolSession(
            Process process,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings settings,
            DefaultProtocolSession.Dependencies dependencies) {
        return protocolSession(process, adapter, settings, dependencies, ignored -> {});
    }

    static <I, O> DefaultProtocolSession<I, O> protocolSession(
            Process process,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings settings,
            Consumer<? super DefaultSession> sessionObserver) {
        return protocolSession(
                process, adapter, settings, DefaultProtocolSession.Dependencies.defaults(), sessionObserver);
    }

    private static <I, O> DefaultProtocolSession<I, O> protocolSession(
            Process process,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings settings,
            DefaultProtocolSession.Dependencies dependencies,
            Consumer<? super DefaultSession> sessionObserver) {
        return SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "protocol-test", CommandEcho.empty()),
                SessionOutputMode.PROTOCOL,
                session -> {
                    DefaultProtocolSession<I, O> protocol =
                            new DefaultProtocolSession<>(session, adapter, settings, dependencies);
                    sessionObserver.accept(session);
                    return protocol;
                });
    }

    static <I, O> DefaultProtocolSession<I, O> protocolSession(
            Process process,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings settings,
            DefaultProtocolSession.Dependencies dependencies,
            DiagnosticEmitter diagnostics,
            BoundedCloseDispatcher closeDispatcher,
            DefaultSession.WatcherStarter watcherStarter) {
        return SessionTestFixtures.openHandle(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics,
                SessionOutputMode.PROTOCOL,
                session -> new DefaultProtocolSession<>(session, adapter, settings, dependencies),
                closeDispatcher,
                watcherStarter);
    }

    static <I, O> DefaultProtocolSession<I, O> protocolSession(
            Process process,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings settings,
            BoundedCloseDispatcher closeDispatcher) {
        return protocolSession(
                process,
                adapter,
                settings,
                DefaultProtocolSession.Dependencies.defaults(),
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "protocol-cleanup-test", CommandEcho.empty()),
                closeDispatcher,
                DefaultSession.WatcherStarter.threading());
    }

    static CharsetDecoder passthroughDecoder(Charset charset) {
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

    static final class CountingOutputStream extends OutputStream {

        final CountDownLatch firstWrite = new CountDownLatch(1);
        final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public void write(int value) {
            writeCalls.incrementAndGet();
            firstWrite.countDown();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            firstWrite.countDown();
        }

        boolean awaitWrite() throws InterruptedException {
            return firstWrite.await(1, TimeUnit.SECONDS);
        }

        int writeCalls() {
            return writeCalls.get();
        }
    }

    static final class GatedEofInputStream extends InputStream {

        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseEof = new CountDownLatch(1);

        @Override
        public int read() {
            readEntered.countDown();
            awaitUninterruptibly(releaseEof);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseEof() {
            releaseEof.countDown();
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

    static final class ControllableProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final OutputStream stdin;
        final InputStream stdout;
        final InputStream stderr;
        final AtomicInteger exitValueCalls = new AtomicInteger();
        volatile CountDownLatch livenessQueryEntered;
        volatile CountDownLatch releaseLivenessQuery;

        ControllableProcess() {
            this(OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        }

        ControllableProcess(OutputStream stdin, InputStream stdout, InputStream stderr) {
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
            exitValueCalls.incrementAndGet();
            Integer exitCode = exit.getNow(null);
            if (exitCode == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            exitNaturally(143);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            CountDownLatch entered = livenessQueryEntered;
            CountDownLatch release = releaseLivenessQuery;
            if (entered != null && release != null) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
            return !exit.isDone();
        }

        void blockLivenessQueries() {
            livenessQueryEntered = new CountDownLatch(1);
            releaseLivenessQuery = new CountDownLatch(1);
        }

        boolean awaitLivenessQuery() throws InterruptedException {
            CountDownLatch entered = livenessQueryEntered;
            return entered != null && entered.await(1, TimeUnit.SECONDS);
        }

        void releaseLivenessQueries() {
            CountDownLatch release = releaseLivenessQuery;
            if (release != null) {
                release.countDown();
            }
        }

        int exitValueCalls() {
            return exitValueCalls.get();
        }

        void exitNaturally(int exitCode) {
            exit.complete(exitCode);
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
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

    static final class BlockingPhysicalCloseInputStream extends InputStream {

        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeEntered.countDown();
            try {
                awaitUninterruptibly(releaseClose);
            } finally {
                closeFinished.countDown();
            }
        }
    }
}

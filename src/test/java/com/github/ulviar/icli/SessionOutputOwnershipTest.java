package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class SessionOutputOwnershipTest {

    @Test
    void helperClaimFailsAfterPublicOutputReadSelectsRawMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[] {'x'}))) {
            assertEquals('x', session.stdout().read());

            assertThrows(IllegalStateException.class, session::expect);
        }
    }

    @Test
    void helperClaimFailsAfterPublicOutputCloseSelectsRawMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[0]))) {
            session.stdout().close();

            assertThrows(IllegalStateException.class, session::expect);
        }
    }

    @Test
    void helperClaimFailsWhilePublicOutputReadIsInFlight() throws Exception {
        BlockingInputStream stdout = new BlockingInputStream();
        try (Session session = sessionWith(stdout)) {
            CompletableFuture<Integer> read = new CompletableFuture<>();
            Thread.ofVirtual().name("icli-test-blocking-public-output-read").start(() -> {
                try {
                    read.complete(session.stdout().read());
                } catch (Throwable throwable) {
                    read.completeExceptionally(throwable);
                }
            });
            stdout.awaitReadStarted();

            assertThrows(IllegalStateException.class, session::expect);

            stdout.release();
            assertEquals(-1, read.get(2, TimeUnit.SECONDS));
        }
    }

    private static Session sessionWith(InputStream stdout) {
        return new Session(
                new StubProcess(stdout),
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                Diagnostics.of(DiagnosticsOptions.defaults(), "session-test", CommandEcho.empty()));
    }

    private static final class BlockingInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while blocking test input stream", exception);
            }
            if (closed.get()) {
                throw new IOException("Stream closed");
            }
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
            release.countDown();
        }

        private void awaitReadStarted() throws InterruptedException {
            if (!readStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Public output read did not start");
            }
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class StubProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final OutputStream stdin = OutputStream.nullOutputStream();
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private StubProcess(InputStream stdout) {
            this.stdout = stdout;
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
                throw new IllegalStateException("Stub process failed", exception);
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
                throw new IllegalStateException("Stub process failed", exception);
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("Process is still alive");
            }
            return value;
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
        public long pid() {
            return 1L;
        }
    }
}

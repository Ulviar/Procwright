/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.awaitIgnoringInterrupts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.Threading;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionStdinCloseContentionTest {

    @Test
    void closeStdinDoesNotWaitForRawCloseContendedByAnActiveWrite() throws Exception {
        WriteContendedCloseOutputStream stdin = new WriteContendedCloseOutputStream();
        ControllableProcess process = new ControllableProcess(stdin);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        CompletableFuture<Throwable> writerOutcome = new CompletableFuture<>();
        Thread writer = new Thread(() -> {
            try {
                session.send("payload");
                writerOutcome.complete(null);
            } catch (Throwable failure) {
                writerOutcome.complete(failure);
            }
        });
        writer.setDaemon(true);
        writer.start();
        try {
            assertTrue(stdin.awaitWriteStarted(Duration.ofSeconds(1)));

            assertTimeoutPreemptively(Duration.ofSeconds(1), session::closeStdin);

            assertFalse(
                    stdin.closeStarted(),
                    "the raw close cannot acquire the delegate monitor while the active write owns it");
            stdin.releaseWrite();
            SessionStdinClosedException writerFailure =
                    assertInstanceOf(SessionStdinClosedException.class, writerOutcome.get(1, TimeUnit.SECONDS));
            assertEquals("Session stdin is closed", writerFailure.getMessage());
            assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));
        } finally {
            stdin.releaseWrite();
            writer.join(TimeUnit.SECONDS.toMillis(1));
            session.close();
        }

        assertFalse(writer.isAlive());
    }

    @Test
    void closeStartFailureCompletesBoundedTerminalCleanupBeforeReturning() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("cannot start stdin close");
        BlockingDestroyProcess process = new BlockingDestroyProcess();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, (name, task) -> {
            throw startFailure;
        });
        DefaultSession session = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()),
                () -> {},
                dispatcher,
                Threading::start);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread closeCaller = new Thread(() -> {
            try {
                session.closeStdin();
            } catch (Throwable failure) {
                observed.set(failure);
            }
        });
        closeCaller.start();
        try {
            assertTrue(process.destroyStarted.await(1, TimeUnit.SECONDS));
            assertTrue(closeCaller.isAlive(), "closeStdin returned before terminal cleanup completed");
        } finally {
            process.releaseDestroy.countDown();
            closeCaller.join(1_000);
        }

        assertFalse(closeCaller.isAlive());
        assertSame(startFailure, observed.get());
    }

    private static final class WriteContendedCloseOutputStream extends OutputStream {

        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);
        private final CountDownLatch closeStarted = new CountDownLatch(1);

        @Override
        public synchronized void write(int value) {
            blockWrite();
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length > 0) {
                blockWrite();
            }
        }

        @Override
        public synchronized void close() {
            closeStarted.countDown();
        }

        private void blockWrite() {
            writeStarted.countDown();
            awaitIgnoringInterrupts(releaseWrite);
        }

        private boolean awaitWriteStarted(Duration timeout) throws InterruptedException {
            return writeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void releaseWrite() {
            releaseWrite.countDown();
        }

        private boolean closeStarted() {
            return closeStarted.getCount() == 0;
        }

        private boolean awaitCloseStarted(Duration timeout) throws InterruptedException {
            return closeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static final class BlockingDestroyProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final CountDownLatch destroyStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDestroy = new CountDownLatch(1);

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
            return exit.join();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException timeoutFailure) {
                return false;
            } catch (java.util.concurrent.ExecutionException impossible) {
                throw new AssertionError(impossible);
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return value;
        }

        @Override
        public void destroy() {
            destroyStarted.countDown();
            awaitIgnoringInterrupts(releaseDestroy);
            exit.complete(143);
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
    }
}

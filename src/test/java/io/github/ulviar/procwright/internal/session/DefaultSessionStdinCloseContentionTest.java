/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DefaultSessionStdinCloseContentionTest extends DefaultSessionLifecycleTestSupport {

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
}

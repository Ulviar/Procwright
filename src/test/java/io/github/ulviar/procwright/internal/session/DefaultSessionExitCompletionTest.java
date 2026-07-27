/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.awaitIgnoringInterrupts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionExitCompletionTest {

    @Test
    void helperStopClaimsItsOutcomeBeforeRunningTheStopAction() throws Exception {
        DefaultStreamSessionTestSupport.ControllableProcess process =
                new DefaultStreamSessionTestSupport.ControllableProcess(
                        InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        CountDownLatch stopActionEntered = new CountDownLatch(1);
        CountDownLatch releaseStopAction = new CountDownLatch(1);
        CountDownLatch processOutcomeObserved = new CountDownLatch(1);
        AtomicReference<SessionTerminal.SuccessKind> successKind = new AtomicReference<>();
        session.observeTermination((ignored, failure) -> processOutcomeObserved.countDown());
        session.observePublicOutcome(outcome -> successKind.set(outcome.successKind()));
        Thread stopper = new Thread(
                () -> session.closeFromHelper(false, () -> {
                    stopActionEntered.countDown();
                    awaitIgnoringInterrupts(releaseStopAction);
                }),
                "helper-stop-claim-test");
        stopper.setDaemon(true);
        try {
            stopper.start();
            assertTrue(stopActionEntered.await(1, TimeUnit.SECONDS));

            process.complete(0);

            assertTrue(processOutcomeObserved.await(1, TimeUnit.SECONDS));
            assertFalse(session.onExit().isDone());
            releaseStopAction.countDown();
            stopper.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(stopper.isAlive());
            session.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(SessionTerminal.SuccessKind.CLOSED, successKind.get());
        } finally {
            releaseStopAction.countDown();
            stopper.join(TimeUnit.SECONDS.toMillis(1));
            session.close();
        }
    }

    @Test
    void publicExitFutureRemainsADefensiveCopy() throws Exception {
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        CompletableFuture<SessionExit> publicView = session.onExit();

        assertTrue(publicView.complete(new SessionExit(OptionalInt.of(99), false)));
        assertFalse(session.onExit().isDone());

        session.close();

        assertEquals(143, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
    }

    @Test
    void naturalExitLeavesUnreadRawOutputAvailableToTheCaller() throws Exception {
        CloseSensitiveInputStream stdout = new CloseSensitiveInputStream("final output");
        CompletedProcess process = new CompletedProcess(stdout);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));

        assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertEquals("final output", new String(session.stdout().readAllBytes(), StandardCharsets.UTF_8));
        assertFalse(stdout.closed.get());

        session.close();
    }

    @Test
    void naturalExitIgnoresPhysicalStdinCleanupFailure() throws Exception {
        IOException cleanupFailure = new IOException("stdin cleanup failed");
        CompletedProcess process = new CompletedProcess(
                new CloseSensitiveInputStream("final output"), new FailingCloseOutputStream(cleanupFailure));
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));

        assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertEquals("final output", new String(session.stdout().readAllBytes(), StandardCharsets.UTF_8));

        session.close();
    }

    private static final class CompletedProcess extends Process {

        private final InputStream stdout;
        private final OutputStream stdin;

        private CompletedProcess(InputStream stdout) {
            this(stdout, OutputStream.nullOutputStream());
        }

        private CompletedProcess(InputStream stdout, OutputStream stdin) {
            this.stdout = stdout;
            this.stdin = stdin;
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
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class FailingCloseOutputStream extends OutputStream {

        private final IOException failure;

        private FailingCloseOutputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() throws IOException {
            throw failure;
        }
    }

    private static final class CloseSensitiveInputStream extends InputStream {

        private final ByteArrayInputStream delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseSensitiveInputStream(String text) {
            delegate = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() throws IOException {
            ensureOpen();
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            ensureOpen();
            return delegate.read(bytes, offset, length);
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private void ensureOpen() throws IOException {
            if (closed.get()) {
                throw new IOException("stream already closed");
            }
        }
    }
}

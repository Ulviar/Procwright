/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionDescendantCleanupFailureTest {

    @Test
    void cyclicCleanupFailureCannotHangCloseAndPreservesPrimaryIdentity() throws Exception {
        IllegalStateException primary = new IllegalStateException("cyclic cleanup failure");
        IllegalArgumentException cycle = new IllegalArgumentException("cycle");
        primary.initCause(cycle);
        cycle.initCause(primary);
        FailingDescendantProcess process = new FailingDescendantProcess(primary);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        assertTrue(process.awaitDescendantObservation());

        CommandExecutionException thrown = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> assertThrows(CommandExecutionException.class, session::close));
        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, thrown.reason());
        assertSame(primary, thrown.getCause());
        assertSame(thrown, exitFailure.getCause());
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(1, process.rootDestroyCalls());
        assertTrue(process.descendant().gracefulDestroyCalls() >= 1);
        assertTrue(process.descendant().forceDestroyCalls() >= 1);
        assertFalse(process.isAlive());
    }

    @Test
    void closeErrorCompletesExitFutureAndPreservesPrimaryError() throws Exception {
        AssertionError closeError = new AssertionError("descendant close failed");
        FailingDescendantProcess process = new FailingDescendantProcess(closeError);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        assertTrue(process.awaitDescendantObservation());

        AssertionError thrown = assertThrows(AssertionError.class, session::close);
        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

        assertSame(closeError, thrown);
        assertSame(closeError, exitFailure.getCause());
    }

    @Test
    void closeDoesNotWaitForRawStdinCloseBlockedByAnotherOperation() throws Exception {
        AssertionError closeError = new AssertionError("descendant close failed");
        BlockingCloseOutputStream stdin = new BlockingCloseOutputStream();
        FailingDescendantProcess process = new FailingDescendantProcess(closeError, stdin);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        assertTrue(process.awaitDescendantObservation());

        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                session.close();
            } catch (Throwable failure) {
                observedFailure.set(failure);
            }
        });
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(500);

            assertTrue(!closer.isAlive(), "Session.close() must not wait for a blocked raw stdin close");
            assertSame(closeError, observedFailure.get());
        } finally {
            stdin.releaseClose();
            closer.join(1_000);
        }
    }

    @Test
    void losingPathsDoNotRepeatThePrimaryOwnersCleanup() throws Exception {
        AssertionError cleanupFailure = new AssertionError("descendant cleanup failed");
        FailingDescendantProcess process = new FailingDescendantProcess(cleanupFailure);
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.blockOnceOn(
                DiagnosticsSettings.disabled().withListener(ignored -> {}),
                "session-test",
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                ownerEntered,
                releaseOwner);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics);
        assertTrue(process.awaitDescendantObservation());
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try {
                session.close();
            } catch (Throwable failure) {
                ownerFailure.set(failure);
            }
        });
        owner.start();
        try {
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS));

            assertFalse(session.terminateAfterHelperFailure(new IllegalStateException("late helper failure")));
            assertTimeoutPreemptively(Duration.ofSeconds(1), session::close);
            assertEquals(0, process.rootDestroyCalls());
        } finally {
            releaseOwner.countDown();
            owner.join(1_000);
        }

        assertFalse(owner.isAlive());
        assertSame(cleanupFailure, ownerFailure.get());
        ExecutionException terminal =
                assertThrows(ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));
        assertSame(cleanupFailure, terminal.getCause());
    }

    private static final class FailingDescendantProcess extends Process {

        private final FailingProcessHandle descendant;
        private final CountDownLatch descendantObserved = new CountDownLatch(1);
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger rootDestroyCalls = new AtomicInteger();
        private final OutputStream stdin;

        private FailingDescendantProcess(Throwable closeError) {
            this(closeError, OutputStream.nullOutputStream());
        }

        private FailingDescendantProcess(Throwable closeError, OutputStream stdin) {
            this.descendant = new FailingProcessHandle(closeError);
            this.stdin = stdin;
        }

        private boolean awaitDescendantObservation() throws InterruptedException {
            return descendantObserved.await(1, TimeUnit.SECONDS);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
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
            rootDestroyCalls.incrementAndGet();
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
            return Long.MAX_VALUE;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            descendantObserved.countDown();
            return Stream.of(descendant);
        }

        private FailingProcessHandle descendant() {
            return descendant;
        }

        private int rootDestroyCalls() {
            return rootDestroyCalls.get();
        }
    }

    private static final class BlockingCloseOutputStream extends OutputStream {

        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    allowClose.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void releaseClose() {
            allowClose.countDown();
        }

        private boolean awaitCloseStarted(Duration timeout) throws InterruptedException {
            return closeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static final class FailingProcessHandle implements ProcessHandle {

        private final Throwable closeError;
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        private FailingProcessHandle(Throwable closeError) {
            this.closeError = closeError;
        }

        @Override
        public long pid() {
            return Long.MAX_VALUE - 1;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return new CompletableFuture<>();
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            gracefulDestroyCalls.incrementAndGet();
            throwUnchecked(closeError);
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            throwUnchecked(closeError);
            return false;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }

        private int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("test failure must be unchecked", failure);
    }
}

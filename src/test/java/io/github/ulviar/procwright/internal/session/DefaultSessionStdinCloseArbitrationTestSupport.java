/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class DefaultSessionStdinCloseArbitrationTestSupport extends DefaultSessionLifecycleTestSupport {

    static void assertAsynchronousStdinCloseFailure(Throwable closeFailure) throws Exception {
        ControlledFailingCloseOutputStream stdin = new ControlledFailingCloseOutputStream(closeFailure);
        CloseFailureProcess process = new CloseFailureProcess(stdin);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch processFailurePublished = new CountDownLatch(1);
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        processFailurePublished.countDown();
                    }
                }),
                "session-test",
                CommandEcho.empty());
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics);
        assertTrue(process.awaitDescendantObservation());
        try {
            session.closeStdin();
            assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));
            assertFalse(session.onExit().isDone(), "closeStdin must return before the raw close operation completes");

            stdin.releaseClose();
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

            assertSame(closeFailure, exitFailure.getCause());
            assertFalse(process.isAlive());
            assertFalse(process.descendant().isAlive());
            assertTrue(processFailurePublished.await(1, TimeUnit.SECONDS));

            assertEquals(1, process.rootGracefulDestroyCalls());
            assertEquals(1, process.descendant().gracefulDestroyCalls());
            assertEquals(0, process.descendant().forceDestroyCalls());
            assertEquals(1, failureShutdownCount(events));
            assertEquals(1, terminalEventCount(events, DiagnosticEventType.PROCESS_FAILED));
            assertEquals(0, terminalEventCount(events, DiagnosticEventType.PROCESS_EXITED));
            assertTrue(events.stream()
                    .anyMatch(event -> event.type() == DiagnosticEventType.PROCESS_FAILED
                            && closeFailure
                                    .getClass()
                                    .getName()
                                    .equals(event.attributes().get("error"))));
        } finally {
            stdin.releaseClose();
            session.close();
        }
    }

    static final class CloseFailureProcess extends Process {

        private final ControlledFailingCloseOutputStream stdin;
        private final CountDownLatch descendantObserved = new CountDownLatch(1);
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final TrackingProcessHandle root =
                new TrackingProcessHandle(Long.MAX_VALUE - 3, alive, () -> exit.complete(143));
        private final TrackingProcessHandle descendant =
                new TrackingProcessHandle(Long.MAX_VALUE - 2, new AtomicBoolean(true), () -> {});

        CloseFailureProcess(ControlledFailingCloseOutputStream stdin) {
            this.stdin = stdin;
        }

        boolean awaitDescendantObservation() throws InterruptedException {
            return descendantObserved.await(1, TimeUnit.SECONDS);
        }

        TrackingProcessHandle descendant() {
            return descendant;
        }

        void completeNaturally(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        int rootGracefulDestroyCalls() {
            return root.gracefulDestroyCalls();
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
            root.destroy();
        }

        @Override
        public Process destroyForcibly() {
            root.destroyForcibly();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return root;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            descendantObserved.countDown();
            return Stream.of(descendant);
        }
    }

    static final class BlockingRootDestroyProcess extends Process {

        private final OutputStream stdin;
        private final CountDownLatch destroyStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDestroy = new CountDownLatch(1);
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final TrackingProcessHandle root = new TrackingProcessHandle(Long.MAX_VALUE - 4, alive, () -> {}) {
            @Override
            public boolean destroy() {
                destroyStarted.countDown();
                awaitIgnoringInterrupts(releaseDestroy);
                BlockingRootDestroyProcess.this.alive.set(false);
                exit.complete(143);
                return true;
            }

            @Override
            public boolean destroyForcibly() {
                return destroy();
            }
        };

        BlockingRootDestroyProcess(OutputStream stdin) {
            this.stdin = stdin;
        }

        boolean awaitDestroyStarted() throws InterruptedException {
            return destroyStarted.await(1, TimeUnit.SECONDS);
        }

        void releaseDestroy() {
            releaseDestroy.countDown();
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
            root.destroy();
        }

        @Override
        public Process destroyForcibly() {
            root.destroyForcibly();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return root;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    static class TrackingProcessHandle implements ProcessHandle {

        private final long pid;
        private final AtomicBoolean alive;
        private final Runnable onStop;
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        TrackingProcessHandle(long pid, AtomicBoolean alive, Runnable onStop) {
            this.pid = pid;
            this.alive = alive;
            this.onStop = onStop;
        }

        @Override
        public long pid() {
            return pid;
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
            alive.set(false);
            onStop.run();
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            alive.set(false);
            onStop.run();
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    static final class ControlledFailingCloseOutputStream extends OutputStream {

        private final Throwable failure;
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        ControlledFailingCloseOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() throws IOException {
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unsupported close failure", failure);
        }

        boolean awaitCloseStarted(Duration timeout) throws InterruptedException {
            return closeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void releaseClose() {
            release.countDown();
        }
    }

    static final class WriteContendedCloseOutputStream extends OutputStream {

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

        boolean awaitWriteStarted(Duration timeout) throws InterruptedException {
            return writeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void releaseWrite() {
            releaseWrite.countDown();
        }

        boolean closeStarted() {
            return closeStarted.getCount() == 0;
        }

        boolean awaitCloseStarted(Duration timeout) throws InterruptedException {
            return closeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }
}

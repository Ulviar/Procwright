/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class SessionLifecycleTestFixtures {

    private SessionLifecycleTestFixtures() {}

    static int terminalEventCount(List<DiagnosticEvent> events, DiagnosticEventType type) {
        return Math.toIntExact(
                events.stream().filter(event -> event.type() == type).count());
    }

    static int failureShutdownCount(List<DiagnosticEvent> events) {
        return Math.toIntExact(events.stream()
                .filter(event -> event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED)
                .filter(event -> "failure".equals(event.attributes().get("reason")))
                .count());
    }

    static void awaitIgnoringInterrupts(CountDownLatch latch) {
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

        private final OutputStream stdin;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        ControllableProcess(OutputStream stdin) {
            this.stdin = stdin;
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

    static final class CloseFailureProcess extends Process {

        private final ControlledFailingCloseOutputStream stdin;
        private final CountDownLatch descendantObserved = new CountDownLatch(1);
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final TrackingProcessHandle root;
        private final TrackingProcessHandle descendant;

        CloseFailureProcess(ControlledFailingCloseOutputStream stdin) {
            this(stdin, () -> {});
        }

        CloseFailureProcess(ControlledFailingCloseOutputStream stdin, Runnable beforeDescendantStop) {
            this.stdin = stdin;
            root = new TrackingProcessHandle(Long.MAX_VALUE - 3, alive, () -> exit.complete(143));
            descendant = new TrackingProcessHandle(Long.MAX_VALUE - 2, new AtomicBoolean(true), beforeDescendantStop);
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
}

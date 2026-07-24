/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class DefaultSessionWatcherCleanupTestSupport extends DefaultSessionLifecycleTestSupport {

    static final class FailingDescendantProcess extends Process {

        private final FailingProcessHandle descendant;
        private final CountDownLatch descendantObserved = new CountDownLatch(1);
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger rootDestroyCalls = new AtomicInteger();
        private final OutputStream stdin;

        FailingDescendantProcess(Throwable closeError) {
            this(closeError, OutputStream.nullOutputStream());
        }

        FailingDescendantProcess(Throwable closeError, OutputStream stdin) {
            this.descendant = new FailingProcessHandle(closeError);
            this.stdin = stdin;
        }

        boolean awaitDescendantObservation() throws InterruptedException {
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

        FailingProcessHandle descendant() {
            return descendant;
        }

        int rootDestroyCalls() {
            return rootDestroyCalls.get();
        }
    }

    static final class WatcherFailureProcess extends Process {

        private final IllegalStateException watcherFailure = new IllegalStateException("watcher failed");
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

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
            throw watcherFailure;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            if (!stopped.get()) {
                throw watcherFailure;
            }
            return true;
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            stopped.set(true);
        }

        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            stopped.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (!stopped.get()) {
                throw new SecurityException("root liveness observation is denied");
            }
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        IllegalStateException watcherFailure() {
            return watcherFailure;
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    static final class RepeatedWatcherFailureProcess extends Process {

        private final IllegalStateException failure = new IllegalStateException("repeated watcher failure");
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean stdoutClosed = new AtomicBoolean();
        private final AtomicBoolean stderrClosed = new AtomicBoolean();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return closeTrackingStream(stdoutClosed, true);
        }

        @Override
        public InputStream getErrorStream() {
            return closeTrackingStream(stderrClosed, false);
        }

        private InputStream closeTrackingStream(AtomicBoolean closed, boolean fail) {
            return new InputStream() {
                @Override
                public int read() {
                    return -1;
                }

                @Override
                public void close() {
                    closed.set(true);
                    if (fail) {
                        throw failure;
                    }
                }
            };
        }

        @Override
        public int waitFor() {
            throw failure;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            if (!stopped.get()) {
                throw failure;
            }
            return true;
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            stopped.set(true);
        }

        @Override
        public Process destroyForcibly() {
            stopped.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (!stopped.get()) {
                throw new SecurityException("root liveness observation is denied");
            }
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        IllegalStateException failure() {
            return failure;
        }

        boolean stdoutClosed() {
            return stdoutClosed.get();
        }

        boolean stderrClosed() {
            return stderrClosed.get();
        }
    }

    static final class BlockingCloseOutputStream extends OutputStream {

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

        void releaseClose() {
            allowClose.countDown();
        }

        boolean awaitCloseStarted(Duration timeout) throws InterruptedException {
            return closeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    static final class FailingProcessHandle implements ProcessHandle {

        private final Throwable closeError;
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        FailingProcessHandle(Throwable closeError) {
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

        int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        int forceDestroyCalls() {
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

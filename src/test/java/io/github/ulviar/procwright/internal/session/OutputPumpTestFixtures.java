/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.Threading;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class OutputPumpTestFixtures {
    private OutputPumpTestFixtures() {}

    static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "pump-startup-test", CommandEcho.empty());
    }

    static void drainToEof(InputStream stream, CountDownLatch finished) {
        try (stream) {
            if (stream.read() != -1) {
                throw new AssertionError("test output must be at EOF");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not drain test output", exception);
        } finally {
            finished.countDown();
        }
    }

    static DefaultSession session(Process process) {
        return SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    static DefaultSession session(Process process, BoundedCloseDispatcher closeDispatcher) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                () -> {},
                closeDispatcher,
                Threading::start);
    }

    static void awaitSettlement(CompletableFuture<?> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        future.handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
    }

    static final class CloseTrackingInputStream extends InputStream {

        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        final BoundedCloseDispatcher observedDispatcher;
        final AtomicInteger activeDuringClose = new AtomicInteger(-1);
        volatile String closeThreadName;

        CloseTrackingInputStream() {
            this(null);
        }

        CloseTrackingInputStream(BoundedCloseDispatcher observedDispatcher) {
            this.observedDispatcher = observedDispatcher;
        }

        @Override
        public int read() {
            reads.incrementAndGet();
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            reads.incrementAndGet();
            return -1;
        }

        @Override
        public void close() {
            closeThreadName = Thread.currentThread().getName();
            if (observedDispatcher != null) {
                activeDuringClose.set(observedDispatcher.activeCount());
            }
            closes.incrementAndGet();
            closed.countDown();
        }

        int reads() {
            return reads.get();
        }

        int closeCalls() {
            return closes.get();
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        String closeThreadName() {
            return closeThreadName;
        }

        int activeDuringClose() {
            return activeDuringClose.get();
        }
    }

    static final class ThrowingCloseInputStream extends InputStream {

        final Error closeFailure;
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile Thread closeThread;

        ThrowingCloseInputStream(Error closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeThread = Thread.currentThread();
            closes.incrementAndGet();
            closed.countDown();
            throw closeFailure;
        }

        int closeCalls() {
            return closes.get();
        }

        boolean awaitCloseCompleted() throws InterruptedException {
            if (!closed.await(1, TimeUnit.SECONDS)) {
                return false;
            }
            Thread thread = closeThread;
            if (thread == null) {
                return false;
            }
            thread.join(TimeUnit.SECONDS.toMillis(1));
            return !thread.isAlive();
        }
    }

    static final class GatedThrowingCloseInputStream extends InputStream {

        final Error closeFailure;
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeRelease = new CountDownLatch(1);
        final CountDownLatch closeCompleted = new CountDownLatch(1);

        GatedThrowingCloseInputStream(Error closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
            throw closeFailure;
        }

        boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        void releaseClose() {
            closeRelease.countDown();
        }
    }

    static final class BlockingCloseInputStream extends InputStream {

        final AtomicBoolean processAlive;
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeRelease = new CountDownLatch(1);
        final CountDownLatch closeCompleted = new CountDownLatch(1);
        final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();

        BlockingCloseInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            destroyedBeforeClose.set(!processAlive.get());
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
        }

        boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        void releaseClose() {
            closeRelease.countDown();
        }

        boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        boolean closeCompleted() {
            return closeCompleted.getCount() == 0;
        }

        int closeCalls() {
            return closes.get();
        }
    }

    static final class ControllableProcess extends Process {

        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicBoolean alive;
        final CountDownLatch destroyed = new CountDownLatch(1);
        final InputStream stdout;
        final InputStream stderr;
        volatile Thread isAliveFailureThread;
        volatile RuntimeException isAliveFailure;

        ControllableProcess(InputStream stdout, InputStream stderr) {
            this(stdout, stderr, new AtomicBoolean(true));
        }

        ControllableProcess(InputStream stdout, InputStream stderr, AtomicBoolean alive) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.alive = alive;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
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
            if (Thread.currentThread() == isAliveFailureThread) {
                throw isAliveFailure;
            }
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        boolean awaitDestroyed() throws InterruptedException {
            return destroyed.await(1, TimeUnit.SECONDS);
        }

        void exitNaturally(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        void failIsAliveOnCurrentThread(RuntimeException failure) {
            isAliveFailure = failure;
            isAliveFailureThread = Thread.currentThread();
        }
    }

    static final class FailingPumpStarter implements PumpStarter {

        final int failingOrdinal;
        final Throwable failure;
        final AtomicInteger starts = new AtomicInteger();
        final List<Thread> startedThreads = new ArrayList<>();

        FailingPumpStarter(int failingOrdinal, Throwable failure) {
            this.failingOrdinal = failingOrdinal;
            this.failure = failure;
        }

        @Override
        public Thread start(String namePrefix, Runnable task) {
            if (starts.incrementAndGet() == failingOrdinal) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            Thread thread = Threading.start(namePrefix, task);
            startedThreads.add(thread);
            return thread;
        }

        boolean awaitStartedThreadsStopped() throws InterruptedException {
            for (Thread thread : startedThreads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
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
}

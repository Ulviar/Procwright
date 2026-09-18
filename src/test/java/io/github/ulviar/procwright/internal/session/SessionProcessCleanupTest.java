/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class SessionProcessCleanupTest {

    @Test
    void explicitStopPublishesAStableExitCodeSnapshot() {
        SessionRuntimeTest.TrackingProcess process = new SessionRuntimeTest.TrackingProcess();
        SessionProcessCleanup cleanup =
                new SessionProcessCleanup(process, ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO));

        assertTrue(cleanup.exitCodeSnapshot().isEmpty());

        assertEquals(137, cleanup.stop().orElseThrow());

        for (int attempt = 0; attempt < 10_000; attempt++) {
            assertEquals(137, cleanup.exitCodeSnapshot().orElseThrow());
        }
    }

    @Test
    void naturalExitPublishesAStableExitCodeSnapshot() throws Exception {
        ControlledProcess process = new ControlledProcess();
        SessionProcessCleanup cleanup =
                new SessionProcessCleanup(process, ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO));
        process.complete(23);

        assertEquals(23, cleanup.awaitNaturalExit());
        assertEquals(23, cleanup.exitCodeSnapshot().orElseThrow());
    }

    @Test
    void concurrentCleanupPathsStopTheProcessAtMostOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int attempt = 0; attempt < 250; attempt++) {
                ControlledProcess process = new ControlledProcess();
                SessionProcessCleanup cleanup = new SessionProcessCleanup(
                        process, ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO));
                CountDownLatch start = new CountDownLatch(1);
                Future<?> stop = executor.submit(() -> after(start, cleanup::stop));
                Future<?> force = executor.submit(() -> after(start, () -> {
                    return cleanup.forceAfterFailure();
                }));
                Future<?> preservingStop = executor.submit(() -> after(start, () -> {
                    return cleanup.stopAfterFailure();
                }));

                start.countDown();
                stop.get(1, TimeUnit.SECONDS);
                force.get(1, TimeUnit.SECONDS);
                preservingStop.get(1, TimeUnit.SECONDS);

                assertTrue(!process.isAlive());
                assertEquals(1, process.terminationCalls());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupFailureIsReturnedWithoutTouchingItsThrowableMonitor() throws Exception {
        IllegalStateException cleanupFailure = new IllegalStateException("process cleanup failed");
        FailingTerminationProcess process = new FailingTerminationProcess(cleanupFailure);
        SessionProcessCleanup cleanup =
                new SessionProcessCleanup(process, ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Throwable observed;
        try (var monitor = hold(cleanupFailure)) {
            monitor.verifyHeld();
            Future<Throwable> failingCleanup = executor.submit(cleanup::stopAfterFailure);

            observed = failingCleanup.get(1, TimeUnit.SECONDS);
            ExecutionException repeated = assertThrows(
                    ExecutionException.class,
                    () -> executor.submit(cleanup::stop).get(1, TimeUnit.SECONDS));
            assertSame(observed, repeated.getCause());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(process.isAlive());
        assertSame(cleanupFailure, observed.getCause());
        assertEquals(0, cleanupFailure.getSuppressed().length);
    }

    private static <T> T after(CountDownLatch start, java.util.concurrent.Callable<T> action) {
        try {
            start.await();
            return action.call();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static class ControlledProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger terminationCalls = new AtomicInteger();

        private void complete(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        private int terminationCalls() {
            return terminationCalls.get();
        }

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
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException timeoutFailure) {
                return false;
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
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
            terminate();
        }

        @Override
        public Process destroyForcibly() {
            terminate();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private void terminate() {
            terminationCalls.incrementAndGet();
            complete(143);
        }
    }

    private static final class FailingTerminationProcess extends ControlledProcess {

        private final RuntimeException failure;

        private FailingTerminationProcess(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void destroy() {
            throw failure;
        }

        @Override
        public Process destroyForcibly() {
            throw failure;
        }
    }
}

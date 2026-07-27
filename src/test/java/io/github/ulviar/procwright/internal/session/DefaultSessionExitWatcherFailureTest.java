/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.failureShutdownCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionExitWatcherFailureTest {

    @Test
    void exitWatcherFailureForceStopsRootWhenHandleAccessIsUnavailable() throws Exception {
        WatcherFailureProcess process = new WatcherFailureProcess();
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch shutdownFailurePublished = new CountDownLatch(1);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(event -> {
                            events.add(event);
                            if (event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED
                                    && "failure".equals(event.attributes().get("reason"))) {
                                shutdownFailurePublished.countDown();
                            }
                        }),
                        "session-test",
                        CommandEcho.empty()));
        try {
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

            assertSame(process.watcherFailure(), exitFailure.getCause());
            assertEquals(1, process.forceDestroyCalls());
            assertTrue(shutdownFailurePublished.await(1, TimeUnit.SECONDS));
            assertEquals(1, failureShutdownCount(events));
            assertEquals(0, process.watcherFailure().getSuppressed().length);
        } finally {
            session.close();
        }
    }

    @Test
    void watcherFailurePublicationDoesNotInspectTheFailureGraph() throws Exception {
        WatcherFailureProcess process = new WatcherFailureProcess();
        ExecutionException exitFailure;
        try (var monitor = hold(process.watcherFailure())) {
            monitor.verifyHeld();
            DefaultSession session = SessionTestFixtures.open(
                    process,
                    Duration.ZERO,
                    ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                    StandardCharsets.UTF_8,
                    DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));

            exitFailure = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

            assertSame(process.watcherFailure(), exitFailure.getCause());
        }
        assertEquals(0, process.watcherFailure().getSuppressed().length);
    }

    @Test
    void watcherCleanupClosesEveryStreamWhenThePrimaryFailureRepeats() throws Exception {
        RepeatedWatcherFailureProcess process = new RepeatedWatcherFailureProcess();
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));

        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

        assertSame(process.failure(), exitFailure.getCause());
        assertTrue(eventually(() -> process.stdoutClosed() && process.stderrClosed()));
        assertEquals(0, process.failure().getSuppressed().length);
    }

    @Test
    void losingWatcherFailureDoesNotRunThePrimaryOwnersCleanup() throws Exception {
        WatcherFailureProcess process = new WatcherFailureProcess();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicReference<Runnable> exitWatcher = new AtomicReference<>();
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.blockOnceOn(
                DiagnosticsSettings.disabled().withListener(ignored -> {}),
                "session-test",
                DiagnosticEventType.SHUTDOWN_REQUESTED,
                ownerEntered,
                releaseOwner);
        DefaultSession session = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics,
                () -> {},
                BoundedCloseDispatcher.shared(),
                (name, task) -> {
                    exitWatcher.set(task);
                    return new Thread(task, name + "captured");
                });
        Thread owner = new Thread(session::close, "session-close-owner");
        owner.start();
        try {
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS));

            exitWatcher.get().run();

            assertEquals(0, process.forceDestroyCalls());
        } finally {
            releaseOwner.countDown();
            owner.join(1_000);
            session.close();
        }
        assertTrue(!owner.isAlive());
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return condition.getAsBoolean();
    }

    private static final class WatcherFailureProcess extends Process {

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

        private IllegalStateException watcherFailure() {
            return watcherFailure;
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class RepeatedWatcherFailureProcess extends Process {

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

        private IllegalStateException failure() {
            return failure;
        }

        private boolean stdoutClosed() {
            return stdoutClosed.get();
        }

        private boolean stderrClosed() {
            return stderrClosed.get();
        }
    }
}

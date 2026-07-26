/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.CloseFailureProcess;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.ControlledFailingCloseOutputStream;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.TrackingProcessHandle;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.failureShutdownCount;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.terminalEventCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionStdinCloseTerminalRaceTest {

    @Test
    void blockedStdinCloseFailureAfterNaturalExitIsReportedOnceWithoutContradictoryTerminalEvents() throws Exception {
        IOException closeFailure = new IOException("late stdin close failed");
        ControlledFailingCloseOutputStream stdin = new ControlledFailingCloseOutputStream(closeFailure);
        CloseFailureProcess process = new CloseFailureProcess(stdin);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch processExitPublished = new CountDownLatch(1);
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        processExitPublished.countDown();
                    }
                }),
                "session-test",
                CommandEcho.empty());
        AtomicInteger reportCount = new AtomicInteger();
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            reportCount.incrementAndGet();
            reportedFailure.compareAndSet(null, failure);
            reported.countDown();
        });
        try {
            DefaultSession session = SessionTestFixtures.open(
                    process,
                    Duration.ZERO,
                    ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                    StandardCharsets.UTF_8,
                    diagnostics);
            try {
                assertTrue(process.awaitDescendantObservation());
                session.closeStdin();
                assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));

                process.completeNaturally(0);
                assertEquals(
                        0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());

                stdin.releaseClose();
                assertTrue(reported.await(1, TimeUnit.SECONDS));
                assertTrue(processExitPublished.await(1, TimeUnit.SECONDS));

                assertSame(closeFailure, reportedFailure.get());
                assertEquals(1, reportCount.get());
                assertEquals(1, terminalEventCount(events, DiagnosticEventType.PROCESS_EXITED));
                assertEquals(0, terminalEventCount(events, DiagnosticEventType.PROCESS_FAILED));
                assertEquals(0, terminalEventCount(events, DiagnosticEventType.SHUTDOWN_REQUESTED));
                assertFalse(process.descendant().isAlive(), "late failure must still clean up a surviving descendant");
                assertEquals(1, process.descendant().forceDestroyCalls());
            } finally {
                stdin.releaseClose();
                session.close();
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void asynchronousStdinFailureWinsConcurrentCloseWithoutPublishingCloseSuccess() throws Exception {
        IOException closeFailure = new IOException("concurrent stdin close failed");
        ControlledFailingCloseOutputStream stdin = new ControlledFailingCloseOutputStream(closeFailure);
        BlockingRootDestroyProcess process = new BlockingRootDestroyProcess(stdin);
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
                ShutdownPolicy.interruptThenKill(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                StandardCharsets.UTF_8,
                diagnostics);
        AtomicReference<Throwable> closeFailureObserved = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                session.close();
            } catch (Throwable failure) {
                closeFailureObserved.set(failure);
            }
        });
        closer.setDaemon(true);
        try {
            session.closeStdin();
            assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));

            stdin.releaseClose();
            assertTrue(process.awaitDestroyStarted());
            closer.start();
            closer.join(1_000);
            assertFalse(closer.isAlive());
            assertNull(closeFailureObserved.get());
            assertFalse(session.onExit().isDone(), "terminal failure must not publish before cleanup completes");

            process.releaseDestroy();
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));
            assertSame(closeFailure, exitFailure.getCause());
            assertTrue(processFailurePublished.await(1, TimeUnit.SECONDS));

            assertEquals(0, terminalEventCount(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(1, terminalEventCount(events, DiagnosticEventType.PROCESS_FAILED));
            assertEquals(1, failureShutdownCount(events));
        } finally {
            stdin.releaseClose();
            process.releaseDestroy();
            closer.join(1_000);
            session.close();
        }
    }

    @Test
    void explicitCloseSuccessReportsLaterStdinFailureWithoutRepublishingTerminalDiagnostics() throws Exception {
        IOException closeFailure = new IOException("stdin close failed after explicit close");
        ControlledFailingCloseOutputStream stdin = new ControlledFailingCloseOutputStream(closeFailure);
        CloseFailureProcess process = new CloseFailureProcess(stdin);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch processExitPublished = new CountDownLatch(1);
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        processExitPublished.countDown();
                    }
                }),
                "session-test",
                CommandEcho.empty());
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        AtomicInteger reportCount = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            reportedFailure.compareAndSet(null, failure);
            reportCount.incrementAndGet();
            reported.countDown();
        });
        try {
            DefaultSession session = SessionTestFixtures.open(
                    process,
                    Duration.ZERO,
                    ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                    StandardCharsets.UTF_8,
                    diagnostics);
            try {
                assertTrue(process.awaitDescendantObservation());
                session.closeStdin();
                assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));

                session.close();
                assertEquals(
                        143,
                        session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());

                stdin.releaseClose();
                assertTrue(reported.await(1, TimeUnit.SECONDS));
                assertTrue(processExitPublished.await(1, TimeUnit.SECONDS));

                assertSame(closeFailure, reportedFailure.get());
                assertEquals(1, reportCount.get());
                assertEquals(1, terminalEventCount(events, DiagnosticEventType.PROCESS_EXITED));
                assertEquals(0, terminalEventCount(events, DiagnosticEventType.PROCESS_FAILED));
                assertEquals(1, shutdownCount(events, "close"));
                assertEquals(0, shutdownCount(events, "failure"));
            } finally {
                stdin.releaseClose();
                session.close();
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static final class BlockingRootDestroyProcess extends Process {

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

        private BlockingRootDestroyProcess(OutputStream stdin) {
            this.stdin = stdin;
        }

        private boolean awaitDestroyStarted() throws InterruptedException {
            return destroyStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseDestroy() {
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

    private static int shutdownCount(List<DiagnosticEvent> events, String reason) {
        return Math.toIntExact(events.stream()
                .filter(event -> event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED)
                .filter(event -> reason.equals(event.attributes().get("reason")))
                .count());
    }
}

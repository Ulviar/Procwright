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
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.Threading;
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
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionStdinCloseTerminalRaceTest {

    @Test
    void closeCleansObservedDescendantWhileNaturalExitCleanupIsStillStarting() throws Exception {
        ControlledFailingCloseOutputStream stdin =
                new ControlledFailingCloseOutputStream(new IOException("late close failure"));
        CloseFailureProcess process = new CloseFailureProcess(stdin);
        CountDownLatch closeStarterEntered = new CountDownLatch(1);
        CountDownLatch releaseCloseStarter = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, (name, task) -> {
            if (starts.getAndIncrement() == 0) {
                closeStarterEntered.countDown();
                awaitIgnoringInterrupts(releaseCloseStarter);
            }
            Threading.start(name, task);
        });
        DefaultSession session = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()),
                () -> {},
                dispatcher,
                Threading::start);
        try {
            assertTrue(process.awaitDescendantObservation());
            process.completeNaturally(0);
            assertTrue(closeStarterEntered.await(1, TimeUnit.SECONDS));

            session.close();

            assertFalse(process.descendant().isAlive());
            assertEquals(1, process.descendant().gracefulDestroyCalls());
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
        } finally {
            releaseCloseStarter.countDown();
            stdin.releaseClose();
        }
        assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
    }

    @Test
    void blockedStdinCloseFailureAfterNaturalExitDoesNotChangeTheTerminalOutcome() throws Exception {
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
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());

            stdin.releaseClose();
            assertTrue(processExitPublished.await(1, TimeUnit.SECONDS));
            assertTrue(eventually(() -> !process.descendant().isAlive()));

            assertEquals(1, terminalEventCount(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(0, terminalEventCount(events, DiagnosticEventType.PROCESS_FAILED));
            assertEquals(0, terminalEventCount(events, DiagnosticEventType.SHUTDOWN_REQUESTED));
            assertEquals(1, process.descendant().forceDestroyCalls());
        } finally {
            stdin.releaseClose();
            session.close();
        }
    }

    @Test
    void closeAfterNaturalExitCleansAnObservedLiveDescendant() throws Exception {
        ControlledFailingCloseOutputStream stdin =
                new ControlledFailingCloseOutputStream(new IOException("late close failure"));
        CloseFailureProcess process = new CloseFailureProcess(stdin);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        try {
            assertTrue(process.awaitDescendantObservation());
            process.completeNaturally(0);
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));

            session.close();

            assertFalse(process.descendant().isAlive());
            assertEquals(1, process.descendant().gracefulDestroyCalls());
        } finally {
            stdin.releaseClose();
            session.close();
        }
    }

    @Test
    void concurrentCloseAfterNaturalExitHasOneCleanupOwner() throws Exception {
        ControlledFailingCloseOutputStream stdin =
                new ControlledFailingCloseOutputStream(new IOException("late close failure"));
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CloseFailureProcess process = new CloseFailureProcess(stdin, () -> {
            cleanupStarted.countDown();
            awaitIgnoringInterrupts(releaseCleanup);
        });
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        CountDownLatch secondCloseReturned = new CountDownLatch(1);
        Thread firstCloser = new Thread(session::close, "post-natural-cleanup-owner");
        Thread secondCloser = new Thread(
                () -> {
                    session.close();
                    secondCloseReturned.countDown();
                },
                "post-natural-cleanup-loser");
        firstCloser.setDaemon(true);
        secondCloser.setDaemon(true);
        try {
            assertTrue(process.awaitDescendantObservation());
            process.completeNaturally(0);
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));
            firstCloser.start();
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));

            secondCloser.start();

            assertTrue(secondCloseReturned.await(1, TimeUnit.SECONDS));
        } finally {
            releaseCleanup.countDown();
            stdin.releaseClose();
            firstCloser.join(1_000);
            secondCloser.join(1_000);
            session.close();
        }
        assertFalse(firstCloser.isAlive());
        assertFalse(secondCloser.isAlive());
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
        AtomicBoolean closeInterruptPreserved = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                session.close();
            } catch (Throwable failure) {
                closeFailureObserved.set(failure);
            } finally {
                closeInterruptPreserved.set(Thread.currentThread().isInterrupted());
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
            assertFalse(closer.isAlive(), "losing close must not join the primary owner's cleanup");
            assertNull(closeFailureObserved.get());
            assertTrue(closeInterruptPreserved.get());
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
    void explicitCloseRemainsStableAfterALateStdinFailure() throws Exception {
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
                    143, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());

            stdin.releaseClose();
            assertTrue(processExitPublished.await(1, TimeUnit.SECONDS));

            assertEquals(1, terminalEventCount(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(0, terminalEventCount(events, DiagnosticEventType.PROCESS_FAILED));
            assertEquals(1, shutdownCount(events, "close"));
            assertEquals(0, shutdownCount(events, "failure"));
        } finally {
            stdin.releaseClose();
            session.close();
        }
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
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
            return !exit.isDone();
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

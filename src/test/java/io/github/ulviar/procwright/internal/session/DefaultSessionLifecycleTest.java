/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultSessionLifecycleTest {

    @Test
    void hostilePublicExitCompositionCannotPinCloseWatcherOrInternalObservers() throws Exception {
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        AtomicReference<Thread> exitWatcher = new AtomicReference<>();
        DefaultSession session = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()),
                () -> {},
                new BoundedCloseDispatcher(1, 2, 3),
                (threadPrefix, task) -> {
                    Thread watcher = io.github.ulviar.procwright.internal.Threading.start(threadPrefix, task);
                    exitWatcher.set(watcher);
                    return watcher;
                });
        CountDownLatch internalObserverCalled = new CountDownLatch(1);
        AtomicReference<SessionExit> internalResult = new AtomicReference<>();
        session.observeExit((result, failure) -> {
            assertNull(failure);
            internalResult.set(result);
            internalObserverCalled.countDown();
        });
        CountDownLatch hostileEntered = new CountDownLatch(1);
        CountDownLatch releaseHostile = new CountDownLatch(1);
        AtomicReference<SessionExit> publicResult = new AtomicReference<>();
        CompletableFuture<SessionExit> hostileComposition = session.onExit()
                .thenCompose(result -> {
                    publicResult.set(result);
                    hostileEntered.countDown();
                    awaitIgnoringInterrupts(releaseHostile);
                    return CompletableFuture.completedFuture(result);
                })
                .handle((result, failure) -> {
                    if (failure != null) {
                        throw new AssertionError("unexpected public exit failure", failure);
                    }
                    return result;
                });
        Thread closer = new Thread(session::close, "hostile-public-exit-close");
        closer.setDaemon(true);
        try {
            closer.start();
            assertTrue(hostileEntered.await(1, TimeUnit.SECONDS));
            assertTrue(internalObserverCalled.await(1, TimeUnit.SECONDS));

            closer.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(closer.isAlive(), "public continuation pinned Session.close()");
            Thread watcher = exitWatcher.get();
            watcher.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(watcher.isAlive(), "public continuation pinned the process exit watcher");
            assertFalse(hostileComposition.isDone());
            assertSame(internalResult.get(), publicResult.get());
        } finally {
            releaseHostile.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(1));
            session.close();
        }
        assertSame(publicResult.get(), hostileComposition.get(1, TimeUnit.SECONDS));
    }

    @Test
    void publicExitFutureRemainsADefensiveCopy() throws Exception {
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        DefaultSession session = new DefaultSession(
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
    void exhaustedCloseAdmissionFailsBeforeSessionPublicationAndTerminatesProcess() throws Exception {
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        BoundedCloseDispatcher.Reservation occupied = dispatcher.reserve(3);
        try {
            assertThrows(
                    RejectedExecutionException.class,
                    () -> DefaultSession.openTransactionally(
                            process,
                            Duration.ZERO,
                            ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                            StandardCharsets.UTF_8,
                            DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()),
                            () -> {},
                            dispatcher,
                            io.github.ulviar.procwright.internal.Threading::start));

            assertFalse(process.isAlive(), "capacity exhaustion must retire the session process");
        } finally {
            occupied.release();
        }
    }

    @Test
    void asynchronousIoStdinCloseFailureTerminatesSessionWithTheOriginalCause() throws Exception {
        assertAsynchronousStdinCloseFailure(new IOException("stdin close failed"));
    }

    @Test
    void asynchronousRuntimeStdinCloseFailureTerminatesSessionWithTheOriginalCause() throws Exception {
        assertAsynchronousStdinCloseFailure(new IllegalStateException("stdin close failed"));
    }

    @Test
    void asynchronousErrorStdinCloseFailureTerminatesSessionWithTheOriginalCause() throws Exception {
        assertAsynchronousStdinCloseFailure(new AssertionError("stdin close failed"));
    }

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
            DefaultSession session = new DefaultSession(
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
        DefaultSession session = new DefaultSession(
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
            DefaultSession session = new DefaultSession(
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

    @Test
    void cyclicCleanupFailureCannotHangCloseAndPreservesPrimaryIdentity() throws Exception {
        IllegalStateException primary = new IllegalStateException("cyclic cleanup failure");
        IllegalArgumentException cycle = new IllegalArgumentException("cycle");
        primary.initCause(cycle);
        cycle.initCause(primary);
        FailingDescendantProcess process = new FailingDescendantProcess(primary);
        DefaultSession session = new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        assertTrue(process.awaitDescendantObservation());

        IllegalStateException thrown = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> assertThrows(IllegalStateException.class, session::close));
        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

        assertSame(primary, thrown);
        assertSame(primary, exitFailure.getCause());
        assertEquals(1, process.rootDestroyCalls());
        assertTrue(process.descendant().gracefulDestroyCalls() >= 1);
        assertTrue(process.descendant().forceDestroyCalls() >= 1);
        assertFalse(process.isAlive());
    }

    @Test
    void closeErrorCompletesExitFutureAndPreservesPrimaryError() throws Exception {
        AssertionError closeError = new AssertionError("descendant close failed");
        FailingDescendantProcess process = new FailingDescendantProcess(closeError);
        DefaultSession session = new DefaultSession(
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
        DefaultSession session = new DefaultSession(
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
    void closeStdinDoesNotWaitForRawCloseContendedByAnActiveWrite() throws Exception {
        WriteContendedCloseOutputStream stdin = new WriteContendedCloseOutputStream();
        ControllableProcess process = new ControllableProcess(stdin);
        DefaultSession session = new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        CompletableFuture<Throwable> writerOutcome = new CompletableFuture<>();
        Thread writer = new Thread(() -> {
            try {
                session.send("payload");
                writerOutcome.complete(null);
            } catch (Throwable failure) {
                writerOutcome.complete(failure);
            }
        });
        writer.setDaemon(true);
        writer.start();
        try {
            assertTrue(stdin.awaitWriteStarted(Duration.ofSeconds(1)));

            assertTimeoutPreemptively(Duration.ofSeconds(1), session::closeStdin);

            assertFalse(
                    stdin.closeStarted(),
                    "the raw close cannot acquire the delegate monitor while the active write owns it");
            stdin.releaseWrite();
            SessionStdinClosedException writerFailure =
                    assertInstanceOf(SessionStdinClosedException.class, writerOutcome.get(1, TimeUnit.SECONDS));
            assertEquals("Session stdin is closed", writerFailure.getMessage());
            assertTrue(stdin.awaitCloseStarted(Duration.ofSeconds(1)));
        } finally {
            stdin.releaseWrite();
            writer.join(TimeUnit.SECONDS.toMillis(1));
            session.close();
        }

        assertFalse(writer.isAlive());
    }

    @Test
    void exitWatcherFailureForceStopsRootWhenLivenessCannotBeObserved() throws Exception {
        WatcherFailureProcess process = new WatcherFailureProcess();
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch shutdownFailurePublished = new CountDownLatch(1);
        DefaultSession session = new DefaultSession(
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
    void watcherCleanupClosesEveryStreamWhenThePrimaryFailureRepeats() throws Exception {
        RepeatedWatcherFailureProcess process = new RepeatedWatcherFailureProcess();
        DefaultSession session = new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));

        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

        assertSame(process.failure(), exitFailure.getCause());
        assertTrue(process.stdoutClosed());
        assertTrue(process.stderrClosed());
        assertEquals(0, process.failure().getSuppressed().length);
    }

    private static void assertAsynchronousStdinCloseFailure(Throwable closeFailure) throws Exception {
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
        DefaultSession session = new DefaultSession(
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

    private static int terminalEventCount(List<DiagnosticEvent> events, DiagnosticEventType type) {
        return Math.toIntExact(
                events.stream().filter(event -> event.type() == type).count());
    }

    private static int failureShutdownCount(List<DiagnosticEvent> events) {
        return shutdownCount(events, "failure");
    }

    private static int shutdownCount(List<DiagnosticEvent> events, String reason) {
        return Math.toIntExact(events.stream()
                .filter(event -> event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED)
                .filter(event -> reason.equals(event.attributes().get("reason")))
                .count());
    }

    private static final class CloseFailureProcess extends Process {

        private final ControlledFailingCloseOutputStream stdin;
        private final CountDownLatch descendantObserved = new CountDownLatch(1);
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final TrackingProcessHandle root =
                new TrackingProcessHandle(Long.MAX_VALUE - 3, alive, () -> exit.complete(143));
        private final TrackingProcessHandle descendant =
                new TrackingProcessHandle(Long.MAX_VALUE - 2, new AtomicBoolean(true), () -> {});

        private CloseFailureProcess(ControlledFailingCloseOutputStream stdin) {
            this.stdin = stdin;
        }

        private boolean awaitDescendantObservation() throws InterruptedException {
            return descendantObserved.await(1, TimeUnit.SECONDS);
        }

        private TrackingProcessHandle descendant() {
            return descendant;
        }

        private void completeNaturally(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        private int rootGracefulDestroyCalls() {
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

    private static class TrackingProcessHandle implements ProcessHandle {

        private final long pid;
        private final AtomicBoolean alive;
        private final Runnable onStop;
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        private TrackingProcessHandle(long pid, AtomicBoolean alive, Runnable onStop) {
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

        private int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class ControlledFailingCloseOutputStream extends OutputStream {

        private final Throwable failure;
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private ControlledFailingCloseOutputStream(Throwable failure) {
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

        private boolean awaitCloseStarted(Duration timeout) throws InterruptedException {
            return closeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void releaseClose() {
            release.countDown();
        }
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

    private static final class ControllableProcess extends Process {

        private final OutputStream stdin;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private ControllableProcess(OutputStream stdin) {
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

    private static final class WatcherFailureProcess extends Process {

        private final IllegalStateException watcherFailure = new IllegalStateException("watcher failed");
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger forceDestroyCalls =
                new java.util.concurrent.atomic.AtomicInteger();

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
            throw new SecurityException("descendant enumeration is denied");
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
            throw new SecurityException("descendant enumeration is denied");
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

    private static final class WriteContendedCloseOutputStream extends OutputStream {

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

        private boolean awaitWriteStarted(Duration timeout) throws InterruptedException {
            return writeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void releaseWrite() {
            releaseWrite.countDown();
        }

        private boolean closeStarted() {
            return closeStarted.getCount() == 0;
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

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
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

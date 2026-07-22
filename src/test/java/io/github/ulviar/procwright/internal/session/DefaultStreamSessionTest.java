/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchMode;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamSource;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DefaultStreamSessionTest {

    @Test
    void blockingPublicExitContinuationObservesReleasedOutputCleanupOwners() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultStreamSession stream =
                new DefaultStreamSession(session(process, dispatcher), plan(chunk -> {}), diagnostics());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean cleanupWasComplete = new AtomicBoolean();
        CompletableFuture<Void> continuation = stream.onExit().thenRun(() -> {
            cleanupWasComplete.set(stream.physicalOutputCleanup().isDone()
                    && stream.outputCleanupCompleted()
                    && dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0);
            continuationEntered.countDown();
            awaitUninterruptibly(releaseContinuation);
        });
        FutureTask<Void> closeTask = new FutureTask<>(() -> {
            stream.close();
            return null;
        });
        Thread closeThread = new Thread(closeTask, "procwright-stream-blocking-continuation-test");
        closeThread.setDaemon(true);
        closeThread.start();
        try {
            assertTrue(continuationEntered.await(1, TimeUnit.SECONDS));
            assertTrue(cleanupWasComplete.get());
            BoundedCloseDispatcher.Reservation fullCapacity = dispatcher.reserve(4);
            fullCapacity.release();
            assertFalse(continuation.isDone());
        } finally {
            releaseContinuation.countDown();
        }
        closeTask.get(1, TimeUnit.SECONDS);
        continuation.get(1, TimeUnit.SECONDS);
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void exitDurationUsesInjectedMonotonicTimeAndClampsBackwardReadings() throws Exception {
        AtomicLong nanoTime = new AtomicLong(100);
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                diagnostics(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                () -> nanoTime.getAndSet(50));
        try {
            process.complete(0);

            assertEquals(Duration.ZERO, stream.onExit().get(1, TimeUnit.SECONDS).duration());
        } finally {
            stream.close();
        }
    }

    @Test
    void timeoutWatcherStopsBeforeBlockingPublicExitContinuationRuns() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultStreamSession stream =
                new DefaultStreamSession(session(process), plan(chunk -> {}, Duration.ofSeconds(30)), diagnostics());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean watcherWasStopped = new AtomicBoolean();
        CompletableFuture<Void> continuation = stream.onExit().thenRun(() -> {
            watcherWasStopped.set(stream.timeoutWatcherStopped().isDone());
            continuationEntered.countDown();
            awaitUninterruptibly(releaseContinuation);
        });
        try {
            process.complete(0);

            assertTrue(continuationEntered.await(1, TimeUnit.SECONDS));
            assertTrue(watcherWasStopped.get());
            assertTrue(stream.timeoutWatcherStopped().isDone());
            assertFalse(continuation.isDone(), "the public continuation must still be blocked by the test latch");
        } finally {
            releaseContinuation.countDown();
            stream.close();
        }
        continuation.get(1, TimeUnit.SECONDS);
    }

    @Test
    void fallbackHelperTerminalContinuationCannotStrandAnotherStreamSessionTerminal() throws Exception {
        BoundedCloseDispatcher dispatcher = outputStartFailingDispatcher(6);
        ControllableProcess firstProcess =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        ControllableProcess secondProcess =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultStreamSession first =
                new DefaultStreamSession(session(firstProcess, dispatcher), plan(chunk -> {}), diagnostics());
        DefaultStreamSession second =
                new DefaultStreamSession(session(secondProcess, dispatcher), plan(chunk -> {}), diagnostics());
        CompletableFuture<Void> escape = new CompletableFuture<>();
        CompletableFuture<Void> secondTerminal = second.onExit().handle((ignored, failure) -> null);
        CountDownLatch firstContinuationEntered = new CountDownLatch(1);
        CompletableFuture<Void> firstContinuation = first.onExit().handle((ignored, failure) -> {
            firstContinuationEntered.countDown();
            CompletableFuture.anyOf(secondTerminal, escape).join();
            return null;
        });
        try {
            firstProcess.complete(0);
            assertTrue(firstContinuationEntered.await(1, TimeUnit.SECONDS));

            secondProcess.complete(0);

            secondTerminal.get(1, TimeUnit.SECONDS);
            firstContinuation.get(1, TimeUnit.SECONDS);
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        } finally {
            escape.complete(null);
            firstProcess.complete(143);
            secondProcess.complete(143);
            closeIgnoringTerminal(first);
            closeIgnoringTerminal(second);
        }
    }

    @Test
    void normalCompletionClaimRejectsALateTimeoutWithoutPostTerminalDiagnostics() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            process.complete(0);
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            stream.expireTimeout();

            assertFalse(result.timedOut());
            assertFalse(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
        } finally {
            stream.close();
        }
    }

    @Test
    void timeoutClaimWinsBeforeProcessCompletionAndPublishesOneConsistentOutcome() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            stream.expireTimeout();
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            assertTrue(result.timedOut());
            assertFalse(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(1, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(1, timeoutShutdownCount(events));
        } finally {
            stream.close();
        }
    }

    @Test
    void closeClaimRejectsLateTimeoutAndNormalCompletion() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            stream.close();
            stream.expireTimeout();
            process.complete(0);
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            assertFalse(result.timedOut());
            assertTrue(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
            assertEquals(1, shutdownCount(events, "close"));
        } finally {
            stream.close();
        }
    }

    @Test
    void failureClaimRejectsLateTimeoutAndNormalCompletion() throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream("x");
        ControllableProcess process = new ControllableProcess(stdout, InputStream.nullInputStream());
        IllegalStateException listenerFailure = new IllegalStateException("listener failed");
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {
                    throw listenerFailure;
                }),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            assertTrue(stdout.awaitReadStarted());
            stdout.release();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> stream.onExit().get(1, TimeUnit.SECONDS));
            StreamException streamFailure = (StreamException) failure.getCause();
            assertSame(listenerFailure, streamFailure.getCause());

            stream.expireTimeout();
            process.complete(0);

            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
            assertEquals(1, shutdownCount(events, "failure"));
        } finally {
            stdout.release();
            stream.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> blockedListenerCannotDelayControlOutcomeOrLateFailureAccounting() {
        return Stream.of(ControlAction.values()).flatMap(control -> Stream.of(NestedFailureKind.values())
                .map(failureKind -> DynamicTest.dynamicTest(
                        control + " / late listener " + failureKind,
                        () -> assertBlockedListenerCannotDelayControlOutcome(control, failureKind))));
    }

    private static void assertBlockedListenerCannotDelayControlOutcome(
            ControlAction control, NestedFailureKind failureKind) throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream("chunk");
        CountingEofInputStream stderr = new CountingEofInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch lateReported = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        Throwable expected = failureKind.newFailure();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {
                    Thread.currentThread().setUncaughtExceptionHandler((ignored, failure) -> {
                        reported.compareAndSet(null, failure);
                        lateReported.countDown();
                    });
                    listenerEntered.countDown();
                    awaitUninterruptibly(releaseListener);
                    throwUnchecked(expected);
                }),
                diagnostics());
        FutureTask<Throwable> controlTask = new FutureTask<>(() -> captureFailure(() -> control.terminate(stream)));
        Thread controlThread = new Thread(controlTask, "stream-blocked-listener-control");
        controlThread.setDaemon(true);
        try {
            assertTrue(stdout.awaitReadStarted());
            stdout.release();
            assertTrue(listenerEntered.await(1, TimeUnit.SECONDS));

            controlThread.start();

            assertSame(null, controlTask.get(1, TimeUnit.SECONDS));
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(control == ControlAction.CLOSE, result.closed());
            assertEquals(control == ControlAction.TIMEOUT, result.timedOut());
            assertEquals(1L, lateReported.getCount(), "late listener failure preceded callback completion");
            assertTrue(eventually(() -> stdout.closeCalls() == 1 && stderr.closeCalls() == 1));

            releaseListener.countDown();
            assertTrue(lateReported.await(1, TimeUnit.SECONDS));
            assertSame(expected, reported.get());
            assertEquals(result, stream.onExit().get(1, TimeUnit.SECONDS));
        } finally {
            releaseListener.countDown();
            stdout.release();
            process.complete(143);
            stream.close();
            controlThread.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @TestFactory
    Stream<DynamicTest> controlOutcomeCompletesAfterLateNestedSessionFailure() {
        return Stream.of(ControlAction.values()).flatMap(control -> Stream.of(NestedFailureKind.values())
                .flatMap(failureKind -> Stream.of(PumpCompletionOrder.values())
                        .map(pumpOrder -> DynamicTest.dynamicTest(
                                control + " / " + failureKind + " / " + pumpOrder,
                                () -> assertControlOutcomeCompletesAfterLateNestedSessionFailure(
                                        control, failureKind, pumpOrder)))));
    }

    private static void assertControlOutcomeCompletesAfterLateNestedSessionFailure(
            ControlAction control, NestedFailureKind failureKind, PumpCompletionOrder pumpOrder) throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        GatedEofInputStream stderr = new GatedEofInputStream();
        FailingLivenessProcess process = new FailingLivenessProcess(stdout, stderr);
        TrackingPumpStarter pumpStarter = new TrackingPumpStarter();
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch controlSelected = new CountDownLatch(1);
        CountDownLatch processExitedDelivered = new CountDownLatch(1);
        DiagnosticEmitter eventDiagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (control.isSelectionEvent(event)) {
                        controlSelected.countDown();
                    }
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        processExitedDelivered.countDown();
                    }
                }),
                "stream-late-session-failure-test",
                CommandEcho.empty());
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process), plan(chunk -> {}), eventDiagnostics, ZeroReadBackoff.exponential(), pumpStarter);
        CompletableFuture<StreamExit> observedExit = stream.onExit();
        AtomicInteger exitCompletions = new AtomicInteger();
        CompletableFuture<StreamExit> observedExitContinuation =
                observedExit.whenComplete((ignored, failure) -> exitCompletions.incrementAndGet());
        Throwable expected = failureKind.newFailure();
        CopyOnWriteArrayList<Throwable> lateReports = new CopyOnWriteArrayList<>();
        CountDownLatch lateReportEntered = new CountDownLatch(1);
        CountDownLatch releaseLateReport = new CountDownLatch(1);
        FutureTask<Throwable> controlTask = new FutureTask<>(() -> captureFailure(() -> control.terminate(stream)));
        Thread controlThread = new Thread(controlTask, "stream-control-test");
        controlThread.setDaemon(true);
        controlThread.setUncaughtExceptionHandler((ignored, failure) -> {
            lateReports.add(failure);
            lateReportEntered.countDown();
            awaitUninterruptibly(releaseLateReport);
        });
        process.failLivenessOn(controlThread, expected);

        try {
            assertTrue(stdout.awaitReadStarted(), "stdout pump did not start");
            assertTrue(stderr.awaitReadStarted(), "stderr pump did not start");
            if (pumpOrder == PumpCompletionOrder.EOF_BEFORE_FAILURE) {
                stdout.releaseEof();
                stderr.releaseEof();
                assertTrue(pumpStarter.awaitCompletion(), "output pumps did not finish before control");
            }

            controlThread.start();
            assertTrue(controlSelected.await(1, TimeUnit.SECONDS), "control outcome was not selected");
            assertTrue(process.awaitLivenessFailure(), "nested session did not reach the liveness failure");
            process.releaseLivenessFailure();

            if (pumpOrder == PumpCompletionOrder.EOF_AFTER_FAILURE) {
                assertFalse(observedExit.isDone(), "stream exit completed before blocked pumps reached EOF");
                assertEquals(1L, lateReportEntered.getCount(), "late report preceded terminal stream accounting");
                stdout.releaseEof();
                stderr.releaseEof();
                assertTrue(pumpStarter.awaitCompletion(), "output pumps did not finish after nested failure");
            }

            StreamExit result = observedExitContinuation.get(1, TimeUnit.SECONDS);
            assertTrue(lateReportEntered.await(1, TimeUnit.SECONDS), "late nested failure was not reported");
            assertLateReport(failureKind, expected, lateReports.get(0));
            assertEquals(control == ControlAction.TIMEOUT, result.timedOut());
            assertEquals(control == ControlAction.CLOSE, result.closed());
            assertTrue(result.exitCode().isEmpty());
            assertEquals(1, exitCompletions.get());
            assertSame(expected, controlTask.get(1, TimeUnit.SECONDS));

            releaseLateReport.countDown();
            assertTrue(eventually(() -> stdout.closeCalls() == 1 && stderr.closeCalls() == 1));

            stream.close();
            stream.expireTimeout();
            assertEquals(result, stream.onExit().get(1, TimeUnit.SECONDS));
            assertTrue(
                    processExitedDelivered.await(1, TimeUnit.SECONDS), "PROCESS_EXITED diagnostic was not delivered");
            assertEquals(1, exitCompletions.get());
            assertEquals(1, lateReports.size());
            List<DiagnosticEvent> processExitedEvents = events.stream()
                    .filter(event -> event.type() == DiagnosticEventType.PROCESS_EXITED)
                    .toList();
            assertEquals(1, processExitedEvents.size());
            assertEquals(
                    Boolean.toString(control == ControlAction.TIMEOUT),
                    processExitedEvents.get(0).attributes().get("timedOut"));
            assertFalse(processExitedEvents.get(0).attributes().containsKey("exitCode"));
            assertEquals(0, count(events, DiagnosticEventType.PROCESS_FAILED));
            assertEquals(control == ControlAction.TIMEOUT ? 1 : 0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(control == ControlAction.TIMEOUT ? 1 : 0, timeoutShutdownCount(events));
            assertEquals(control == ControlAction.CLOSE ? 1 : 0, shutdownCount(events, "close"));
            assertEquals(0, shutdownCount(events, "failure"));
        } finally {
            process.releaseLivenessFailure();
            releaseLateReport.countDown();
            stdout.releaseEof();
            stderr.releaseEof();
            process.complete(143);
            stream.close();
            controlThread.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    private static void assertLateReport(NestedFailureKind kind, Throwable expected, Throwable reported) {
        if (kind == NestedFailureKind.ERROR) {
            assertSame(expected, reported);
            return;
        }
        assertTrue(reported instanceof StreamException);
        StreamException streamFailure = (StreamException) reported;
        assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
        assertSame(expected, streamFailure.getCause());
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    @Test
    void stdoutAndStderrCallbacksRemainSerializedOutsideTerminalOwnership() throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream("o");
        GatedChunkInputStream stderr = new GatedChunkInputStream("e");
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        CountDownLatch firstCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CountDownLatch secondCallbackEntered = new CountDownLatch(1);
        CountDownLatch callbacksFinished = new CountDownLatch(2);
        AtomicInteger callbackEntries = new AtomicInteger();
        AtomicInteger activeCallbacks = new AtomicInteger();
        AtomicInteger maxActiveCallbacks = new AtomicInteger();
        AtomicReference<StreamSource> firstSource = new AtomicReference<>();
        AtomicReference<StreamSource> secondSource = new AtomicReference<>();
        AtomicReference<DefaultStreamSession> openedSession = new AtomicReference<>();
        AtomicBoolean firstCallbackHeldDeliveryLock = new AtomicBoolean();

        DefaultSession rawSession = session(process);
        DefaultStreamSession stream = new DefaultStreamSession(
                rawSession,
                plan(chunk -> {
                    int active = activeCallbacks.incrementAndGet();
                    maxActiveCallbacks.accumulateAndGet(active, Math::max);
                    int entry = callbackEntries.incrementAndGet();
                    try {
                        if (entry == 1) {
                            firstSource.set(chunk.source());
                            firstCallbackHeldDeliveryLock.set(
                                    deliveryLock(openedSession.get()).isHeldByCurrentThread());
                            firstCallbackEntered.countDown();
                            awaitUninterruptibly(releaseFirstCallback);
                        } else {
                            secondSource.set(chunk.source());
                            secondCallbackEntered.countDown();
                        }
                    } finally {
                        activeCallbacks.decrementAndGet();
                        callbacksFinished.countDown();
                    }
                }),
                diagnostics());
        openedSession.set(stream);

        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            stdout.release();
            assertTrue(firstCallbackEntered.await(1, TimeUnit.SECONDS));
            assertEquals(StreamSource.STDOUT, firstSource.get());
            assertFalse(firstCallbackHeldDeliveryLock.get(), "user callbacks must not own terminal coordination state");

            stderr.release();
            assertTrue(stderr.awaitChunkReturned());
            assertTrue(
                    awaitQueuedAtDeliveryBoundary(deliveryLock(stream), stderr.readerThread()),
                    "stderr pump did not reach the shared delivery lock");
            assertEquals(1L, secondCallbackEntered.getCount(), "stderr callback entered while stdout held the lock");
            assertEquals(1, maxActiveCallbacks.get());

            releaseFirstCallback.countDown();
            assertTrue(secondCallbackEntered.await(1, TimeUnit.SECONDS));
            assertTrue(callbacksFinished.await(1, TimeUnit.SECONDS));
            assertEquals(StreamSource.STDERR, secondSource.get());
            assertEquals(2, callbackEntries.get());
            assertEquals(0, activeCallbacks.get());
            assertEquals(1, maxActiveCallbacks.get());

            process.complete(0);
            StreamExit exit = stream.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(0, exit.exitCode().orElseThrow());
        } finally {
            stdout.release();
            stderr.release();
            releaseFirstCallback.countDown();
            process.complete(0);
            stream.close();
        }
    }

    private static boolean awaitQueuedAtDeliveryBoundary(ReentrantLock lock, Thread pump) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!lock.hasQueuedThread(pump)) {
            if (!pump.isAlive() || deadline - System.nanoTime() <= 0) {
                return false;
            }
            LockSupport.parkNanos(100_000L);
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while observing the delivery boundary");
            }
        }
        return true;
    }

    private static int count(List<DiagnosticEvent> events, DiagnosticEventType type) {
        return Math.toIntExact(
                events.stream().filter(event -> event.type() == type).count());
    }

    private static int timeoutShutdownCount(List<DiagnosticEvent> events) {
        return shutdownCount(events, "timeout");
    }

    private static int shutdownCount(List<DiagnosticEvent> events, String reason) {
        return Math.toIntExact(events.stream()
                .filter(event -> event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED)
                .filter(event -> reason.equals(event.attributes().get("reason")))
                .count());
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static ReentrantLock deliveryLock(DefaultStreamSession stream) {
        try {
            Field field = DefaultStreamSession.class.getDeclaredField("deliveryLock");
            assertTrue(field.trySetAccessible());
            return (ReentrantLock) field.get(stream);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not inspect the stream delivery boundary", failure);
        }
    }

    private static DefaultSession session(Process process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    private static DefaultSession session(Process process, BoundedCloseDispatcher closeDispatcher) {
        return DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                () -> {},
                closeDispatcher,
                DefaultSession.WatcherStarter.threading());
    }

    private static BoundedCloseDispatcher outputStartFailingDispatcher(int capacity) {
        return new BoundedCloseDispatcher(2, capacity - 2, capacity, (name, task) -> {
            if (name.contains("stdout-close") || name.contains("stderr-close")) {
                throw new IllegalStateException("output close starter failed: " + name);
            }
            return io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
    }

    private static void closeIgnoringTerminal(DefaultStreamSession session) {
        try {
            session.close();
        } catch (RuntimeException | Error ignored) {
            // The fixture deliberately makes every helper output close report a terminal failure.
        }
    }

    private static StreamExecutionPlan plan(io.github.ulviar.procwright.session.StreamListener listener) {
        return plan(listener, Duration.ZERO);
    }

    private static StreamExecutionPlan plan(
            io.github.ulviar.procwright.session.StreamListener listener, Duration timeout) {
        LaunchPlan launchPlan = new LaunchPlan(
                LaunchMode.DIRECT,
                List.of("stub"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.DISABLED);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                PtyProvider.unavailable(),
                TerminalSize.defaults());
        return new StreamExecutionPlan(sessionPlan, timeout, 1024, listener, DiagnosticsSettings.disabled());
    }

    private static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "stream-delivery-test", CommandEcho.empty());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
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

    private static final class GatedChunkInputStream extends InputStream {

        private final byte[] bytes;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch chunkReturned = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicReference<Thread> readerThread = new AtomicReference<>();

        private GatedChunkInputStream(String text) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            readerThread.compareAndSet(null, Thread.currentThread());
            readStarted.countDown();
            awaitUninterruptibly(release);
            if (!delivered.compareAndSet(false, true)) {
                return -1;
            }
            int count = Math.min(length, bytes.length);
            System.arraycopy(bytes, 0, target, offset, count);
            chunkReturned.countDown();
            return count;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            release.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitChunkReturned() throws InterruptedException {
            return chunkReturned.await(1, TimeUnit.SECONDS);
        }

        private Thread readerThread() {
            return readerThread.get();
        }

        private void release() {
            release.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class CountingEofInputStream extends InputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class GatedEofInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEof = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(releaseEof);
            return -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            return read();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseEof() {
            releaseEof.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class TrackingPumpStarter implements PumpStarter {

        private final CountDownLatch completed = new CountDownLatch(2);
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread start(String namePrefix, Runnable task) {
            Thread thread = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            completed.countDown();
                        }
                    },
                    namePrefix + sequence.getAndIncrement());
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private boolean awaitCompletion() throws InterruptedException {
            return completed.await(1, TimeUnit.SECONDS);
        }
    }

    private enum ControlAction {
        CLOSE,
        TIMEOUT;

        private void terminate(DefaultStreamSession stream) {
            switch (this) {
                case CLOSE -> stream.close();
                case TIMEOUT -> stream.expireTimeout();
            }
        }

        private boolean isSelectionEvent(DiagnosticEvent event) {
            return switch (this) {
                case CLOSE ->
                    event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED
                            && "close".equals(event.attributes().get("reason"));
                case TIMEOUT -> event.type() == DiagnosticEventType.TIMEOUT_REACHED;
            };
        }
    }

    private enum NestedFailureKind {
        RUNTIME,
        ERROR;

        private Throwable newFailure() {
            return switch (this) {
                case RUNTIME -> new IllegalStateException("liveness failed");
                case ERROR -> new AssertionError("liveness failed");
            };
        }
    }

    private enum PumpCompletionOrder {
        EOF_BEFORE_FAILURE,
        EOF_AFTER_FAILURE
    }

    private static final class FailingLivenessProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicReference<Thread> failingThread = new AtomicReference<>();
        private final AtomicReference<Throwable> livenessFailure = new AtomicReference<>();
        private final CountDownLatch livenessFailureEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLivenessFailure = new CountDownLatch(1);

        private FailingLivenessProcess(InputStream stdout, InputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
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
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException ignored) {
                return false;
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            if (Thread.currentThread() == failingThread.get()) {
                livenessFailureEntered.countDown();
                awaitUninterruptibly(releaseLivenessFailure);
                throwUnchecked(livenessFailure.get());
            }
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private void failLivenessOn(Thread thread, Throwable failure) {
            failingThread.set(thread);
            livenessFailure.set(failure);
        }

        private boolean awaitLivenessFailure() throws InterruptedException {
            return livenessFailureEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseLivenessFailure() {
            releaseLivenessFailure.countDown();
        }

        private void complete(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        private static void throwUnchecked(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unexpected checked liveness failure", failure);
        }
    }

    private static final class ControllableProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private ControllableProcess(InputStream stdout, InputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
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
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException ignored) {
                return false;
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {
            complete(143);
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

        private void complete(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }
    }
}

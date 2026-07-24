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
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultSessionStdinCloseArbitrationTest extends DefaultSessionStdinCloseArbitrationTestSupport {

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
}

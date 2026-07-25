/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultSessionWatcherCleanupTest extends DefaultSessionWatcherCleanupTestSupport {

    @Test
    void cyclicCleanupFailureCannotHangCloseAndPreservesPrimaryIdentity() throws Exception {
        IllegalStateException primary = new IllegalStateException("cyclic cleanup failure");
        IllegalArgumentException cycle = new IllegalArgumentException("cycle");
        primary.initCause(cycle);
        cycle.initCause(primary);
        FailingDescendantProcess process = new FailingDescendantProcess(primary);
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        assertTrue(process.awaitDescendantObservation());

        CommandExecutionException thrown = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> assertThrows(CommandExecutionException.class, session::close));
        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, thrown.reason());
        assertSame(primary, thrown.getCause());
        assertSame(thrown, exitFailure.getCause());
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(1, process.rootDestroyCalls());
        assertTrue(process.descendant().gracefulDestroyCalls() >= 1);
        assertTrue(process.descendant().forceDestroyCalls() >= 1);
        assertFalse(process.isAlive());
    }

    @Test
    void closeErrorCompletesExitFutureAndPreservesPrimaryError() throws Exception {
        AssertionError closeError = new AssertionError("descendant close failed");
        FailingDescendantProcess process = new FailingDescendantProcess(closeError);
        DefaultSession session = SessionTestFixtures.open(
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
        DefaultSession session = SessionTestFixtures.open(
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
        assertTrue(process.stdoutClosed());
        assertTrue(process.stderrClosed());
        assertEquals(0, process.failure().getSuppressed().length);
    }
}

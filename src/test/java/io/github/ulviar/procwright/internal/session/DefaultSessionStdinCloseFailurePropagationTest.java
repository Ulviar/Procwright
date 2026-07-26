/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.CloseFailureProcess;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.ControlledFailingCloseOutputStream;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.failureShutdownCount;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.terminalEventCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DefaultSessionStdinCloseFailurePropagationTest {

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
        DefaultSession session = SessionTestFixtures.open(
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
}

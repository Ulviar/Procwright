/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.ulviar.procwright.session.StreamSession;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StreamRuntimeStartupTest extends StreamRuntimeStartupTestSupport {

    @Test
    void openUsesTheStablePidCapturedBySessionRuntimeOnlyOnce() throws Exception {
        AssertionError secondPidFailure = new AssertionError("pid queried more than once");
        StatefulPidProcess process = new StatefulPidProcess(secondPidFailure);
        CountDownLatch processStarted = new CountDownLatch(1);
        AtomicInteger processFailures = new AtomicInteger();
        DiagnosticsSettings settings = DiagnosticsSettings.disabled().withListener(event -> {
            if (event.type() == DiagnosticEventType.PROCESS_STARTED) {
                processStarted.countDown();
            }
            if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                processFailures.incrementAndGet();
            }
        });
        StreamSession stream = null;
        try {
            stream = StreamRuntime.open(ptyPlan(process, settings));

            assertTrue(processStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, process.pidCalls());
            assertEquals(0, processFailures.get());
            assertTrue(process.isAlive());
        } finally {
            if (stream != null) {
                stream.close();
            } else {
                process.destroyForcibly();
            }
        }

        assertTrue(process.awaitDestroyed());
        assertFalse(process.isAlive());
    }

    @Test
    void listenClosesStdinDuringConstruction() throws Exception {
        CloseCountingOutputStream stdin = new CloseCountingOutputStream();
        ReadinessInputStream stdout = new ReadinessInputStream();
        ControllableProcess process = new ControllableProcess(stdout, InputStream.nullInputStream(), null, stdin);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(stdout.awaitReadStarted(), "stdout pump did not reach its readiness barrier");
            assertTrue(eventually(() -> stdin.closeCalls() == 1), "listen must close stdin during construction");
        } finally {
            stream.close();
        }

        assertEquals(1, stdin.closeCalls());
    }

    @Test
    void constructionErrorStopsTheAlreadyOpenedSession() {
        ControllableProcess process = new ControllableProcess();
        DefaultSession session = new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
        AssertionError constructionFailure = new AssertionError("stream construction failed");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> StreamRuntime.finishOpen(session, plan(), diagnostics(), (rawSession, plan, events) -> {
                    throw constructionFailure;
                }));

        assertSame(constructionFailure, thrown);
        assertFalse(process.isAlive(), "failed stream construction must close the opened process");
    }

    @Test
    void postCommitConstructionFailureClosesOwnedOutputAndStopsPumpsExactlyOnce() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true);
        ConstructionBlockingInputStream stdout = new ConstructionBlockingInputStream(processAlive);
        ConstructionBlockingInputStream stderr = new ConstructionBlockingInputStream(processAlive);
        ControllableProcess process =
                new ControllableProcess(stdout, stderr, null, OutputStream.nullOutputStream(), processAlive);
        AssertionError constructionFailure = new AssertionError("stdin close scheduling failed");
        CountDownLatch pumpsStopped = new CountDownLatch(2);
        CopyOnWriteArrayList<Thread> pumpThreads = new CopyOnWriteArrayList<>();
        PumpStarter trackingStarter = (namePrefix, task) -> {
            Thread thread = io.github.ulviar.procwright.internal.Threading.start(namePrefix, () -> {
                try {
                    task.run();
                } finally {
                    pumpsStopped.countDown();
                }
            });
            pumpThreads.add(thread);
            return thread;
        };
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch diagnosticBarrier = new CountDownLatch(1);
        DiagnosticEmitter eventDiagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        diagnosticBarrier.countDown();
                    }
                }),
                "listen",
                CommandEcho.empty());
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(3, 3, 6, (name, task) -> {
            if (name.startsWith("procwright-process-stdin-close-")) {
                awaitUninterruptibly(stdout.readStarted);
                awaitUninterruptibly(stderr.readStarted);
                throw constructionFailure;
            }
            Threading.start(name, task);
        });
        DefaultSession rawSession = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                () -> {},
                closeDispatcher,
                Threading::start);
        eventDiagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        eventDiagnostics.emit(DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "42"));

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> StreamRuntime.finishOpen(
                        rawSession,
                        plan(),
                        eventDiagnostics,
                        (session, streamPlan, streamDiagnostics) -> new DefaultStreamSession(
                                session,
                                streamPlan,
                                streamDiagnostics,
                                ZeroReadBackoff.exponential(),
                                trackingStarter)));

        assertSame(constructionFailure, thrown);
        assertTrue(process.awaitDestroyed(), "process cleanup must complete before owned output closes");
        assertTrue(stdout.awaitClose());
        assertTrue(stderr.awaitClose());
        assertTrue(pumpsStopped.await(1, TimeUnit.SECONDS), "committed pumps must terminate");
        assertTrue(pumpThreads.stream().noneMatch(Thread::isAlive));
        assertTrue(stdout.destroyedBeforeClose());
        assertTrue(stderr.destroyedBeforeClose());
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());

        rawSession.close();
        eventDiagnostics.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));
        assertTrue(diagnosticBarrier.await(2, TimeUnit.SECONDS));
        assertEquals(
                List.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.PROCESS_FAILED,
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        DiagnosticEventType.PROCESS_EXITED),
                events.stream().map(DiagnosticEvent::type).toList());
        assertEquals(1, stdout.closeCalls(), "raw-session fallback must not physically close owned stdout");
        assertEquals(1, stderr.closeCalls(), "raw-session fallback must not physically close owned stderr");
    }

    @Test
    void cleanupFailureIsSuppressedOnTheConstructionFailure() {
        AssertionError constructionFailure = new AssertionError("stream construction failed");
        AssertionError cleanupFailure = new AssertionError("stream cleanup failed");

        StreamRuntime.closePreserving(
                () -> {
                    throw cleanupFailure;
                },
                constructionFailure);

        assertArrayEquals(new Throwable[] {cleanupFailure}, constructionFailure.getSuppressed());
    }
}

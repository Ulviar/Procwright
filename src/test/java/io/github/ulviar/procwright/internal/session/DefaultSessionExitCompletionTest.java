/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.SessionLifecycleTestFixtures.awaitIgnoringInterrupts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DefaultSessionExitCompletionTest {

    @Test
    void failingInternalObserverUsesBestEffortReportingWithoutDelayingTerminalCompletion() throws Exception {
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        CountDownLatch laterObserverCalled = new CountDownLatch(1);
        session.observeExit((result, failure) -> laterObserverCalled.countDown());
        AssertionError observerFailure = new AssertionError("observer failed");
        session.observeExit((result, failure) -> {
            throw observerFailure;
        });
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        Thread closer = new Thread(session::close, "failing-internal-observer-close");
        closer.setDaemon(true);
        closer.setUncaughtExceptionHandler((ignored, failure) -> {
            assertSame(observerFailure, failure);
            handlerEntered.countDown();
            awaitIgnoringInterrupts(releaseHandler);
        });
        try {
            closer.start();

            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(laterObserverCalled.await(1, TimeUnit.SECONDS));
            session.onExit().get(1, TimeUnit.SECONDS);
            closer.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(closer.isAlive(), "failure reporting delayed terminal completion");
        } finally {
            releaseHandler.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(1));
            session.close();
        }
    }

    @Test
    void saturatedFailureReportingCannotDelayTerminalCleanup() throws Exception {
        BoundedFailureReporter reporter = BoundedFailureReporter.shared();
        assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
        CountDownLatch activeReports = new CountDownLatch(BoundedFailureReporter.SHARED_WORKER_CAPACITY);
        CountDownLatch releaseReports = new CountDownLatch(1);
        for (int index = 0; index < BoundedFailureReporter.SHARED_WORKER_CAPACITY; index++) {
            assertTrue(reporter.execute(Thread.currentThread(), () -> {
                activeReports.countDown();
                awaitIgnoringInterrupts(releaseReports);
            }));
        }
        assertTrue(activeReports.await(1, TimeUnit.SECONDS));
        for (int index = 0; index < BoundedFailureReporter.SHARED_QUEUE_CAPACITY; index++) {
            assertTrue(reporter.execute(Thread.currentThread(), () -> {}));
        }
        assertFalse(
                reporter.execute(Thread.currentThread(), () -> {}),
                "the reporter must be saturated before terminal completion");

        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
        CountDownLatch laterObserverCalled = new CountDownLatch(1);
        session.observeExit((result, failure) -> laterObserverCalled.countDown());
        session.observeExit((result, failure) -> {
            throw new AssertionError("observer failed while reporter was saturated");
        });
        Thread closer = new Thread(session::close, "saturated-failure-reporting-close");
        closer.setDaemon(true);
        try {
            closer.start();
            assertTrue(laterObserverCalled.await(1, TimeUnit.SECONDS));
            closer.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(closer.isAlive(), "saturated best-effort reporting pinned terminal cleanup");
            session.onExit().get(1, TimeUnit.SECONDS);
        } finally {
            releaseReports.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(1));
            session.close();
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
        }
    }

    @Test
    void publicExitFutureRemainsADefensiveCopy() throws Exception {
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        DefaultSession session = SessionTestFixtures.open(
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
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
}

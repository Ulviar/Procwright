/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultSessionExitPublicationTest extends DefaultSessionLifecycleTestSupport {

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
    void failingInternalObserverCannotRunAHostileHandlerOnTheTerminalPublisher() throws Exception {
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        DefaultSession session = new DefaultSession(
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
            assertFalse(closer.isAlive(), "failure reporting pinned the terminal publisher");
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
        CountDownLatch activeReports = new CountDownLatch(reporter.workerCapacity());
        CountDownLatch releaseReports = new CountDownLatch(1);
        for (int index = 0; index < reporter.workerCapacity(); index++) {
            assertTrue(reporter.execute(() -> {
                activeReports.countDown();
                awaitIgnoringInterrupts(releaseReports);
            }));
        }
        assertTrue(activeReports.await(1, TimeUnit.SECONDS));
        for (int index = 0; index < reporter.queueCapacity(); index++) {
            assertTrue(reporter.execute(() -> {}));
        }
        assertFalse(reporter.execute(() -> {}), "the reporter must be saturated before terminal publication");

        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream());
        DefaultSession session = new DefaultSession(
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
}

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

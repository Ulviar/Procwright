/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.openSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionExitBarrierTest {

    @Test
    void blockingPublicExitContinuationObservesReleasedOutputCleanupOwners() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultLineSession lineSession =
                new DefaultLineSession(openSession(process, dispatcher), LineSessionSettings.defaults());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean cleanupWasComplete = new AtomicBoolean();
        CompletableFuture<Void> continuation = lineSession.onExit().thenRun(() -> {
            cleanupWasComplete.set(lineSession.physicalOutputCleanup().isDone()
                    && dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0);
            continuationEntered.countDown();
            awaitUninterruptibly(releaseContinuation);
        });
        FutureTask<Void> closeTask = new FutureTask<>(() -> {
            lineSession.close();
            return null;
        });
        Thread closeThread = new Thread(closeTask, "procwright-line-blocking-continuation-test");
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
    void publicExitWaitsForFallbackOwnedPhysicalOutputClose() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("line stdout close starter failed");
        IOException physicalFailure = new IOException("line stdout physical close failed");
        FailingBlockingPhysicalCloseInputStream stdout = new FailingBlockingPhysicalCloseInputStream(physicalFailure);
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, (name, task) -> {
            if (name.contains("stdout-close")) {
                throw startFailure;
            }
            io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
        DefaultLineSession lineSession =
                new DefaultLineSession(openSession(process, dispatcher), LineSessionSettings.defaults());
        AtomicInteger startReports = new AtomicInteger();
        AtomicInteger physicalReports = new AtomicInteger();
        AtomicReference<Throwable> unexpectedReport = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == startFailure) {
                startReports.incrementAndGet();
            } else if (failure == physicalFailure) {
                physicalReports.incrementAndGet();
            } else {
                unexpectedReport.compareAndSet(null, failure);
            }
        });
        try {
            process.complete(0);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            assertFalse(lineSession.physicalOutputCleanup().isDone());
            assertFalse(lineSession.onExit().isDone(), "public helper exit must wait for fallback output settlement");

            stdout.releaseClose.countDown();
            lineSession.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(lineSession.physicalOutputCleanup().isDone());
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(1, stdout.closeCalls.get());
            assertEquals(1, startReports.get());
            assertEquals(1, physicalReports.get());
            assertEquals(null, unexpectedReport.get());
            assertEquals(0, startFailure.getSuppressed().length);
            assertEquals(0, physicalFailure.getSuppressed().length);
            assertTrue(eventually(() -> dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0));
        } finally {
            stdout.releaseClose.countDown();
            process.complete(143);
            try {
                lineSession.close();
            } catch (RuntimeException | Error expectedTerminalFailure) {
                assertSame(startFailure, expectedTerminalFailure);
            }
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static final class FailingBlockingPhysicalCloseInputStream extends InputStream {

        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();
        private final IOException failure;

        FailingBlockingPhysicalCloseInputStream(IOException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            throw failure;
        }
    }
}

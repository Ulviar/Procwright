/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.session.StreamExit;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class DefaultStreamSessionExitCoordinationTest extends DefaultStreamSessionTestSupport {

    @Test
    void streamTimeoutWatcherStopsAfterEarlyProcessExit() throws Exception {
        ControllableProcess process = new ControllableProcess(
                new ByteArrayInputStream("done\n".getBytes(StandardCharsets.UTF_8)), InputStream.nullInputStream());

        try (DefaultStreamSession session = new DefaultStreamSession(
                session(process), plan(chunk -> {}, Duration.ofSeconds(Long.MAX_VALUE)), diagnostics())) {
            process.complete(0);

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            session.timeoutWatcherStopped().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockingPublicExitContinuationObservesReleasedOutputCleanupOwners() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultStreamSession stream =
                new DefaultStreamSession(session(process, dispatcher), plan(chunk -> {}), diagnostics());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean cleanupWasComplete = new AtomicBoolean();
        CompletableFuture<Void> continuation = stream.onExit().thenRun(() -> {
            cleanupWasComplete.set(stream.physicalOutputCleanup().isDone()
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
                StreamSessionTestDependencies.withNanoTime(() -> nanoTime.getAndSet(50)));
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
}

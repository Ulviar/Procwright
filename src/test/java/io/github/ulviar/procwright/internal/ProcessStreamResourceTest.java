/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProcessStreamResourceTest extends ProcessIoResourcesTestSupport {

    @Test
    void asynchronousCloseDoesNotWaitForAnActiveRead() throws Exception {
        BlockingReadInputStream stdout = new BlockingReadInputStream(new IOException("close failed"));
        TrackingProcess process = new TrackingProcess(stdout, new TrackingInputStream());
        ProcessIoResources resources = ProcessIoResources.acquire(process, new BoundedCloseDispatcher(1, 3));
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch failureReported = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                resources.stdout().stream().read();
            } catch (IOException ignored) {
                // Closing the stream releases the fixture's blocked read.
            }
        });
        reader.setDaemon(true);
        reader.start();
        assertTrue(stdout.readStarted.await(1, TimeUnit.SECONDS));

        long started = System.nanoTime();
        resources.stdout().closeAsync("test-output-close-", failure -> {
            reported.set(failure);
            failureReported.countDown();
        });

        assertTrue(System.nanoTime() - started < Duration.ofMillis(250).toNanos());
        stdout.releaseRead.countDown();
        assertTrue(stdout.closeCompleted.await(1, TimeUnit.SECONDS));
        assertSame(
                stdout.closeFailure,
                resources.stdout().closeOutcome().get(1, TimeUnit.SECONDS).failure());
        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertSame(stdout.closeFailure, reported.get());
        reader.join(1_000);
        resources.closeAllAsync(ignored -> {});
    }

    @Test
    void closeIsClaimedExactlyOnceAcrossInlineAndAsyncCallers() throws Exception {
        TrackingProcess process = new TrackingProcess();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
        Thread first = new Thread(() -> resources.stdout().closeAsync("first-close-", ignored -> {}));
        Thread second = new Thread(() -> resources.stdout().closeAsync("second-close-", ignored -> {}));

        first.start();
        second.start();
        first.join(1_000);
        second.join(1_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(resources.stdout().closeOutcome().get(1, TimeUnit.SECONDS).failure());
        assertEquals(1, process.stdout.closeCalls.get());
        resources.closeAllAsync(ignored -> {});
        assertNull(resources.awaitClose(Duration.ofSeconds(1)));
    }

    @Test
    void acceptedCloseFallsBackWhenItsWorkerCannotStart() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("starter failed");
        IOException closeFailure = new IOException("close failed");
        TrackingInputStream stdout = failingInput(closeFailure);
        AtomicInteger starts = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, (name, task) -> {
            if (starts.getAndIncrement() == 0) {
                throw startFailure;
            }
            Threading.start(name, task);
        });
        ProcessIoResources resources =
                ProcessIoResources.acquire(new TrackingProcess(stdout, new TrackingInputStream()), dispatcher);

        assertSame(startFailure, assertThrows(IllegalStateException.class, () -> resources
                .stdout()
                .closeAsync("fallback-close-", ignored -> {})));

        Throwable outcome =
                resources.stdout().closeOutcome().get(1, TimeUnit.SECONDS).failure();
        assertSame(startFailure, outcome.getCause());
        assertEquals(java.util.List.of(closeFailure), java.util.List.of(outcome.getSuppressed()));
        assertEquals(1, stdout.closeCalls.get());
        resources.closeAllAsync(ignored -> {});
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void awaitCloseWaitsForPhysicalSettlement() throws Exception {
        BlockingCloseInputStream stdout = new BlockingCloseInputStream();
        ProcessIoResources resources = ProcessIoResources.acquire(
                new TrackingProcess(stdout, new TrackingInputStream()), new BoundedCloseDispatcher(3, 3));
        resources.closeAllAsync(ignored -> {});
        assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

        assertTrue(resources.awaitClose(Duration.ofMillis(20)) != null);
        stdout.releaseClose.countDown();

        assertNull(resources.awaitClose(Duration.ofSeconds(1)));
        assertEquals(1, stdout.closeCalls.get());
    }

    private static final class BlockingCloseInputStream extends TrackingInputStream {

        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
        }
    }

    private static final class BlockingReadInputStream extends TrackingInputStream {

        private final IOException closeFailure;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);

        private BlockingReadInputStream(IOException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public synchronized int read() {
            readStarted.countDown();
            awaitUninterruptibly(releaseRead);
            return -1;
        }

        @Override
        public synchronized void close() throws IOException {
            closeCalls.incrementAndGet();
            closeCompleted.countDown();
            throw closeFailure;
        }
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

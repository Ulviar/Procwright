/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.CloseTrackingInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.expect;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.openExpect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DefaultExpectOutputPumpLifecycleTest {

    @Test
    void helperConstructionFailureRollsBackTheProcessAndOutputResources() throws Exception {
        CloseTrackingInputStream stdout = new CloseTrackingInputStream(new byte[0]);
        CloseTrackingInputStream stderr = new CloseTrackingInputStream(new byte[0]);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        RejectedExecutionException startupFailure = new RejectedExecutionException("pump rejected");

        RejectedExecutionException actual = assertThrows(
                RejectedExecutionException.class,
                () -> openExpect(
                        process,
                        session -> new DefaultExpect(
                                session, ExpectSettings.defaults(), ZeroReadBackoff.exponential(), (name, task) -> {
                                    throw startupFailure;
                                })));

        assertEquals(startupFailure, actual);
        assertTrue(process.awaitDestroyed());
        assertTrue(stdout.awaitClose());
        assertTrue(stderr.awaitClose());
        assertFalse(process.isAlive());
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
    }

    @Test
    void closeStopsProcessBeforeBlockingOutputClosesAndDoesNotWaitForThem() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        BlockingCloseInputStream stderr = new BlockingCloseInputStream(processAlive);
        ControllableProcess process = new ControllableProcess(stdout, stderr, processAlive);
        DefaultExpect expect = expect(process);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> close = null;
        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            close = executor.submit(expect::close);

            assertTrue(process.awaitDestroyed(), "process cleanup must precede helper output closure");
            close.get(1, TimeUnit.SECONDS);
            assertTrue(expect.onExit().isDone());
            assertTrue(stdout.awaitCloseStarted());
            assertTrue(stderr.awaitCloseStarted());
            assertTrue(stdout.destroyedBeforeClose());
            assertTrue(stderr.destroyedBeforeClose());
            assertFalse(stdout.closeCompleted());
            assertFalse(stderr.closeCompleted());
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            if (close != null) {
                close.get(1, TimeUnit.SECONDS);
            }
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(stdout.awaitCloseCompleted());
        assertTrue(stderr.awaitCloseCompleted());
        expect.onExit().get(1, TimeUnit.SECONDS);
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
    }

    @Test
    void zeroLengthPumpsBackOffAndCloseExactlyOnceForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            CloseTrackingInputStream eofStream = new CloseTrackingInputStream(new byte[0]);
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : eofStream;
            InputStream stderr = zeroStdout ? eofStream : zeroStream;
            ControllableProcess process = new ControllableProcess(stdout, stderr);
            DefaultExpect expect = openExpect(
                    process,
                    session -> new DefaultExpect(session, ExpectSettings.defaults(), backoff, PumpStarter.threading()));
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads());

                expect.close();
                backoff.release();
                expect.onExit().get(1, TimeUnit.SECONDS);

                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive());
                assertEquals(1, zeroStream.reads());
                assertTrue(zeroStream.awaitClose());
                assertTrue(eofStream.awaitClose());
                assertEquals(1, zeroStream.closeCalls());
                assertEquals(1, eofStream.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                backoff.release();
                expect.close();
            }
        }
    }

    private static final class BlockingCloseInputStream extends InputStream {

        private final AtomicBoolean processAlive;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);
        private final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();

        private BlockingCloseInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(closeCompleted);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            destroyedBeforeClose.set(!processAlive.get());
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            closeRelease.countDown();
        }

        private boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        private boolean closeCompleted() {
            return closeCompleted.getCount() == 0;
        }

        private int closeCalls() {
            return closes.get();
        }
    }

    private static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class ZeroForeverInputStream extends InputStream {

        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
        }

        private int reads() {
            return reads.get();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private Thread readerThread() {
            return readerThread;
        }
    }
}

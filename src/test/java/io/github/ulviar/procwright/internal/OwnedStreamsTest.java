/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OwnedStreamsTest extends ProcessKernelProcessFixtureSupport {

    @Test
    void acquiresEachStableProcessStreamExactlyOnce() {
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());

        OwnedStreams streams = OwnedStreams.acquire(process);

        assertSame(process.stdin, streams.stdin().stream());
        assertSame(process.stdout, streams.stdout().stream());
        assertSame(process.stderr, streams.stderr().stream());
        assertEquals(1, process.stdinGets.get());
        assertEquals(1, process.stdoutGets.get());
        assertEquals(1, process.stderrGets.get());
        streams.closeAll();
    }

    @Test
    void logicalCloseIsExactOnceAndDoesNotWaitForPhysicalClose() throws Exception {
        BlockingCloseInputStream stdout = new BlockingCloseInputStream();
        TerminalProcess process = new TerminalProcess(stdout, new TrackingInputStream(), new TrackingOutputStream());
        OwnedStreams streams = OwnedStreams.acquire(process);
        FutureTask<Void> close = new FutureTask<>(() -> {
            streams.stdout().close();
            streams.stdout().close();
            return null;
        });
        Thread caller = new Thread(close, "owned-stream-close-caller");
        caller.setDaemon(true);
        caller.start();

        close.get(1, TimeUnit.SECONDS);
        assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));
        assertEquals(1, stdout.closeCalls.get());
        stdout.releaseClose.countDown();
        assertTrue(stdout.closeFinished.await(1, TimeUnit.SECONDS));
        streams.closeAll();
    }

    @Test
    void partialAcquisitionReleasesObservedStreamsWithoutOwningProcessLifecycle() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 3; failedOrdinal++) {
            IllegalStateException expected = new IllegalStateException("getter " + failedOrdinal);
            FailingGetterProcess process = new FailingGetterProcess(failedOrdinal, expected);

            RuntimeException actual = assertThrows(RuntimeException.class, () -> OwnedStreams.acquire(process));

            assertSame(expected, actual);
            assertTrue(process.isAlive(), "stream ownership must not stop the process");
            if (failedOrdinal >= 2) {
                assertTrue(eventually(() -> process.stdin.closeCalls() == 1));
            }
            if (failedOrdinal >= 3) {
                assertTrue(eventually(() -> process.stdout.closeCalls() == 1));
            }
            assertEquals(failedOrdinal >= 2 ? 1 : 0, process.stdin.closeCalls());
            assertEquals(failedOrdinal >= 3 ? 1 : 0, process.stdout.closeCalls());
            assertEquals(0, process.stderr.closeCalls());
        }
    }

    private static final class BlockingCloseInputStream extends TrackingInputStream {

        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            closeFinished.countDown();
        }
    }

    private static final class FailingGetterProcess extends TerminalProcess {

        private final int failedOrdinal;
        private final RuntimeException getterFailure;

        private FailingGetterProcess(int failedOrdinal, RuntimeException getterFailure) {
            super(new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
            this.failedOrdinal = failedOrdinal;
            this.getterFailure = getterFailure;
        }

        @Override
        public OutputStream getOutputStream() {
            stdinGets.incrementAndGet();
            failAt(1);
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            stdoutGets.incrementAndGet();
            failAt(2);
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrGets.incrementAndGet();
            failAt(3);
            return stderr;
        }

        private void failAt(int ordinal) {
            if (ordinal == failedOrdinal) {
                throw getterFailure;
            }
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

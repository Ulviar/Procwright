/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SessionRawOutputOwnershipContractTest extends SessionOutputOwnershipTestSupport {

    @Test
    void naturalExitLeavesRawOutputOwnedByTheCaller() throws Exception {
        CloseCountingInputStream stdout = new CloseCountingInputStream();
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);
        InputStream rawStdout = session.stdout();

        process.completeExit(0);
        session.onExit().get(2, TimeUnit.SECONDS);

        assertEquals(0, stdout.closeCalls());
        assertDoesNotThrow(rawStdout::close);
        assertTrue(stdout.awaitClose());
        assertEquals(1, stdout.closeCalls());
    }

    @Test
    void sessionCloseAfterNaturalExitClosesCallerOwnedRawOutput() throws Exception {
        CloseCountingInputStream stdout = new CloseCountingInputStream();
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);

        process.completeExit(0);
        session.onExit().get(2, TimeUnit.SECONDS);
        session.close();

        assertTrue(stdout.awaitClose());
        assertEquals(1, stdout.closeCalls());
    }

    @Test
    void rawAndLifecycleClosePhysicalOutputDelegateExactlyOnce() throws Exception {
        CloseCountingInputStream stdout = new CloseCountingInputStream();
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);
        InputStream firstRawStdout = session.stdout();
        InputStream secondRawStdout = session.stdout();

        firstRawStdout.close();
        secondRawStdout.close();
        process.completeExit(0);
        session.onExit().get(2, TimeUnit.SECONDS);

        assertEquals(1, stdout.closeCalls());
    }

    @Test
    void concurrentRawAndLifecycleClosePhysicalOutputDelegateExactlyOnce() throws Exception {
        BlockingCloseCountingInputStream stdout = new BlockingCloseCountingInputStream();
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);
        InputStream rawStdout = session.stdout();
        CompletableFuture<Throwable> rawCloseOutcome = new CompletableFuture<>();
        Thread rawCloser = new Thread(() -> {
            try {
                rawStdout.close();
                rawCloseOutcome.complete(null);
            } catch (Throwable failure) {
                rawCloseOutcome.complete(failure);
            }
        });
        rawCloser.setDaemon(true);
        rawCloser.start();
        try {
            assertTrue(stdout.awaitCloseStarted());

            process.completeExit(0);

            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertEquals(1, stdout.closeCalls());
        } finally {
            stdout.releaseClose();
            process.completeExit(0);
            rawCloser.join(TimeUnit.SECONDS.toMillis(2));
            session.close();
        }

        assertFalse(rawCloser.isAlive());
        assertNull(rawCloseOutcome.get(2, TimeUnit.SECONDS));
        assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertEquals(1, stdout.closeCalls());
    }

    @Test
    void failingRawOutputCloseDoesNotRewriteProcessExit() throws Exception {
        IOException closeFailure = new IOException("stdout close failed");
        AtomicInteger closeCalls = new AtomicInteger();
        InputStream stdout = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public void close() throws IOException {
                closeCalls.incrementAndGet();
                throw closeFailure;
            }
        };
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);

        IOException observed = assertThrows(IOException.class, session.stdout()::close);
        assertSame(closeFailure, observed);
        assertFalse(session.onExit().isDone());

        process.completeExit(0);

        assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertEquals(1, closeCalls.get());
    }

    private static final class CloseCountingInputStream extends ByteArrayInputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private CloseCountingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            try {
                super.close();
            } finally {
                closed.countDown();
            }
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(2, TimeUnit.SECONDS);
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class BlockingCloseCountingInputStream extends ByteArrayInputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        private BlockingCloseCountingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseClose.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            super.close();
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            releaseClose.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }
}

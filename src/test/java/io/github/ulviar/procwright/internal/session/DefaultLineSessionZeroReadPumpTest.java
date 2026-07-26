/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionZeroReadPumpTest extends DefaultLineSessionTestSupport {

    @Test
    void zeroLengthPumpsBackOffAndStopAfterCloseForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : InputStream.nullInputStream();
            InputStream stderr = zeroStdout ? InputStream.nullInputStream() : zeroStream;
            ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultLineSession lineSession = new DefaultLineSession(
                    rawSession, LineSessionSettings.defaults(), LineSessionTestDependencies.withBackoff(backoff));
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads(), "the pump must enter backoff before attempting another read");

                try {
                    lineSession.close();
                } finally {
                    backoff.release();
                }
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive(), "line pump thread must terminate after close");
                assertEquals(1, zeroStream.reads(), "close during backoff must prevent another read");
                assertFalse(process.isAlive());
            } finally {
                try {
                    backoff.release();
                } finally {
                    lineSession.close();
                }
            }
        }
    }

    @Test
    void interruptedZeroLengthPumpRestoresInterruptStatusBeforeStopping() throws Exception {
        ZeroForeverInputStream stdout = new ZeroForeverInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            assertTrue(stdout.awaitFirstRead());
            Thread readerThread = stdout.readerThread();

            readerThread.interrupt();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(readerThread.isAlive(), "interrupted line pump thread must terminate");
            assertTrue(readerThread.isInterrupted(), "line pump must restore its interrupted status");
            assertFalse(
                    lineSession.transcript().malformed(),
                    "interrupting zero-read backoff must not fabricate malformed output");
            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> lineSession.request("request"));
            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertTrue(failure.getMessage().contains("stdout output pump failed"));
            assertTrue(failure.getCause() instanceof CommandExecutionException);
            lineSession.onExit().handle((ignored, exitFailure) -> null).get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    private static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    private static final class ZeroForeverInputStream extends InputStream {

        final CountDownLatch firstRead = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            // Deliberately ignore close so the pump must observe its owning session state.
        }

        void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
            firstRead.countDown();
        }

        boolean awaitFirstRead() throws InterruptedException {
            return firstRead.await(1, TimeUnit.SECONDS);
        }

        int reads() {
            return reads.get();
        }

        Thread readerThread() {
            return readerThread;
        }
    }
}

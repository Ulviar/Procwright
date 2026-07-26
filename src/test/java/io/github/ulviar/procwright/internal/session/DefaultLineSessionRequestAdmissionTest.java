/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.BlockingUntilClosedInputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ReplyingOutputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ResponseInputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.captureFailure;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.openSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionRequestAdmissionTest {

    @Test
    void serializedWaiterTimeoutBeforeAdmissionLeavesLineSessionReusableAndWritesNothing() throws Exception {
        assertRecoverableSerializedWaiterFailure(false);
    }

    @Test
    void serializedWaiterInterruptionBeforeAdmissionLeavesLineSessionReusableAndRestoresInterrupt() throws Exception {
        assertRecoverableSerializedWaiterFailure(true);
    }

    private static void assertRecoverableSerializedWaiterFailure(boolean interrupt) throws Exception {
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        AtomicInteger responses = new AtomicInteger();
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            if (responses.getAndIncrement() == 0) {
                firstResponseStarted.countDown();
                awaitUninterruptibly(releaseFirstResponse);
            }
            return List.of(reader.readLine());
        });
        ControlledRequestLockWaiter lockWaiter = new ControlledRequestLockWaiter();
        DefaultLineSession lineSession = new DefaultLineSession(
                openSession(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())),
                settings,
                LineSessionTestDependencies.withRequestLockWaiter(lockWaiter));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
        try {
            Future<LineResponse> active = executor.submit(() -> lineSession.request("active"));
            assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));
            Future<Throwable> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> lineSession.request("not-admitted"));
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(lockWaiter.awaitContended(), "second request did not enter the serialization wait");

            if (interrupt) {
                Objects.requireNonNull(waiterThread.get(), "waiterThread").interrupt();
            } else {
                lockWaiter.expire();
            }
            LineSessionException failure =
                    assertInstanceOf(LineSessionException.class, waiter.get(2, TimeUnit.SECONDS));

            assertEquals(
                    interrupt ? LineSessionException.Reason.FAILURE : LineSessionException.Reason.TIMEOUT,
                    failure.reason());
            if (interrupt) {
                assertEquals("Interrupted while waiting to start line request", failure.getMessage());
                assertInstanceOf(InterruptedException.class, failure.getCause());
            } else {
                assertEquals("Line request timed out", failure.getMessage());
                assertNull(failure.getCause());
            }
            assertEquals(interrupt, waiterInterruptRestored.get());
            assertEquals("active\n", stdin.writtenText());
            assertFalse(lineSession.onExit().isDone());

            releaseFirstResponse.countDown();
            assertEquals("ok", active.get(2, TimeUnit.SECONDS).text());
            assertEquals("ok", lineSession.request("retry").text());
            assertEquals("active\nretry\n", stdin.writtenText());
            assertFalse(lineSession.onExit().isDone());
        } finally {
            releaseFirstResponse.countDown();
            lockWaiter.expire();
            lineSession.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void fatalErrorSelectedBeforeSerializedWaiterInterruptionWinsByIdentityAndRestoresInterrupt() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(true, true);
    }

    @Test
    void closedSelectedBeforeSerializedWaiterInterruptionWinsAndRestoresInterrupt() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(false, true);
    }

    @Test
    void fatalErrorSelectedBeforeSerializedWaiterTimeoutWinsByIdentity() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(true, false);
    }

    @Test
    void closedSelectedBeforeSerializedWaiterTimeoutWins() throws Exception {
        assertSelectedTerminalWinsSerializedWaiterFailure(false, false);
    }

    private static void assertSelectedTerminalWinsSerializedWaiterFailure(boolean fatal, boolean interrupt)
            throws Exception {
        AssertionError fatalError = new AssertionError("fatal output failure selected while line request waits");
        InputStream stdout = fatal ? new GatedErrorInputStream(fatalError) : new BlockingUntilClosedInputStream();
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            responseStarted.countDown();
            awaitUninterruptibly(releaseResponse);
            return List.of(reader.readLine());
        });
        ControlledRequestLockWaiter lockWaiter = new ControlledRequestLockWaiter();
        DefaultLineSession lineSession = new DefaultLineSession(
                openSession(process), settings, LineSessionTestDependencies.withRequestLockWaiter(lockWaiter));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> active = executor.submit(() -> captureFailure(() -> lineSession.request("active")));
            assertTrue(responseStarted.await(1, TimeUnit.SECONDS));
            Future<Throwable> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> lineSession.request("waiter"));
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(lockWaiter.awaitContended(), "second request did not enter the serialization wait");

            if (fatal) {
                ((GatedErrorInputStream) stdout).releaseFailure();
                lineSession.onExit().get(2, TimeUnit.SECONDS);
            } else {
                lineSession.close();
            }
            int fatalSuppressedBeforeWaiter = fatalError.getSuppressed().length;

            if (interrupt) {
                Objects.requireNonNull(waiterThread.get(), "waiterThread").interrupt();
            } else {
                lockWaiter.expire();
            }
            Throwable waiterFailure = waiter.get(2, TimeUnit.SECONDS);

            if (fatal) {
                assertSame(fatalError, waiterFailure);
                assertEquals(fatalSuppressedBeforeWaiter, fatalError.getSuppressed().length);
            } else {
                LineSessionException closed = assertInstanceOf(LineSessionException.class, waiterFailure);
                assertEquals(LineSessionException.Reason.CLOSED, closed.reason());
            }
            assertEquals(interrupt, waiterInterruptRestored.get());
            assertEquals("active\n", stdin.toString(StandardCharsets.UTF_8));

            releaseResponse.countDown();
            Throwable activeFailure = active.get(2, TimeUnit.SECONDS);
            if (fatal) {
                assertSame(fatalError, activeFailure);
            } else {
                assertEquals(
                        LineSessionException.Reason.CLOSED,
                        assertInstanceOf(LineSessionException.class, activeFailure)
                                .reason());
            }
            Throwable followUp = captureFailure(() -> lineSession.request("follow-up"));
            if (fatal) {
                assertSame(fatalError, followUp);
            } else {
                assertEquals(
                        LineSessionException.Reason.CLOSED,
                        assertInstanceOf(LineSessionException.class, followUp).reason());
            }
            assertEquals("active\n", stdin.toString(StandardCharsets.UTF_8));
        } finally {
            releaseResponse.countDown();
            lockWaiter.expire();
            lineSession.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static final class GatedErrorInputStream extends InputStream {

        private final Error failure;
        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        private GatedErrorInputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseFailure);
            throw failure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static final class ControlledRequestLockWaiter implements SerializedRequestGate.Waiter {

        private final CountDownLatch contended = new CountDownLatch(1);
        private final CountDownLatch expired = new CountDownLatch(1);

        @Override
        public boolean acquire(java.util.concurrent.locks.ReentrantLock lock, long remainingNanos)
                throws InterruptedException {
            if (lock.tryLock()) {
                return true;
            }
            contended.countDown();
            expired.await();
            return false;
        }

        private boolean awaitContended() throws InterruptedException {
            return contended.await(1, TimeUnit.SECONDS);
        }

        private void expire() {
            expired.countDown();
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ReplyingOutputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ResponseInputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.captureFailure;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.openSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

final class DefaultLineSessionWriteAdmissionTest {

    @Test
    void writeAdmissionTimeoutLeavesSessionOpenAndCannotWriteLate() throws Exception {
        BoundedTaskLimiter limiter = BoundedTaskLimits.BLOCKING_WRITES;
        int capacity = limiter.availablePermits();
        assertEquals(32, capacity, "another test leaked a production line-write permit");
        CountDownLatch callbacksStarted = new CountDownLatch(capacity);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        ExecutorService occupiers = Executors.newFixedThreadPool(capacity);
        List<DefaultLineSession> occupyingSessions = new ArrayList<>(capacity);
        List<BlockingReplyOutputStream> occupyingStdinStreams = new ArrayList<>(capacity);
        List<Future<LineResponse>> occupied = new ArrayList<>(capacity);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultSession rawSession = openSession(process);
        DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults());
        try {
            for (int index = 0; index < capacity; index++) {
                ResponseInputStream occupyingStdout = new ResponseInputStream();
                BlockingReplyOutputStream occupyingStdin =
                        new BlockingReplyOutputStream(occupyingStdout, callbacksStarted, releaseCallbacks);
                ControllableProcess occupyingProcess =
                        new ControllableProcess(occupyingStdin, occupyingStdout, InputStream.nullInputStream());
                DefaultLineSession occupyingSession =
                        new DefaultLineSession(openSession(occupyingProcess), LineSessionSettings.defaults());
                occupyingStdinStreams.add(occupyingStdin);
                occupyingSessions.add(occupyingSession);
                occupied.add(occupiers.submit(() -> occupyingSession.requestEncoded(
                        "occupy\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10))));
            }
            assertTrue(callbacksStarted.await(5, TimeUnit.SECONDS), "line-write callbacks did not occupy all permits");

            LineSessionException timeout = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "never-written\n".getBytes(StandardCharsets.UTF_8), Duration.ofMillis(50)));

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertEquals(0, stdin.writeCalls());
            assertFalse(lineSession.onExit().isDone());

            releaseCallbacks.countDown();
            for (Future<LineResponse> task : occupied) {
                assertEquals("ok", task.get(5, TimeUnit.SECONDS).text());
            }
            BoundedTaskRunner.run(
                    limiter, "procwright-line-write-saturation-barrier-", deadline(Duration.ofSeconds(1)), () -> null);
            assertEquals(0, stdin.writeCalls(), "timed-out request wrote after writer capacity became available");

            LineResponse response =
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1));
            assertEquals("ok", response.text());
            assertEquals(1, stdin.writeCalls());
            assertEquals("retry\n", stdin.writtenText());
        } finally {
            releaseCallbacks.countDown();
            try {
                lineSession.close();
            } finally {
                occupyingSessions.forEach(DefaultLineSession::close);
                assertTrue(stdin.awaitClosed());
                for (BlockingReplyOutputStream occupyingStdin : occupyingStdinStreams) {
                    assertTrue(occupyingStdin.awaitClosed());
                }
                stdout.close();
                occupiers.shutdownNow();
                assertTrue(occupiers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(capacity, limiter.availablePermits());
    }

    @Test
    void callerInterruptWhileWriteAdmissionIsSaturatedIsRetrySafeAndRestoresState() throws Exception {
        BoundedTaskLimiter limiter = BoundedTaskLimits.BLOCKING_WRITES;
        int capacity = limiter.availablePermits();
        assertEquals(32, capacity, "another test leaked a production line-write permit");
        List<BoundedTaskPermit> reservations = new ArrayList<>(capacity);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        CountDownLatch writeAdmissionAttempted = new CountDownLatch(1);
        DefaultLineSession lineSession = new DefaultLineSession(
                openSession(process),
                LineSessionSettings.defaults(),
                LineSessionTestDependencies.withTaskRunner(
                        (writeLimiter, threadPrefix, deadlineNanos, handoff, task) -> {
                            writeAdmissionAttempted.countDown();
                            BoundedTaskTestSupport.runTracked(writeLimiter, threadPrefix, deadlineNanos, handoff, task);
                        }));
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread caller = new Thread(
                () -> {
                    observedFailure.set(captureFailure(() -> lineSession.requestEncoded(
                            "never-written\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30))));
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                },
                "procwright-interrupted-line-caller-test");
        caller.setDaemon(true);
        try {
            for (int index = 0; index < capacity; index++) {
                reservations.add(limiter.acquire(deadline(Duration.ofSeconds(1))));
            }

            caller.start();
            assertTrue(writeAdmissionAttempted.await(5, TimeUnit.SECONDS), "caller did not begin write admission");
            assertEquals(0, limiter.availablePermits());
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(caller.isAlive(), "interrupted caller did not return before the watchdog");
            assertTrue(observedFailure.get() instanceof LineSessionException);
            LineSessionException interrupted = (LineSessionException) observedFailure.get();
            assertEquals(LineSessionException.Reason.FAILURE, interrupted.reason());
            assertTrue(interrupted.getCause() instanceof InterruptedException);
            assertTrue(interruptRestored.get());
            assertEquals(0, stdin.writeCalls());
            assertFalse(lineSession.onExit().isDone());
            assertEquals(0, limiter.availablePermits());

            reservations.forEach(BoundedTaskPermit::close);
            BoundedTaskRunner.run(
                    limiter, "procwright-interrupted-line-barrier-", deadline(Duration.ofSeconds(5)), () -> null);
            assertEquals(0, stdin.writeCalls(), "interrupted request wrote after admission capacity was restored");

            LineResponse response =
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
            assertEquals("ok", response.text());
            assertEquals("retry\n", stdin.writtenText());
            assertEquals(capacity, limiter.availablePermits());
        } finally {
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));
            reservations.forEach(BoundedTaskPermit::close);
            try {
                lineSession.close();
            } finally {
                assertTrue(stdin.awaitClosed());
                stdout.close();
            }
        }
        assertEquals(capacity, limiter.availablePermits());
    }

    @Test
    void threadStartRejectionIsTypedRetrySafeAndLeavesLineSessionReusable() throws Exception {
        BoundedTaskLimiter limiter = BoundedTaskLimits.BLOCKING_WRITES;
        int capacity = limiter.availablePermits();
        assertEquals(32, capacity, "another test leaked a production line-write permit");
        SecurityException rejection = new SecurityException("line writer start denied");
        AtomicBoolean rejectNextStart = new AtomicBoolean(true);
        AtomicInteger threadSequence = new AtomicInteger();
        AtomicReference<Thread> rejectedThread = new AtomicReference<>();
        BoundedTaskTestSupport.TaskThreadFactory threadFactory = (threadPrefix, task) -> {
            Thread thread;
            if (rejectNextStart.compareAndSet(true, false)) {
                thread = new Thread(task, threadPrefix + threadSequence.getAndIncrement()) {
                    @Override
                    public synchronized void start() {
                        super.start();
                        throw rejection;
                    }
                };
                rejectedThread.set(thread);
            } else {
                thread = new Thread(task, threadPrefix + threadSequence.getAndIncrement());
            }
            thread.setDaemon(true);
            return thread;
        };
        LineRequestWriter.TaskRunner taskRunner =
                (writeLimiter, threadPrefix, deadlineNanos, handoff, task) -> BoundedTaskTestSupport.runTracked(
                        writeLimiter, threadPrefix, deadlineNanos, handoff, threadFactory, task);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultLineSession lineSession = new DefaultLineSession(
                openSession(process),
                LineSessionSettings.defaults(),
                LineSessionTestDependencies.withTaskRunner(taskRunner));
        try {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "never-written\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10)));

            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertSame(rejection, failure.getCause());
            rejectedThread.get().join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(rejectedThread.get().isAlive());
            assertEquals(0, stdin.writeCalls());
            assertFalse(lineSession.onExit().isDone());
            assertEquals(capacity, limiter.availablePermits());

            LineResponse response =
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
            assertEquals("ok", response.text());
            assertEquals("retry\n", stdin.writtenText());
        } finally {
            try {
                lineSession.close();
            } finally {
                assertTrue(stdin.awaitClosed());
                stdout.close();
            }
        }
        assertEquals(capacity, limiter.availablePermits());
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static final class BlockingReplyOutputStream extends OutputStream {

        private static final byte[] RESPONSE = "ok\n".getBytes(StandardCharsets.UTF_8);

        private final ResponseInputStream responses;
        private final CountDownLatch callbackStarted;
        private final CountDownLatch releaseCallback;
        private final CountDownLatch closed = new CountDownLatch(1);

        private BlockingReplyOutputStream(
                ResponseInputStream responses, CountDownLatch callbackStarted, CountDownLatch releaseCallback) {
            this.responses = responses;
            this.callbackStarted = callbackStarted;
            this.releaseCallback = releaseCallback;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            callbackStarted.countDown();
            awaitUninterruptibly(releaseCallback);
            responses.publish(RESPONSE);
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }
    }
}

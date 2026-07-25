/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

final class DefaultLineSessionRequestAdmissionTest extends DefaultLineSessionRequestAdmissionTestSupport {

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
                session(new ControllableProcess(stdin, stdout, InputStream.nullInputStream())),
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
                session(process), settings, LineSessionTestDependencies.withRequestLockWaiter(lockWaiter));
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
        DefaultSession rawSession = session(process);
        DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults());
        try {
            for (int index = 0; index < capacity; index++) {
                ResponseInputStream occupyingStdout = new ResponseInputStream();
                BlockingReplyOutputStream occupyingStdin =
                        new BlockingReplyOutputStream(occupyingStdout, callbacksStarted, releaseCallbacks);
                ControllableProcess occupyingProcess =
                        new ControllableProcess(occupyingStdin, occupyingStdout, InputStream.nullInputStream());
                DefaultLineSession occupyingSession =
                        new DefaultLineSession(session(occupyingProcess), LineSessionSettings.defaults());
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
                session(process),
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
                session(process),
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

    @Test
    void callerInterruptAfterControlledPartialWriteClosesSessionAndPreservesTypedFailure() throws Exception {
        BoundedTaskLimiter limiter = BoundedTaskLimits.BLOCKING_WRITES;
        int baselineCapacity = limiter.availablePermits();
        assertEquals(32, baselineCapacity, "another test leaked a production line-write permit");
        BlockingAfterFirstByteOutputStream stdin = new BlockingAfterFirstByteOutputStream();
        BlockingUntilClosedInputStream stdout = new BlockingUntilClosedInputStream();
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        CountDownLatch writerWrapperCompleted = new CountDownLatch(1);
        BoundedTaskTestSupport.TaskThreadFactory threadFactory = (threadPrefix, task) -> {
            Thread thread = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            writerWrapperCompleted.countDown();
                        }
                    },
                    threadPrefix + "controlled");
            thread.setDaemon(true);
            return thread;
        };
        DefaultLineSession lineSession = new DefaultLineSession(
                rawSession,
                LineSessionSettings.defaults(),
                LineSessionTestDependencies.withTaskRunner(
                        (writeLimiter, threadPrefix, deadlineNanos, handoff, task) -> BoundedTaskTestSupport.runTracked(
                                writeLimiter, threadPrefix, deadlineNanos, handoff, threadFactory, task)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> requestCaller = new AtomicReference<>();
        try {
            Future<Throwable> request = executor.submit(() -> {
                requestCaller.set(Thread.currentThread());
                return captureFailure(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30)));
            });
            assertTrue(stdin.awaitFirstByte(), "writer did not reach the controlled partial-write boundary");
            requestCaller.get().interrupt();

            Throwable thrown = request.get(10, TimeUnit.SECONDS);
            assertTrue(thrown instanceof LineSessionException);
            LineSessionException interrupted = (LineSessionException) thrown;
            assertEquals(LineSessionException.Reason.FAILURE, interrupted.reason());
            assertTrue(interrupted.getCause() instanceof InterruptedException);
            assertArrayEquals(new byte[] {'r'}, stdin.writtenBytes());
            assertTrue(stdin.awaitWriterStopped());
            assertTrue(stdin.wasInterrupted());
            assertTrue(
                    writerWrapperCompleted.await(5, TimeUnit.SECONDS),
                    "writer wrapper did not complete after the delegate returned");
            assertEquals(
                    baselineCapacity,
                    limiter.availablePermits(),
                    "partial-write interrupt did not return the write permit after full wrapper completion");
            lineSession.onExit().get(1, TimeUnit.SECONDS);

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
            assertEquals(1, stdin.writeCalls());
        } finally {
            stdin.releaseWriter();
            try {
                lineSession.close();
            } finally {
                assertTrue(stdin.awaitClosed());
                stdout.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
        assertEquals(baselineCapacity, limiter.availablePermits());
    }

    @Test
    void delegateIllegalStateExceptionIsFailureAndWriterIsFailStopped() throws Exception {
        IllegalStateException writeFailure = new IllegalStateException("delegate state failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(writeFailure, followUp.getCause().getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }

    @Test
    void delegateIoFailureIsBrokenPipeAndRemainsTheTerminalReason() throws Exception {
        int baselineWriteCapacity = BoundedTaskLimits.BLOCKING_WRITES.availablePermits();
        IOException writeFailure = new IOException("pipe write failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.BROKEN_PIPE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertEquals(baselineWriteCapacity, BoundedTaskLimits.BLOCKING_WRITES.availablePermits());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.BROKEN_PIPE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(writeFailure, followUp.getCause().getCause());
            assertEquals(1, stdin.writeCalls());
        }
        assertEquals(baselineWriteCapacity, BoundedTaskLimits.BLOCKING_WRITES.availablePermits());
    }

    @Test
    void delegateErrorClosesSessionAndIsRethrownByIdentity() throws Exception {
        AssertionError writeFailure = new AssertionError("delegate invariant failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            AssertionError thrown = assertThrows(
                    AssertionError.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertSame(writeFailure, thrown);
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertSame(writeFailure, followUp.getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }
}

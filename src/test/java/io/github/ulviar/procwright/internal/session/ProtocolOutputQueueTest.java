/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolOutputQueueTest {

    @Test
    void processExitBetweenEofPublicationAndTerminalClaimCanonicalizesProcessExited() throws Exception {
        AtomicReference<OptionalInt> exitCode = new AtomicReference<>(OptionalInt.empty());
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                1,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                System::nanoTime,
                () -> {},
                () -> {},
                exitCode::get,
                () -> {
                    readEntered.countDown();
                    await(releaseRead);
                });
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                queue.peek(
                        new byte[1],
                        0,
                        1,
                        new ProtocolOutputQueue.ReadWindow(),
                        System.nanoTime() + Duration.ofSeconds(2).toNanos(),
                        RECORDING_FAILURES,
                        event -> event);
            } catch (Throwable failure) {
                observed.set(failure);
            }
        });
        reader.start();

        try {
            assertTrue(readEntered.await(1, TimeUnit.SECONDS));
            queue.eof();
            exitCode.set(OptionalInt.of(17));
        } finally {
            releaseRead.countDown();
            reader.join(Duration.ofSeconds(1).toMillis());
        }

        assertFalse(reader.isAlive());
        ProtocolSessionException failure = (ProtocolSessionException) observed.get();
        assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, failure.reason());
    }

    @Test
    void blockedExitSnapshotDoesNotRetainQueueMonitorOrStopTerminalReplacement() throws Exception {
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        CountDownLatch replacementRead = new CountDownLatch(1);
        IllegalStateException replacementCause = new IllegalStateException("replacement");
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                1,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                System::nanoTime,
                () -> {},
                () -> {},
                () -> {
                    snapshotEntered.countDown();
                    await(releaseSnapshot);
                    return OptionalInt.of(17);
                },
                null);
        queue.eof();
        AtomicReference<Throwable> firstRead = new AtomicReference<>();
        AtomicReference<Throwable> replacement = new AtomicReference<>();
        Thread reader = new Thread(() -> firstRead.set(captureFailure(() ->
                queue.readUnsignedByte(System.nanoTime() + Duration.ofSeconds(2).toNanos(), RECORDING_FAILURES))));
        Thread replacingReader = new Thread(() -> {
            queue.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, replacementCause);
            replacement.set(captureFailure(() -> queue.readUnsignedByte(
                    System.nanoTime() + Duration.ofSeconds(2).toNanos(), RECORDING_FAILURES)));
            replacementRead.countDown();
        });
        reader.start();

        try {
            assertTrue(snapshotEntered.await(1, TimeUnit.SECONDS));
            replacingReader.start();
            assertTrue(
                    replacementRead.await(1, TimeUnit.SECONDS), "a blocked exit snapshot retained the queue monitor");
        } finally {
            releaseSnapshot.countDown();
            reader.join(TimeUnit.SECONDS.toMillis(1));
            replacingReader.join(TimeUnit.SECONDS.toMillis(1));
        }

        assertFalse(reader.isAlive());
        assertFalse(replacingReader.isAlive());
        ProtocolSessionException firstFailure = (ProtocolSessionException) firstRead.get();
        ProtocolSessionException replacementFailure = (ProtocolSessionException) replacement.get();
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, firstFailure.reason());
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, replacementFailure.reason());
        assertSame(replacementCause, firstFailure.getCause());
        assertSame(replacementCause, replacementFailure.getCause());
    }

    @Test
    void runtimeFailureFromExitSnapshotRetainsEof() {
        RuntimeException unavailable = new IllegalStateException("snapshot unavailable");
        assertExitSnapshotFailureRetainsEof(unavailable);
    }

    @Test
    void errorFromExitSnapshotRetainsEof() {
        Error unavailable = new AssertionError("snapshot unavailable");
        assertExitSnapshotFailureRetainsEof(unavailable);
    }

    @Test
    void expiredDeadlineSkipsExitSnapshotAndRetainsEof() {
        ControlledNanoTime nanoTime = new ControlledNanoTime();
        AtomicInteger snapshots = new AtomicInteger();
        long deadline = 42;
        nanoTime.set(deadline);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                1,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                nanoTime::read,
                () -> {},
                () -> {},
                () -> {
                    snapshots.incrementAndGet();
                    return OptionalInt.of(17);
                },
                null);
        queue.eof();

        ProtocolSessionException eof = assertThrows(
                ProtocolSessionException.class, () -> queue.readUnsignedByte(deadline, RECORDING_FAILURES));

        assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
        assertEquals(0, snapshots.get());
    }

    @Test
    void exitSnapshotCompletingAfterDeadlineRetainsEof() {
        ControlledNanoTime nanoTime = new ControlledNanoTime();
        long deadline = 42;
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                1,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                nanoTime::read,
                () -> {},
                () -> {},
                () -> {
                    nanoTime.set(deadline);
                    return OptionalInt.of(17);
                },
                null);
        queue.eof();

        ProtocolSessionException eof = assertThrows(
                ProtocolSessionException.class, () -> queue.readUnsignedByte(deadline, RECORDING_FAILURES));

        assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
    }

    @Test
    void transactionalWindowCommitsOnlyMatchedPrefixAndPreservesSuffix() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a', '|', 'b', '|'});
        byte[] target = new byte[8];
        ProtocolOutputQueue.ReadWindow window = new ProtocolOutputQueue.ReadWindow();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();

        int available = queue.peek(target, 0, target.length, window, deadline, RECORDING_FAILURES, event -> event);
        assertEquals(4, available);
        queue.commit(window, 2, ignored -> {}, RECORDING_FAILURES, event -> event);

        assertEquals(2, queue.pendingBytes());
        assertEquals('b', queue.readUnsignedByte(deadline, RECORDING_FAILURES));
    }

    @Test
    void terminalReplacementWinsAWindowCommitRaceWithoutPublishingPeekedBytes() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(new byte[] {'a', '\n'});
        byte[] target = new byte[8];
        ProtocolOutputQueue.ReadWindow window = new ProtocolOutputQueue.ReadWindow();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        AssertionError fatal = new AssertionError("fatal output failure");
        queue.peek(target, 0, target.length, window, deadline, RECORDING_FAILURES, event -> event);

        queue.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, fatal);
        ProtocolSessionException failure = assertThrows(
                ProtocolSessionException.class,
                () -> queue.commit(window, 2, ignored -> {}, RECORDING_FAILURES, event -> event));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
        assertSame(fatal, failure.getCause());
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void interruptBeforeDeadlineIsFailureAndRestoresInterruptStatus() throws Exception {
        ControlledNanoTime nanoTime = new ControlledNanoTime();
        CountDownLatch waitEntered = new CountDownLatch(1);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                16, ProtocolOutputQueue.OverflowPolicy.STRICT, nanoTime::read, waitEntered::countDown);
        AtomicReference<ProtocolSessionException> observed = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        long deadline = Duration.ofDays(1).toNanos();
        Thread reader = new Thread(() -> {
            try {
                queue.readUnsignedByte(deadline, RECORDING_FAILURES);
            } catch (ProtocolSessionException exception) {
                observed.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        reader.start();
        assertTrue(waitEntered.await(1, TimeUnit.SECONDS), "reader did not enter the output wait");
        reader.interrupt();
        reader.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(reader.isAlive());
        assertEquals(ProtocolSessionException.Reason.FAILURE, observed.get().reason());
        assertTrue(observed.get().getCause() instanceof InterruptedException);
        assertEquals(true, interrupted.get());
    }

    @Test
    void interruptAfterDeadlineIsTimeoutAndRestoresInterruptStatus() throws Exception {
        ControlledNanoTime nanoTime = new ControlledNanoTime();
        CountDownLatch waitEntered = new CountDownLatch(1);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                16, ProtocolOutputQueue.OverflowPolicy.STRICT, nanoTime::read, waitEntered::countDown);
        AtomicReference<ProtocolSessionException> observed = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        long deadline = Duration.ofDays(1).toNanos();
        Thread reader = new Thread(() -> {
            try {
                queue.readUnsignedByte(deadline, RECORDING_FAILURES);
            } catch (ProtocolSessionException exception) {
                observed.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        reader.start();
        assertTrue(waitEntered.await(1, TimeUnit.SECONDS), "reader did not enter the output wait");
        nanoTime.set(deadline + 1);
        reader.interrupt();
        reader.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(reader.isAlive());
        assertEquals(ProtocolSessionException.Reason.TIMEOUT, observed.get().reason());
        assertTrue(observed.get().getCause() instanceof InterruptedException);
        assertEquals(true, interrupted.get());
    }

    @Test
    void exactDeadlineIsTimeoutWithoutEnteringWait() {
        ControlledNanoTime nanoTime = new ControlledNanoTime();
        AtomicInteger waits = new AtomicInteger();
        long deadline = 42;
        nanoTime.set(deadline);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                16, ProtocolOutputQueue.OverflowPolicy.STRICT, nanoTime::read, waits::incrementAndGet);

        ProtocolSessionException observed = assertThrows(
                ProtocolSessionException.class, () -> queue.readUnsignedByte(deadline, RECORDING_FAILURES));

        assertEquals(ProtocolSessionException.Reason.TIMEOUT, observed.reason());
        assertNull(observed.getCause());
        assertEquals(0, waits.get());
    }

    @Test
    void preInterruptedBulkReadBeforeDeadlineIsFailureAndRestoresInterruptStatus() throws Exception {
        ControlledNanoTime nanoTime = new ControlledNanoTime();
        ProtocolOutputQueue queue =
                new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.STRICT, nanoTime::read, () -> {});
        AtomicReference<ProtocolSessionException> observed = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                queue.read(new byte[4], 0, 4, Duration.ofDays(1).toNanos(), RECORDING_FAILURES);
            } catch (ProtocolSessionException exception) {
                observed.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        reader.start();
        reader.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(reader.isAlive());
        assertEquals(ProtocolSessionException.Reason.FAILURE, observed.get().reason());
        assertTrue(observed.get().getCause() instanceof InterruptedException);
        assertEquals(true, interrupted.get());
    }

    @Test
    void bulkReadInterruptAtDeadlineIsTimeoutAndRestoresInterruptStatus() throws Exception {
        ControlledNanoTime nanoTime = new ControlledNanoTime();
        CountDownLatch waitEntered = new CountDownLatch(1);
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                16, ProtocolOutputQueue.OverflowPolicy.STRICT, nanoTime::read, waitEntered::countDown);
        AtomicReference<ProtocolSessionException> observed = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        long deadline = Duration.ofDays(1).toNanos();
        Thread reader = new Thread(() -> {
            try {
                queue.read(new byte[4], 0, 4, deadline, RECORDING_FAILURES);
            } catch (ProtocolSessionException exception) {
                observed.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        reader.start();
        assertTrue(waitEntered.await(1, TimeUnit.SECONDS), "reader did not enter the output wait");
        nanoTime.set(deadline);
        reader.interrupt();
        reader.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(reader.isAlive());
        assertEquals(ProtocolSessionException.Reason.TIMEOUT, observed.get().reason());
        assertTrue(observed.get().getCause() instanceof InterruptedException);
        assertEquals(true, interrupted.get());
    }

    @Test
    void globalFailureReplacesAnEarlierStreamEof() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(16, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        IllegalStateException overflow = new IllegalStateException("overflow");
        queue.eof();

        queue.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow);

        ProtocolSessionException exception = assertThrows(
                ProtocolSessionException.class,
                () -> queue.readUnsignedByte(
                        System.nanoTime() + Duration.ofSeconds(1).toNanos(), RECORDING_FAILURES));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
        assertEquals(overflow, exception.getCause());
    }

    @Test
    void strictCapacityTracksOnlyUnreadBytesAfterPartialChunkConsumption() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        byte[] consumed = new byte[2];

        assertEquals(true, queue.offer(new byte[] {1, 2, 3, 4}));
        assertEquals(2, queue.read(consumed, 0, consumed.length, deadline, RECORDING_FAILURES));
        assertEquals(true, queue.offer(new byte[] {5, 6}));
        assertEquals(false, queue.offer(new byte[] {7}));

        assertEquals(3, queue.readUnsignedByte(deadline, RECORDING_FAILURES));
        assertEquals(4, queue.readUnsignedByte(deadline, RECORDING_FAILURES));
        assertEquals(5, queue.readUnsignedByte(deadline, RECORDING_FAILURES));
        assertEquals(6, queue.readUnsignedByte(deadline, RECORDING_FAILURES));
    }

    @Test
    void emptyByteEventIsIgnoredBeforeRealOutput() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.STRICT);
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();

        assertEquals(true, queue.offer(new byte[0]));
        assertEquals(true, queue.offer(new byte[] {42}));

        assertEquals(42, queue.readUnsignedByte(deadline, RECORDING_FAILURES));
    }

    @Test
    void failOnReadOverflowClearsBytesAndIgnoresAllLaterOutputAndEof() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(4, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();

        assertTrue(queue.offer(new byte[] {1, 2, 3, 4}));
        assertTrue(queue.offer(new byte[] {5}));
        assertEquals(0, queue.pendingBytes());

        for (int index = 0; index < 1_000; index++) {
            assertTrue(queue.offer(new byte[8192]));
        }
        queue.eof();

        ProtocolSessionException first = assertThrows(
                ProtocolSessionException.class, () -> queue.readUnsignedByte(deadline, RECORDING_FAILURES));
        ProtocolSessionException second = assertThrows(
                ProtocolSessionException.class, () -> queue.readUnsignedByte(deadline, RECORDING_FAILURES));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, first.reason());
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, second.reason());
        assertSame(first.getCause(), second.getCause());
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void terminalFailureIsMaterializedOnceAcrossRepeatedCaughtReads() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        CountingFailures failures = new CountingFailures();
        assertTrue(queue.offer(new byte[] {1, 2}));
        ProtocolSessionException first = null;

        for (int attempt = 0; attempt < 10_000; attempt++) {
            ProtocolSessionException observed = assertThrows(
                    ProtocolSessionException.class,
                    () -> queue.readUnsignedByte(
                            System.nanoTime() + Duration.ofSeconds(1).toNanos(), failures));
            if (first == null) {
                first = observed;
            } else {
                assertSame(first, observed);
            }
        }

        assertEquals(1, failures.failureCreations());
        assertEquals(0, first.getSuppressed().length);
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void terminalFailureCreationRunsOutsideQueueMonitor() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        assertTrue(queue.offer(new byte[] {1, 2}));
        AtomicReference<Thread> observerThread = new AtomicReference<>();
        ProtocolRuntimeFailures failures = new ProtocolRuntimeFailures() {
            @Override
            public ProtocolSessionException timeout(Throwable cause) {
                return failure(ProtocolSessionException.Reason.TIMEOUT, "timeout", cause);
            }

            @Override
            public ProtocolSessionException closed(Throwable cause) {
                return failure(ProtocolSessionException.Reason.CLOSED, "closed", cause);
            }

            @Override
            public ProtocolSessionException eof() {
                return failure(ProtocolSessionException.Reason.EOF, "eof", null);
            }

            @Override
            public ProtocolSessionException failure(
                    ProtocolSessionException.Reason reason, String message, Throwable cause) {
                CountDownLatch monitorEntered = new CountDownLatch(1);
                Thread observer = new Thread(() -> {
                    queue.pendingBytes();
                    monitorEntered.countDown();
                });
                observerThread.set(observer);
                observer.start();
                try {
                    assertTrue(
                            monitorEntered.await(1, TimeUnit.SECONDS),
                            "failure creation must not retain the queue monitor");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while checking queue monitor ownership", exception);
                }
                return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), message, cause);
            }
        };

        ProtocolSessionException exception = assertThrows(
                ProtocolSessionException.class,
                () -> queue.readUnsignedByte(
                        System.nanoTime() + Duration.ofSeconds(2).toNanos(), failures));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
        Thread observer = observerThread.get();
        assertTrue(observer != null);
        try {
            observer.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining queue observer", interrupted);
        }
        assertFalse(observer.isAlive());
    }

    @Test
    void overflowDuringActiveReadDoesNotExposePreviouslyPeekedByte() throws Exception {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        CountDownLatch budgetEntered = new CountDownLatch(1);
        CountDownLatch releaseBudget = new CountDownLatch(1);
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        AtomicReference<ProtocolOutputEvent> claimedTerminal = new AtomicReference<>();
        byte[] target = new byte[] {99};
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        queue.offer(new byte[] {42});

        Thread reader = new Thread(() -> {
            try {
                queue.read(
                        target,
                        0,
                        1,
                        deadline,
                        RECORDING_FAILURES,
                        ignored -> {
                            budgetEntered.countDown();
                            await(releaseBudget);
                        },
                        event -> {
                            claimedTerminal.set(event);
                            return event;
                        });
            } catch (Throwable failure) {
                readFailure.set(failure);
            }
        });
        reader.start();

        assertTrue(budgetEntered.await(1, TimeUnit.SECONDS));
        assertTrue(queue.offer(new byte[] {7}));
        releaseBudget.countDown();
        reader.join(Duration.ofSeconds(1).toMillis());

        assertFalse(reader.isAlive());
        ProtocolSessionException overflow = (ProtocolSessionException) readFailure.get();
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
        assertSame(overflow, claimedTerminal.get().terminalFailure(RECORDING_FAILURES));
        assertEquals(99, target[0]);
        assertEquals(0, queue.pendingBytes());
    }

    @Test
    void closeOverridesUnreadOverflowMarker() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        queue.offer(new byte[] {1, 2});

        queue.close();

        ProtocolSessionException exception = assertThrows(
                ProtocolSessionException.class,
                () -> queue.readUnsignedByte(
                        System.nanoTime() + Duration.ofSeconds(1).toNanos(), RECORDING_FAILURES));
        assertEquals(ProtocolSessionException.Reason.CLOSED, exception.reason());
    }

    @Test
    void globalFailureOverridesUnreadOverflowMarker() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(1, ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ);
        AssertionError fatal = new AssertionError("fatal decoder failure");
        queue.offer(new byte[] {1, 2});

        queue.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, fatal);

        ProtocolSessionException exception = assertThrows(
                ProtocolSessionException.class,
                () -> queue.readUnsignedByte(
                        System.nanoTime() + Duration.ofSeconds(1).toNanos(), RECORDING_FAILURES));
        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
        assertSame(fatal, exception.getCause());
    }

    private static void await(CountDownLatch latch) {
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

    private static void assertExitSnapshotFailureRetainsEof(Throwable unavailable) {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(
                1,
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                System::nanoTime,
                () -> {},
                () -> {},
                () -> {
                    if (unavailable instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw (Error) unavailable;
                },
                null);
        queue.eof();

        ProtocolSessionException eof = assertThrows(
                ProtocolSessionException.class,
                () -> queue.readUnsignedByte(
                        System.nanoTime() + Duration.ofSeconds(1).toNanos(), RECORDING_FAILURES));

        assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
        assertNull(eof.getCause());
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class ControlledNanoTime {

        private final AtomicLong value = new AtomicLong();

        private long read() {
            return value.get();
        }

        private void set(long newValue) {
            value.set(newValue);
        }
    }

    private static final ProtocolRuntimeFailures RECORDING_FAILURES = new ProtocolRuntimeFailures() {
        @Override
        public ProtocolSessionException timeout(Throwable cause) {
            return failure(ProtocolSessionException.Reason.TIMEOUT, "timeout", cause);
        }

        @Override
        public ProtocolSessionException closed(Throwable cause) {
            return failure(ProtocolSessionException.Reason.CLOSED, "closed", cause);
        }

        @Override
        public ProtocolSessionException eof() {
            return failure(ProtocolSessionException.Reason.EOF, "eof", null);
        }

        @Override
        public ProtocolSessionException failure(
                ProtocolSessionException.Reason reason, String message, Throwable cause) {
            return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), message, cause);
        }
    };

    private static final class CountingFailures implements ProtocolRuntimeFailures {

        private final AtomicInteger failureCreations = new AtomicInteger();

        @Override
        public ProtocolSessionException timeout(Throwable cause) {
            return failure(ProtocolSessionException.Reason.TIMEOUT, "timeout", cause);
        }

        @Override
        public ProtocolSessionException closed(Throwable cause) {
            return failure(ProtocolSessionException.Reason.CLOSED, "closed", cause);
        }

        @Override
        public ProtocolSessionException eof() {
            return failure(ProtocolSessionException.Reason.EOF, "eof", null);
        }

        @Override
        public ProtocolSessionException failure(
                ProtocolSessionException.Reason reason, String message, Throwable cause) {
            failureCreations.incrementAndGet();
            return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), message, cause);
        }

        private int failureCreations() {
            return failureCreations.get();
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProcessCleanupTest {

    @Test
    void delegatesTheExactProcessAndBudgetAndClosesEveryStreamAsynchronously() throws Exception {
        RecordingProcess process = new RecordingProcess();
        Duration timeout = Duration.ofMillis(250);
        AtomicInteger forceStops = new AtomicInteger();
        AtomicReference<Process> delegatedProcess = new AtomicReference<>();
        AtomicReference<Duration> delegatedTimeout = new AtomicReference<>();

        ProcessCleanup.forceStopAndCloseAsync(
                process,
                timeout,
                (actual, budget) -> {
                    forceStops.incrementAndGet();
                    delegatedProcess.set(actual);
                    delegatedTimeout.set(budget);
                },
                dispatcher(),
                failure -> {});

        assertEquals(1, forceStops.get());
        assertSame(process, delegatedProcess.get());
        assertEquals(timeout, delegatedTimeout.get());
        assertTrue(process.closed.await(1, TimeUnit.SECONDS));
        process.assertEachStreamAccessedAndClosedOnce();
    }

    @Test
    void blockingStreamAccessNeverRunsOnTheRollbackCaller() throws Exception {
        CountDownLatch releaseAccess = new CountDownLatch(1);
        BlockingAccessorProcess process = new BlockingAccessorProcess(releaseAccess);
        Thread caller = Thread.currentThread();

        try {
            ProcessCleanup.forceStopAndCloseAsync(
                    process, Duration.ofMillis(250), (actual, budget) -> {}, dispatcher(), failure -> {});

            assertTrue(process.accessStarted.await(1, TimeUnit.SECONDS));
            assertTrue(process.accessThreads.stream().noneMatch(thread -> thread == caller));
        } finally {
            releaseAccess.countDown();
        }
        assertTrue(process.accessCompleted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void accessorAndCloseFailuresAreReportedOnceWithoutPreventingOtherStreams() throws Exception {
        IllegalStateException stdinAccessFailure = new IllegalStateException("stdin access failed");
        IOException stdoutCloseFailure = new IOException("stdout close failed");
        IOException stderrCloseFailure = new IOException("stderr close failed");
        FailingProcess process = new FailingProcess(stdinAccessFailure, stdoutCloseFailure, stderrCloseFailure);
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(3);

        ProcessCleanup.forceStopAndCloseAsync(
                process, Duration.ofMillis(250), (actual, budget) -> {}, dispatcher(), failure -> {
                    failures.add(failure);
                    reported.countDown();
                });

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(3, failures.size());
        assertTrue(failures.stream().anyMatch(failure -> failure == stdinAccessFailure));
        assertTrue(failures.stream().anyMatch(failure -> failure == stdoutCloseFailure));
        assertTrue(failures.stream().anyMatch(failure -> failure == stderrCloseFailure));
        process.assertEveryAccessorAttempted();
        assertEquals(1, process.stdout.closeCalls());
        assertEquals(1, process.stderr.closeCalls());
    }

    @Test
    void forceStopFailureIsReportedWithoutPreventingStreamCleanup() throws Exception {
        RecordingProcess process = new RecordingProcess();
        AssertionError stopFailure = new AssertionError("force stop failed");
        AtomicReference<Throwable> reported = new AtomicReference<>();

        ProcessCleanup.forceStopAndCloseAsync(
                process,
                Duration.ofMillis(250),
                (actual, budget) -> {
                    throw stopFailure;
                },
                dispatcher(),
                reported::set);

        assertSame(stopFailure, reported.get());
        assertTrue(process.closed.await(1, TimeUnit.SECONDS));
        process.assertEachStreamAccessedAndClosedOnce();
    }

    @Test
    void saturatedCloseAdmissionReportsEachSkippedCloseWithoutLeakingCapacity() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        CountDownLatch occupyingCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseCloses = new CountDownLatch(1);
        for (int index = 0; index < 3; index++) {
            dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                    () -> {
                        occupyingCloseStarted.countDown();
                        boolean interrupted = false;
                        while (true) {
                            try {
                                releaseCloses.await();
                                break;
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    "occupying-close-",
                    ignored -> {}));
        }
        assertTrue(occupyingCloseStarted.await(1, TimeUnit.SECONDS));
        RecordingProcess process = new RecordingProcess();
        CopyOnWriteArrayList<Throwable> reported = new CopyOnWriteArrayList<>();
        try {
            ProcessCleanup.forceStopAndCloseAsync(
                    process, Duration.ofMillis(250), (actual, budget) -> {}, dispatcher, reported::add);

            assertEquals(3, reported.size());
            assertTrue(reported.stream().allMatch(RejectedExecutionException.class::isInstance));
            assertEquals(3, dispatcher.outstandingCount());
            process.assertNoStreamAccess();
        } finally {
            releaseCloses.countDown();
        }
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void closeStarterFailureIsReportedAndReleasesEveryAcceptedSlot() throws Exception {
        List<IllegalStateException> startFailures = List.of(
                new IllegalStateException("stdin close starter failed"),
                new IllegalStateException("stdout close starter failed"),
                new IllegalStateException("stderr close starter failed"));
        AtomicInteger starts = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, (name, task) -> {
            throw startFailures.get(starts.getAndIncrement());
        });
        RecordingProcess process = new RecordingProcess();
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(3);

        ProcessCleanup.forceStopAndCloseAsync(
                process, Duration.ofMillis(250), (actual, budget) -> {}, dispatcher, failure -> {
                    failures.add(failure);
                    reported.countDown();
                });

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(3, failures.size());
        for (Throwable expected : startFailures) {
            assertEquals(
                    1, failures.stream().filter(failure -> failure == expected).count());
        }
        process.assertNoStreamAccess();
        assertEquals(0, dispatcher.outstandingCount());
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return condition.getAsBoolean();
    }

    private static BoundedCloseDispatcher dispatcher() {
        return new BoundedCloseDispatcher(3, 3, Threading::start);
    }

    private static class RecordingProcess extends Process {

        private final RecordingOutputStream stdin = new RecordingOutputStream(null, null);
        private final RecordingInputStream stdout = new RecordingInputStream(null, null);
        private final RecordingInputStream stderr = new RecordingInputStream(null, null);
        private final AtomicInteger stdinAccesses = new AtomicInteger();
        private final AtomicInteger stdoutAccesses = new AtomicInteger();
        private final AtomicInteger stderrAccesses = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(3);

        @Override
        public OutputStream getOutputStream() {
            stdinAccesses.incrementAndGet();
            stdin.closed = closed;
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            stdoutAccesses.incrementAndGet();
            stdout.closed = closed;
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrAccesses.incrementAndGet();
            stderr.closed = closed;
            return stderr;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        private void assertEachStreamAccessedAndClosedOnce() {
            assertEquals(1, stdinAccesses.get());
            assertEquals(1, stdoutAccesses.get());
            assertEquals(1, stderrAccesses.get());
            assertEquals(1, stdin.closeCalls());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        }

        private void assertNoStreamAccess() {
            assertEquals(0, stdinAccesses.get());
            assertEquals(0, stdoutAccesses.get());
            assertEquals(0, stderrAccesses.get());
        }
    }

    private static final class BlockingAccessorProcess extends RecordingProcess {

        private final CountDownLatch releaseAccess;
        private final CountDownLatch accessStarted = new CountDownLatch(3);
        private final CountDownLatch accessCompleted = new CountDownLatch(3);
        private final CopyOnWriteArrayList<Thread> accessThreads = new CopyOnWriteArrayList<>();

        private BlockingAccessorProcess(CountDownLatch releaseAccess) {
            this.releaseAccess = releaseAccess;
        }

        @Override
        public OutputStream getOutputStream() {
            block();
            return super.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            block();
            return super.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            block();
            return super.getErrorStream();
        }

        private void block() {
            accessThreads.add(Thread.currentThread());
            accessStarted.countDown();
            try {
                releaseAccess.await();
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            } finally {
                accessCompleted.countDown();
            }
        }
    }

    private static final class FailingProcess extends Process {

        private final RuntimeException stdinAccessFailure;
        private final RecordingInputStream stdout;
        private final RecordingInputStream stderr;
        private final AtomicInteger stdinAccesses = new AtomicInteger();
        private final AtomicInteger stdoutAccesses = new AtomicInteger();
        private final AtomicInteger stderrAccesses = new AtomicInteger();

        private FailingProcess(
                RuntimeException stdinAccessFailure, IOException stdoutCloseFailure, IOException stderrCloseFailure) {
            this.stdinAccessFailure = stdinAccessFailure;
            stdout = new RecordingInputStream(stdoutCloseFailure, null);
            stderr = new RecordingInputStream(stderrCloseFailure, null);
        }

        @Override
        public OutputStream getOutputStream() {
            stdinAccesses.incrementAndGet();
            throw stdinAccessFailure;
        }

        @Override
        public InputStream getInputStream() {
            stdoutAccesses.incrementAndGet();
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrAccesses.incrementAndGet();
            return stderr;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        private void assertEveryAccessorAttempted() {
            assertEquals(1, stdinAccesses.get());
            assertEquals(1, stdoutAccesses.get());
            assertEquals(1, stderrAccesses.get());
        }
    }

    private static final class RecordingOutputStream extends ByteArrayOutputStream {

        private final IOException closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile CountDownLatch closed;

        private RecordingOutputStream(IOException closeFailure, CountDownLatch closed) {
            this.closeFailure = closeFailure;
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            try {
                if (closeFailure != null) {
                    throw closeFailure;
                }
                super.close();
            } finally {
                if (closed != null) {
                    closed.countDown();
                }
            }
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class RecordingInputStream extends ByteArrayInputStream {

        private final IOException closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile CountDownLatch closed;

        private RecordingInputStream(IOException closeFailure, CountDownLatch closed) {
            super(new byte[0]);
            this.closeFailure = closeFailure;
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            try {
                if (closeFailure != null) {
                    throw closeFailure;
                }
                super.close();
            } finally {
                if (closed != null) {
                    closed.countDown();
                }
            }
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessIoAcquisitionTest extends ProcessIoResourcesTestSupport {

    @Test
    void acquiresEveryProcessStreamExactlyOnceBeforeReturning() {
        TrackingProcess process = new TrackingProcess();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);

        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);

        assertSame(process.stdin, resources.stdin().stream());
        assertSame(process.stdout, resources.stdout().stream());
        assertSame(process.stderr, resources.stderr().stream());
        assertEquals(1, process.stdinGets.get());
        assertEquals(1, process.stdoutGets.get());
        assertEquals(1, process.stderrGets.get());
        resources.closeAllAsync(ignored -> {});
    }

    @Test
    void capacityExhaustionFailsBeforeAnyStreamIsObserved() {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        BoundedCloseDispatcher.Reservation occupied = dispatcher.reserve(3);
        TrackingProcess process = new TrackingProcess();

        assertThrows(RejectedExecutionException.class, () -> ProcessIoResources.acquire(process, dispatcher));

        assertEquals(0, process.stdinGets.get());
        assertEquals(0, process.stdoutGets.get());
        assertEquals(0, process.stderrGets.get());
        assertFalse(process.isAlive());
        occupied.release();
    }

    @Test
    void publicationCapacityFailureReleasesCloseReservationAndStopsProcessBeforeStreams() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);
        BoundedLifecyclePublisher.Reservation occupied = publisher.reserve(1);
        TrackingProcess process = new TrackingProcess();

        assertThrows(
                RejectedExecutionException.class, () -> ProcessIoResources.acquire(process, dispatcher, publisher));

        assertFalse(process.isAlive());
        assertEquals(0, process.stdinGets.get());
        assertEquals(0, process.stdoutGets.get());
        assertEquals(0, process.stderrGets.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        occupied.release();
        assertTrue(eventually(() -> publisher.ownerCount() == 0));
    }

    @Test
    void publicationCapacityFailureReleasesCloseReservationBeforeProcessCleanupCompletes() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);
        BoundedLifecyclePublisher.Reservation occupied = publisher.reserve(1);
        BlockingCleanupProcess process = new BlockingCleanupProcess();
        ExecutorService acquisition = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> failure = acquisition.submit(
                    () -> captureFailure(() -> ProcessIoResources.acquire(process, dispatcher, publisher)));

            assertTrue(process.cleanupEntered.await(1, TimeUnit.SECONDS));
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
            assertFalse(failure.isDone());

            process.releaseCleanup.countDown();
            assertTrue(failure.get(1, TimeUnit.SECONDS) instanceof RejectedExecutionException);
        } finally {
            process.releaseCleanup.countDown();
            occupied.release();
            acquisition.shutdownNow();
        }
        assertTrue(eventually(() -> publisher.ownerCount() == 0));
    }

    @Test
    void everyPartialAcquisitionFailureRollsBackStableResourcesByIdentity() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 3; failedOrdinal++) {
            for (boolean fatal : new boolean[] {false, true}) {
                Throwable expected = fatal
                        ? new AssertionError("getter " + failedOrdinal)
                        : new IllegalStateException("getter " + failedOrdinal);
                TrackingProcess process = new TrackingProcess(failedOrdinal, expected);
                BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
                BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);

                Throwable actual = assertThrows(
                        expected.getClass(), () -> ProcessIoResources.acquire(process, dispatcher, publisher));

                assertSame(expected, actual);
                assertFalse(process.isAlive());
                assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
                assertEquals(failedOrdinal >= 2 ? 1 : 0, process.stdin.closeCalls.get());
                assertEquals(failedOrdinal >= 3 ? 1 : 0, process.stdout.closeCalls.get());
                assertEquals(0, process.stderr.closeCalls.get());
                assertEquals(failedOrdinal >= 1 ? 1 : 0, process.stdinGets.get());
                assertEquals(failedOrdinal >= 2 ? 1 : 0, process.stdoutGets.get());
                assertEquals(failedOrdinal >= 3 ? 1 : 0, process.stderrGets.get());
            }
        }
    }

    @Test
    void processCleanupFailureDoesNotPreventResourceRollback() throws Exception {
        AssertionError acquisitionFailure = new AssertionError("stderr getter failed");
        AssertionError cleanupFailure = new AssertionError("process handle failed");
        TerminationFailureProcess process = new TerminationFailureProcess(acquisitionFailure, cleanupFailure);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);

        Throwable actual = captureFailure(() -> ProcessIoResources.acquire(process, dispatcher, publisher));

        assertTrue(actual instanceof Error);
        assertSame(acquisitionFailure, FailureAggregation.primary(actual));
        assertEquals(java.util.List.of(acquisitionFailure, cleanupFailure), FailureAggregation.sources(actual));
        assertEquals(0, acquisitionFailure.getSuppressed().length);
        assertEquals(0, cleanupFailure.getSuppressed().length);
        assertEquals(1, process.stdin.closeCalls.get());
        assertEquals(1, process.stdout.closeCalls.get());
        assertEquals(0, process.stderr.closeCalls.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
    }

    @Test
    void partialAcquisitionAggregatesEveryRollbackFailureWithoutMutatingSources() throws Exception {
        for (Throwable acquisitionFailure : java.util.List.of(
                new IllegalStateException("stderr getter failed"), new AssertionError("stderr getter failed"))) {
            IOException stdinCloseFailure = new IOException("stdin rollback close failed");
            AssertionError stdoutCloseFailure = new AssertionError("stdout rollback close failed");
            RollbackFailureProcess process =
                    new RollbackFailureProcess(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure);
            BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1);

            Throwable actual = captureFailure(() -> ProcessIoResources.acquire(process, dispatcher));

            assertTrue(
                    acquisitionFailure instanceof Error ? actual instanceof Error : actual instanceof RuntimeException);
            assertSame(acquisitionFailure, FailureAggregation.primary(actual));
            assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
            assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
            assertEquals(
                    java.util.List.of(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure),
                    FailureAggregation.sources(actual));
            assertEquals(0, acquisitionFailure.getSuppressed().length);
            assertEquals(0, stdinCloseFailure.getSuppressed().length);
            assertEquals(0, stdoutCloseFailure.getSuppressed().length);
            assertEquals(1, process.stdin.closeCalls.get());
            assertEquals(1, process.stdout.closeCalls.get());
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        }
    }

    @Test
    void rollbackDoesNotInspectHostileFailureGraphs() throws Exception {
        AssertionError acquisitionFailure = new AssertionError("stderr getter failed");
        HostileCauseIOException stdinCloseFailure = new HostileCauseIOException();
        AssertionError stdoutCloseFailure = new AssertionError("stdout rollback close failed");
        RollbackFailureProcess process =
                new RollbackFailureProcess(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1);

        Throwable failure = captureFailure(() -> ProcessIoResources.acquire(process, dispatcher));

        assertSame(acquisitionFailure, FailureAggregation.primary(failure));
        assertEquals(
                java.util.List.of(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure),
                FailureAggregation.sources(failure));
        assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
        assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        assertEquals(0, stdinCloseFailure.causeReads.get());
        assertEquals(1, process.stdin.closeCalls.get());
        assertEquals(1, process.stdout.closeCalls.get());
    }

    @Test
    void rollbackDoesNotAcquireTheAcquisitionFailureMonitor() throws Exception {
        AssertionError acquisitionFailure = new AssertionError("stderr getter failed");
        IOException stdinCloseFailure = new IOException("stdin rollback close failed");
        AssertionError stdoutCloseFailure = new AssertionError("stdout rollback close failed");
        RollbackFailureProcess process =
                new RollbackFailureProcess(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var monitor = ThrowableMonitorTestSupport.hold(acquisitionFailure)) {
            monitor.verifyHeld();
            Future<Throwable> acquisition =
                    executor.submit(() -> captureFailure(() -> ProcessIoResources.acquire(process, dispatcher)));

            Throwable failure = acquisition.get(1, TimeUnit.SECONDS);
            assertSame(acquisitionFailure, FailureAggregation.primary(failure));
            assertEquals(
                    java.util.List.of(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure),
                    FailureAggregation.sources(failure));
            assertFalse(process.isAlive());
            assertEquals(1, process.stdin.closeCalls.get());
            assertEquals(1, process.stdout.closeCalls.get());
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static final class TerminationFailureProcess extends TrackingProcess {

        private final AssertionError cleanupFailure;

        private TerminationFailureProcess(AssertionError acquisitionFailure, AssertionError cleanupFailure) {
            super(3, acquisitionFailure);
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public ProcessHandle toHandle() {
            throw cleanupFailure;
        }
    }

    private static final class BlockingCleanupProcess extends TrackingProcess {

        private final CountDownLatch cleanupEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCleanup = new CountDownLatch(1);

        @Override
        public Stream<ProcessHandle> descendants() {
            cleanupEntered.countDown();
            awaitUninterruptibly(releaseCleanup);
            return Stream.empty();
        }
    }

    private static final class RollbackFailureProcess extends Process {

        private final Throwable acquisitionFailure;
        private final FailingCloseOutputStream stdin;
        private final FailingCloseInputStream stdout;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private RollbackFailureProcess(
                Throwable acquisitionFailure, IOException stdinCloseFailure, AssertionError stdoutCloseFailure) {
            this.acquisitionFailure = acquisitionFailure;
            stdin = new FailingCloseOutputStream(stdinCloseFailure);
            stdout = new FailingCloseInputStream(stdoutCloseFailure);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            if (acquisitionFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) acquisitionFailure;
        }

        @Override
        public int waitFor() {
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class FailingCloseOutputStream extends OutputStream {

        private final IOException failure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private FailingCloseOutputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closed.countDown();
            throw failure;
        }
    }

    private static final class FailingCloseInputStream extends InputStream {

        private final AssertionError failure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private FailingCloseInputStream(AssertionError failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
            throw failure;
        }
    }

    @SuppressWarnings("serial")
    private static final class HostileCauseIOException extends IOException {

        private final AtomicInteger causeReads = new AtomicInteger();

        private HostileCauseIOException() {
            super("stdin rollback close failed", null);
        }

        @Override
        public synchronized Throwable getCause() {
            causeReads.incrementAndGet();
            throw new AssertionError("rollback diagnostics must not inspect the failure graph");
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

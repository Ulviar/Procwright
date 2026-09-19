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
    void closeCapacityDoesNotLimitLiveProcessResources() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        CountDownLatch occupyingCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseCloses = new CountDownLatch(1);
        for (int index = 0; index < 3; index++) {
            dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                    () -> {
                        occupyingCloseStarted.countDown();
                        awaitUninterruptibly(releaseCloses);
                    },
                    "occupying-close-",
                    ignored -> {}));
        }
        assertTrue(occupyingCloseStarted.await(1, TimeUnit.SECONDS));
        TrackingProcess process = new TrackingProcess();

        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);

        assertEquals(1, process.stdinGets.get());
        assertEquals(1, process.stdoutGets.get());
        assertEquals(1, process.stderrGets.get());
        assertTrue(process.isAlive());
        releaseCloses.countDown();
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        resources.closeAllAsync(ignored -> {});
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

    @Test
    void everyPartialAcquisitionFailureRollsBackStableResourcesByIdentity() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 3; failedOrdinal++) {
            for (boolean fatal : new boolean[] {false, true}) {
                Throwable expected = fatal
                        ? new AssertionError("getter " + failedOrdinal)
                        : new IllegalStateException("getter " + failedOrdinal);
                TrackingProcess process = new TrackingProcess(failedOrdinal, expected);
                BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);

                Throwable actual =
                        assertThrows(expected.getClass(), () -> ProcessIoResources.acquire(process, dispatcher));

                assertSame(expected, actual);
                assertFalse(process.isAlive());
                assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
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

        Throwable actual = captureFailure(() -> ProcessIoResources.acquire(process, dispatcher));

        assertTrue(actual instanceof Error);
        assertSame(acquisitionFailure, FailureAggregation.primary(actual));
        assertEquals(java.util.List.of(acquisitionFailure, cleanupFailure), FailureAggregation.sources(actual));
        assertEquals(0, acquisitionFailure.getSuppressed().length);
        assertEquals(0, cleanupFailure.getSuppressed().length);
        assertTrue(eventually(() -> process.stdin.closeCalls.get() == 1));
        assertTrue(eventually(() -> process.stdout.closeCalls.get() == 1));
        assertEquals(0, process.stderr.closeCalls.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void partialAcquisitionKeepsAsynchronousCloseFailuresSecondary() throws Exception {
        for (Throwable acquisitionFailure : java.util.List.of(
                new IllegalStateException("stderr getter failed"), new AssertionError("stderr getter failed"))) {
            IOException stdinCloseFailure = new IOException("stdin rollback close failed");
            AssertionError stdoutCloseFailure = new AssertionError("stdout rollback close failed");
            RollbackFailureProcess process =
                    new RollbackFailureProcess(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure);
            BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1);

            Throwable actual = captureFailure(() -> ProcessIoResources.acquire(process, dispatcher));

            assertSame(acquisitionFailure, actual);
            assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
            assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
            assertEquals(java.util.List.of(acquisitionFailure), FailureAggregation.sources(actual));
            assertEquals(0, acquisitionFailure.getSuppressed().length);
            assertEquals(0, stdinCloseFailure.getSuppressed().length);
            assertEquals(0, stdoutCloseFailure.getSuppressed().length);
            assertEquals(1, process.stdin.closeCalls.get());
            assertEquals(1, process.stdout.closeCalls.get());
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        }
    }

    @Test
    void partialAcquisitionReturnsWhilePhysicalCloseIsBlocked() throws Exception {
        IllegalStateException acquisitionFailure = new IllegalStateException("stderr getter failed");
        BlockingCloseInputStream stdout = new BlockingCloseInputStream();
        BlockingRollbackProcess process = new BlockingRollbackProcess(acquisitionFailure, stdout);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> acquisition =
                    executor.submit(() -> captureFailure(() -> ProcessIoResources.acquire(process, dispatcher)));
            assertTrue(stdout.closeStarted.await(1, TimeUnit.SECONDS));
            Throwable failure = acquisition.get(1, TimeUnit.SECONDS);
            assertSame(acquisitionFailure, failure);
            assertFalse(process.isAlive());
        } finally {
            stdout.releaseClose.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
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

    private static final class BlockingRollbackProcess extends TrackingProcess {

        private final RuntimeException acquisitionFailure;

        private BlockingRollbackProcess(RuntimeException acquisitionFailure, TrackingInputStream stdout) {
            super(stdout, new TrackingInputStream());
            this.acquisitionFailure = acquisitionFailure;
        }

        @Override
        public InputStream getErrorStream() {
            stderrGets.incrementAndGet();
            throw acquisitionFailure;
        }
    }

    private static final class BlockingCloseInputStream extends TrackingInputStream {

        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public void close() {
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
        }
    }
}

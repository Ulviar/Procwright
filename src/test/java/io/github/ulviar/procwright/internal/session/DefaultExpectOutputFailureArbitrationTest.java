/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.GatedEofInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.session.ExpectException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultExpectOutputFailureArbitrationTest {

    @Test
    void laterFatalOutputFailureCannotDelayPumpOrSessionCleanup() throws Exception {
        AssertionError primary = new AssertionError("primary stdout failure");
        BlockingCauseError secondary = new BlockingCauseError();
        ControlledPumpFailureInputStream stdout =
                new ControlledPumpFailureInputStream(primary, new AssertionError("stdout close failed"));
        ControlledPumpFailureInputStream stderr =
                new ControlledPumpFailureInputStream(secondary, new AssertionError("stderr close failed"));
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        CountDownLatch reporterEntered = new CountDownLatch(1);
        CountDownLatch releaseReporter = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Error> reportedError = new AtomicReference<>();
        DefaultExpect expect = new DefaultExpect(
                rawSession,
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskLimiter(1),
                ExpectRegexMatcher::evaluate,
                (thread, error) -> {
                    reports.incrementAndGet();
                    reportedError.set(error);
                    reporterEntered.countDown();
                    awaitUninterruptibly(releaseReporter);
                });
        try {
            assertTrue(stdout.awaitReadEntered());
            assertTrue(stderr.awaitReadEntered());

            stdout.releaseReadFailure();
            assertTrue(process.awaitDestroyed());
            stderr.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();

            assertTrue(reporterEntered.await(1, TimeUnit.SECONDS));
            rawSession.onExit().get(1, TimeUnit.SECONDS);
            stdout.readThread().join(TimeUnit.SECONDS.toMillis(1));
            stderr.readThread().join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(stdout.readThread().isAlive());
            assertFalse(stderr.readThread().isAlive());
            assertTrue(stdout.awaitCloseWorkerStopped());
            assertTrue(stderr.awaitCloseWorkerStopped());
            assertEquals(1, reports.get());
            assertSame(secondary, reportedError.get());
            assertEquals(1, secondary.causeAccessed.getCount());
            ExpectException terminal = assertThrows(ExpectException.class, () -> expect.expectText("never"));
            assertEquals(ExpectException.Reason.FAILURE, terminal.reason());
            assertSame(primary, terminal.getCause());
            releaseReporter.countDown();
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
        } finally {
            releaseReporter.countDown();
            secondary.releaseCause.countDown();
            stdout.releaseReadFailure();
            stderr.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            expect.close();
            rawSession.close();
        }
    }

    @Test
    void pumpErrorLosingToCloseIsReportedOnceAfterPhysicalCloseFailuresAreAttached() throws Exception {
        AssertionError pumpError = new AssertionError("late expect pump failure");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ControlledPumpFailureInputStream stdout = new ControlledPumpFailureInputStream(pumpError, stdoutCloseFailure);
        ControlledPumpFailureInputStream stderr = new ControlledPumpFailureInputStream(null, stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        List<Thread> pumpThreads = new ArrayList<>();
        CountDownLatch reported = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> reportedThread = new AtomicReference<>();
        AtomicReference<Error> reportedError = new AtomicReference<>();
        AtomicReference<List<Throwable>> suppressionsAtReport = new AtomicReference<>();
        PumpStarter starter = (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            pumpThreads.add(thread);
            thread.start();
            return thread;
        };
        DefaultExpect expect = new DefaultExpect(
                rawSession,
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                starter,
                new BoundedTaskLimiter(1),
                ExpectRegexMatcher::evaluate,
                (thread, error) -> {
                    reports.incrementAndGet();
                    reportedThread.set(thread);
                    reportedError.set(error);
                    suppressionsAtReport.set(List.of(error.getSuppressed()));
                    reported.countDown();
                });
        try {
            assertTrue(stdout.awaitReadEntered());

            expect.close();
            assertTrue(stdout.awaitCloseEntered());
            assertTrue(stderr.awaitCloseEntered());

            stdout.releaseReadFailure();
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }
            assertEquals(0, reports.get(), "fatal publication must wait for physical close failures");

            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            assertTrue(stdout.awaitCloseWorkerStopped());
            assertTrue(stderr.awaitCloseWorkerStopped());
            assertTrue(reported.await(1, TimeUnit.SECONDS));

            assertSame(pumpError, reportedError.get());
            assertSame(stdout.readThread(), reportedThread.get());
            assertEquals(0, pumpError.getSuppressed().length);
            assertTrue(suppressionsAtReport.get().isEmpty());
            ExpectException terminal = assertThrows(ExpectException.class, () -> expect.expectText("never"));
            assertEquals(ExpectException.Reason.CLOSED, terminal.reason());
            expect.close();
            assertEquals(1, reports.get());
        } finally {
            stdout.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            expect.close();
            rawSession.close();
        }
    }

    @Test
    void pumpErrorLosingToEofIsReportedOnceAfterPhysicalCloseSettles() throws Exception {
        AssertionError pumpError = new AssertionError("late stderr pump failure");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControlledPumpFailureInputStream stderr = new ControlledPumpFailureInputStream(pumpError, stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        List<Thread> pumpThreads = new ArrayList<>();
        CountDownLatch stdoutStopped = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> reportedThread = new AtomicReference<>();
        AtomicReference<Error> reportedError = new AtomicReference<>();
        AtomicReference<List<Throwable>> suppressionsAtReport = new AtomicReference<>();
        PumpStarter starter = (name, task) -> {
            Thread thread = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            if (name.contains("stdout")) {
                                stdoutStopped.countDown();
                            }
                        }
                    },
                    name);
            thread.setDaemon(true);
            pumpThreads.add(thread);
            thread.start();
            return thread;
        };
        DefaultExpect expect = new DefaultExpect(
                rawSession,
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                starter,
                new BoundedTaskLimiter(1),
                ExpectRegexMatcher::evaluate,
                (thread, error) -> {
                    reports.incrementAndGet();
                    reportedThread.set(thread);
                    reportedError.set(error);
                    suppressionsAtReport.set(List.of(error.getSuppressed()));
                    reported.countDown();
                });
        try {
            assertTrue(stderr.awaitReadEntered());
            stdout.finish();
            assertTrue(stdoutStopped.await(1, TimeUnit.SECONDS));

            stderr.releaseReadFailure();
            assertTrue(stderr.awaitCloseEntered());
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }
            assertEquals(0, reports.get(), "fatal publication must wait for physical close failure");

            stderr.releaseCloseFailure();
            assertTrue(stderr.awaitCloseWorkerStopped());
            assertTrue(reported.await(1, TimeUnit.SECONDS));

            assertSame(pumpError, reportedError.get());
            assertSame(stderr.readThread(), reportedThread.get());
            assertEquals(0, pumpError.getSuppressed().length);
            assertTrue(suppressionsAtReport.get().isEmpty());
            expect.close();
            ExpectException terminal = assertThrows(ExpectException.class, () -> expect.expectText("never"));
            assertEquals(ExpectException.Reason.EOF, terminal.reason());
            assertEquals(1, reports.get());
        } finally {
            stdout.finish();
            stderr.releaseReadFailure();
            stderr.releaseCloseFailure();
            expect.close();
            rawSession.close();
        }
    }

    private static final class ControlledPumpFailureInputStream extends InputStream {

        private final Error readFailure;
        private final Error closeFailure;
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private volatile Thread readThread;
        private volatile Thread closeThread;

        private ControlledPumpFailureInputStream(Error readFailure, Error closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            if (readFailure == null) {
                return -1;
            }
            readThread = Thread.currentThread();
            readEntered.countDown();
            awaitUninterruptibly(releaseRead);
            throw readFailure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closeThread = Thread.currentThread();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            throw closeFailure;
        }

        private boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseReadFailure() {
            releaseRead.countDown();
        }

        private Thread readThread() {
            return readThread;
        }

        private boolean awaitCloseEntered() throws InterruptedException {
            return closeEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseCloseFailure() {
            releaseClose.countDown();
        }

        private boolean awaitCloseWorkerStopped() throws InterruptedException {
            Thread worker = closeThread;
            if (worker == null) {
                return false;
            }
            worker.join(TimeUnit.SECONDS.toMillis(1));
            return !worker.isAlive();
        }
    }

    @SuppressWarnings("serial")
    private static final class BlockingCauseError extends AssertionError {

        private final CountDownLatch causeAccessed = new CountDownLatch(1);
        private final CountDownLatch releaseCause = new CountDownLatch(1);

        private BlockingCauseError() {
            super("secondary stderr failure", null);
        }

        @Override
        public synchronized Throwable getCause() {
            causeAccessed.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseCause.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}

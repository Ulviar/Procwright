/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectOutputLifecycleTest extends ExpectOutputTestSupport {

    @Test
    void closeWinningBeforeDecodedPublicationPreventsPublication() throws Exception {
        GatedPublicationCharset charset = new GatedPublicationCharset();
        TrackingPumpStarter pumpStarter = new TrackingPumpStarter();
        ControllableProcess process =
                new ControllableProcess(new CloseTrackingInputStream(new byte[] {'x'}), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        DefaultExpect expect = new DefaultExpect(
                rawSession, ExpectSettings.defaults().withCharset(charset), ZeroReadBackoff.exponential(), pumpStarter);
        try {
            assertTrue(charset.awaitPublicationReady());

            expect.close();
            charset.releasePublication();

            assertTrue(pumpStarter.awaitStopped());
            assertFalse(expect.transcript().text().contains("x"));
        } finally {
            charset.releasePublication();
            expect.close();
        }
    }

    @Test
    void rejectedArgumentsAndTimeoutsDoNotMutateTranscript() {
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(new FeedInputStream(), new FeedInputStream())),
                ExpectSettings.defaults());
        try {
            String before = expect.transcript().text();

            assertThrows(NullPointerException.class, () -> expect.send(null));
            assertThrows(NullPointerException.class, () -> expect.sendLine(null));
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("bad\nline"));
            assertThrows(NullPointerException.class, () -> expect.expectTextMatch(null));
            assertThrows(NullPointerException.class, () -> expect.expectRegexMatch(null));
            assertThrows(NullPointerException.class, () -> expect.expectTextMatch("x", null));
            assertThrows(NullPointerException.class, () -> expect.expectRegexMatch(Pattern.compile("x"), null));
            assertThrows(IllegalArgumentException.class, () -> expect.expectTextMatch("x", Duration.ZERO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> expect.expectRegexMatch(Pattern.compile("x"), Duration.ofNanos(-1)));

            assertEquals(before, expect.transcript().text());
        } finally {
            expect.close();
        }
    }

    @Test
    void operationsStartedAfterCloseFailBeforeTranscriptOrStdinMutation() {
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(new FeedInputStream(), new FeedInputStream())),
                ExpectSettings.defaults());
        expect.close();
        String before = expect.transcript().text();

        for (Runnable operation : List.<Runnable>of(
                () -> expect.send("text"),
                () -> expect.sendLine("line"),
                () -> expect.expectTextMatch(""),
                () -> expect.expectRegexMatch(Pattern.compile(".*")))) {
            ExpectException failure = assertThrows(ExpectException.class, operation::run);
            assertEquals(ExpectException.Reason.CLOSED, failure.reason());
            assertEquals(before, expect.transcript().text());
        }
    }

    @Test
    void ansiStrippingIsIncrementalAndAppliedToMatchingAndTranscript() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(stdout, stderr)),
                ExpectSettings.defaults().withAnsiControlSequenceStripping());
        try {
            stdout.offer("\u001B[");
            stderr.offer("warning:\u001B[");
            stdout.offer("31mREADY");
            stderr.offer("1mFAIL\u001B[0m");
            stdout.offer("\u001B[0m");

            ExpectMatch match = expect.expectRegexMatch(Pattern.compile("^READY$"), Duration.ofSeconds(1));

            assertEquals("READY", match.matched());
            assertTrue(eventually(() -> {
                String transcript = expect.transcript().text();
                return transcript.contains("warning:") && transcript.contains("FAIL");
            }));
            assertFalse(expect.transcript().text().contains("\u001B"));
        } finally {
            expect.close();
        }
    }

    @Test
    void closeStopsProcessBeforeBlockingOutputClosesAndDoesNotWaitForThem() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        BlockingCloseInputStream stderr = new BlockingCloseInputStream(processAlive);
        ControllableProcess process = new ControllableProcess(stdout, stderr, processAlive);
        DefaultSession rawSession = session(process);
        DefaultExpect expect = new DefaultExpect(rawSession, ExpectSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> close = null;
        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            close = executor.submit(expect::close);

            assertTrue(process.awaitDestroyed(), "process cleanup must precede helper output closure");
            close.get(1, TimeUnit.SECONDS);
            assertTrue(rawSession.exitCompleted());
            assertFalse(rawSession.onExit().isDone());
            assertTrue(stdout.awaitCloseStarted());
            assertTrue(stderr.awaitCloseStarted());
            assertTrue(stdout.destroyedBeforeClose());
            assertTrue(stderr.destroyedBeforeClose());
            assertFalse(stdout.closeCompleted());
            assertFalse(stderr.closeCompleted());
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            if (close != null) {
                close.get(1, TimeUnit.SECONDS);
            }
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(stdout.awaitCloseCompleted());
        assertTrue(stderr.awaitCloseCompleted());
        rawSession.onExit().get(1, TimeUnit.SECONDS);
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
    }

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

    @Test
    void outputOnlyDecoderIsBoundedAndTerminatesExpectForEitherStream() throws Exception {
        for (String failingSource : List.of("stdout", "stderr")) {
            ThreadSelectedOutputOnlyCharset charset = new ThreadSelectedOutputOnlyCharset(failingSource);
            CloseTrackingInputStream failing = new CloseTrackingInputStream(new byte[] {1});
            BlockingUntilClosedInputStream other = new BlockingUntilClosedInputStream();
            InputStream stdout = failingSource.equals("stdout") ? failing : other;
            InputStream stderr = failingSource.equals("stderr") ? failing : other;
            ControllableProcess process = new ControllableProcess(stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultExpect expect = new DefaultExpect(
                    rawSession,
                    ExpectSettings.defaults()
                            .withCharset(charset)
                            .withTranscriptLimit(16)
                            .withMatchBufferLimit(16));
            try {
                ExpectException failure =
                        assertThrows(ExpectException.class, () -> expect.expectText("never", Duration.ofSeconds(1)));

                assertEquals(ExpectException.Reason.FAILURE, failure.reason());
                assertInstanceOf(IncrementalTextDecoder.DecoderStateException.class, failure.getCause());
                assertTrue(failure.transcript().malformed());
                assertTrue(failure.transcript().text().length() <= 16);
                rawSession.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
                assertTrue(failing.awaitClose());
                assertTrue(other.awaitClose());
                assertEquals(1, failing.closeCalls());
                assertEquals(1, other.closeCalls());
            } finally {
                expect.close();
            }
        }
    }

    @Test
    void zeroLengthPumpsBackOffAndCloseExactlyOnceForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            CloseTrackingInputStream eofStream = new CloseTrackingInputStream(new byte[0]);
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : eofStream;
            InputStream stderr = zeroStdout ? eofStream : zeroStream;
            ControllableProcess process = new ControllableProcess(stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultExpect expect =
                    new DefaultExpect(rawSession, ExpectSettings.defaults(), backoff, PumpStarter.threading());
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads());

                expect.close();
                backoff.release();
                rawSession.onExit().get(1, TimeUnit.SECONDS);

                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive());
                assertEquals(1, zeroStream.reads());
                assertTrue(zeroStream.awaitClose());
                assertTrue(eofStream.awaitClose());
                assertEquals(1, zeroStream.closeCalls());
                assertEquals(1, eofStream.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                backoff.release();
                expect.close();
            }
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

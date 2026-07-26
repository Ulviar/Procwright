/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.BlockingFirstRegexEvaluator;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.FeedInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.GatedEofInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.expectFailure;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.openExpect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectTerminalArbitrationTest {

    @Test
    void timeoutArbitrationPrefersClosedState() throws Exception {
        BlockingFirstRegexEvaluator evaluator = new BlockingFirstRegexEvaluator();
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        ControllableProcess process = new ControllableProcess(new FeedInputStream(), new FeedInputStream());
        DefaultExpect expect = expect(process, limiter, evaluator, ExpectSettings.defaults(), PumpStarter.threading());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> match =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofHours(1)));
            assertTrue(evaluator.awaitStarted());

            expect.close();
            ExpectException failure = expectFailure(match);

            assertEquals(ExpectException.Reason.CLOSED, failure.reason());
            assertNull(failure.getCause());
            assertFalse(process.isAlive());
            assertEquals(0, limiter.availablePermits());
        } finally {
            evaluator.release();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
        }
    }

    @Test
    void timeoutArbitrationPreservesOutputFailureAndItsExactCause() throws Exception {
        BlockingFirstRegexEvaluator evaluator = new BlockingFirstRegexEvaluator();
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        IllegalStateException outputFailure = new IllegalStateException("output failed");
        GatedFailureInputStream stdout = new GatedFailureInputStream(outputFailure);
        FeedInputStream stderr = new FeedInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultExpect expect = expect(process, limiter, evaluator, ExpectSettings.defaults(), PumpStarter.threading());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> match =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofHours(1)));
            assertTrue(evaluator.awaitStarted());

            stdout.fail();
            assertTrue(process.awaitDestroyed());
            ExpectException failure = expectFailure(match);

            assertEquals(ExpectException.Reason.FAILURE, failure.reason());
            assertSame(outputFailure, failure.getCause());
            assertFalse(process.isAlive());
            assertEquals(0, limiter.availablePermits());
        } finally {
            evaluator.release();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
        }
    }

    @Test
    void timeoutArbitrationPrefersEofToTimeout() throws Exception {
        BlockingFirstRegexEvaluator evaluator = new BlockingFirstRegexEvaluator();
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        GatedEofInputStream stdout = new GatedEofInputStream();
        PumpCompletionTracker pumpStarter = new PumpCompletionTracker();
        ControllableProcess process = new ControllableProcess(stdout, new FeedInputStream());
        DefaultExpect expect = expect(process, limiter, evaluator, ExpectSettings.defaults(), pumpStarter);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> match =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofHours(1)));
            assertTrue(evaluator.awaitStarted());

            stdout.finish();
            assertTrue(pumpStarter.awaitStdoutStopped());
            ExpectException failure = expectFailure(match);

            assertEquals(ExpectException.Reason.EOF, failure.reason());
            assertNull(failure.getCause());
            assertTrue(process.awaitDestroyed());
            assertFalse(process.isAlive());
            assertEquals(
                    143, expect.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertEquals(0, limiter.availablePermits());
        } finally {
            stdout.finish();
            evaluator.release();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
        }
    }

    @Test
    void regexErrorAfterOutputFailureCancellationIsIgnoredWithoutMutatingTheSelectedFailure() throws Exception {
        IllegalStateException outputFailure = new IllegalStateException("output failed first");
        AssertionError evaluatorError = new AssertionError("late regex evaluator failure");
        GatedFailureInputStream stdout = new GatedFailureInputStream(outputFailure);
        BlockingErrorRegexEvaluator evaluator = new BlockingErrorRegexEvaluator(evaluatorError);
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        AtomicInteger lateReports = new AtomicInteger();
        AtomicInteger uncaughtReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaughtReports.incrementAndGet());
        ControllableProcess process = new ControllableProcess(stdout, new FeedInputStream());
        DefaultExpect expect = openExpect(
                process,
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        limiter,
                        evaluator,
                        (thread, error) -> lateReports.incrementAndGet()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> match =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofHours(1)));
            assertTrue(evaluator.awaitStarted());

            stdout.fail();
            ExpectException failure = expectFailure(match);

            assertEquals(ExpectException.Reason.FAILURE, failure.reason());
            assertSame(outputFailure, failure.getCause());
            assertEquals(0, limiter.availablePermits(), "the cancelled evaluator must retain its permit");

            evaluator.release();
            evaluator.awaitInvocationStopped();
            assertTrue(eventually(() -> limiter.availablePermits() == 1));
            assertEquals(0, outputFailure.getSuppressed().length);
            assertEquals(0, lateReports.get());
            assertEquals(0, uncaughtReports.get());
        } finally {
            evaluator.release();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void regexErrorCompletedBeforeCloseKeepsExactIdentityAndIsNotReportedAsLate() throws Exception {
        AssertionError evaluatorError = new AssertionError("regex evaluator failed first");
        BlockingErrorRegexEvaluator evaluator = new BlockingErrorRegexEvaluator(evaluatorError);
        AtomicInteger reports = new AtomicInteger();
        DefaultExpect expect = openExpect(
                new ControllableProcess(new FeedInputStream(), new FeedInputStream()),
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        new BoundedTaskLimiter(1),
                        evaluator,
                        (thread, error) -> reports.incrementAndGet()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> match =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofHours(1)));
            assertTrue(evaluator.awaitStarted());

            evaluator.release();
            ExecutionException wrapper = assertThrows(ExecutionException.class, () -> match.get(1, TimeUnit.SECONDS));

            assertSame(evaluatorError, wrapper.getCause());
            assertEquals(0, reports.get());
            expect.close();
        } finally {
            evaluator.release();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
        }
    }

    @Test
    void eofSelectedBeforeCloseRemainsTheReasonForFutureOperations() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        PumpCompletionTracker pumps = new PumpCompletionTracker();
        DefaultExpect expect = expect(
                new ControllableProcess(stdout, new FeedInputStream()),
                new BoundedTaskLimiter(1),
                ExpectRegexMatcher::evaluate,
                ExpectSettings.defaults(),
                pumps);
        try {
            stdout.finish();
            assertTrue(pumps.awaitStdoutStopped());

            expect.close();
            ExpectException failure = assertThrows(ExpectException.class, () -> expect.expectTextMatch("never"));

            assertEquals(ExpectException.Reason.EOF, failure.reason());
            assertNull(failure.getCause());
        } finally {
            stdout.finish();
            expect.close();
        }
    }

    @Test
    void outputFailureSelectedBeforeCloseRemainsTheReasonAndCauseForFutureOperations() throws Exception {
        IllegalStateException outputFailure = new IllegalStateException("output failed");
        GatedFailureInputStream stdout = new GatedFailureInputStream(outputFailure);
        ControllableProcess process = new ControllableProcess(stdout, new FeedInputStream());
        DefaultExpect expect = openExpect(process, session -> new DefaultExpect(session, ExpectSettings.defaults()));
        try {
            stdout.fail();
            assertTrue(process.awaitDestroyed());

            expect.close();
            ExpectException failure =
                    assertThrows(ExpectException.class, () -> expect.expectRegexMatch(Pattern.compile("never")));

            assertEquals(ExpectException.Reason.FAILURE, failure.reason());
            assertSame(outputFailure, failure.getCause());
        } finally {
            expect.close();
        }
    }

    @Test
    void terminalClaimBeforeRegexCommitPreventsAStaleSuccess() {
        ControllableProcess process = new ControllableProcess(new FeedInputStream(), new FeedInputStream());
        AtomicReference<DefaultExpect> reference = new AtomicReference<>();
        DefaultExpect expect = openExpect(
                process,
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        new BoundedTaskLimiter(1),
                        (pattern, text, searchStart) -> {
                            reference.get().close();
                            return new ExpectRegexMatcher.Evaluation(0, 0, "", List.of());
                        }));
        reference.set(expect);

        ExpectException failure = assertThrows(
                ExpectException.class, () -> expect.expectRegexMatch(Pattern.compile(".*"), Duration.ofHours(1)));

        assertEquals(ExpectException.Reason.CLOSED, failure.reason());
    }

    @Test
    void regexEvaluatorErrorKeepsExactIdentity() {
        AssertionError evaluatorError = new AssertionError("regex evaluator failed");
        DefaultExpect expect = openExpect(
                new ControllableProcess(new FeedInputStream(), new FeedInputStream()),
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        new BoundedTaskLimiter(1),
                        (pattern, text, searchStart) -> {
                            throw evaluatorError;
                        }));
        try {
            AssertionError actual = assertThrows(
                    AssertionError.class,
                    () -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofSeconds(1)));

            assertSame(evaluatorError, actual);
        } finally {
            expect.close();
        }
    }

    private static DefaultExpect expect(
            ControllableProcess process,
            BoundedTaskLimiter limiter,
            ExpectRegexMatcher.Evaluator evaluator,
            ExpectSettings settings,
            PumpStarter pumpStarter) {
        return openExpect(
                process,
                session -> new DefaultExpect(
                        session, settings, ZeroReadBackoff.exponential(), pumpStarter, limiter, evaluator));
    }

    static final class BlockingErrorRegexEvaluator implements ExpectRegexMatcher.Evaluator {

        final Error failure;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();

        BlockingErrorRegexEvaluator(Error failure) {
            this.failure = failure;
        }

        @Override
        public ExpectRegexMatcher.Evaluation find(Pattern pattern, String text, int searchStart) {
            worker.set(Thread.currentThread());
            started.countDown();
            try {
                awaitUninterruptibly(release);
                throw failure;
            } finally {
                stopped.countDown();
            }
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        Thread worker() {
            return worker.get();
        }

        void awaitInvocationStopped() throws InterruptedException {
            assertTrue(stopped.await(1, TimeUnit.SECONDS), "controlled matcher invocation must terminate");
        }
    }

    static final class GatedFailureInputStream extends InputStream {

        final RuntimeException failure;
        final CountDownLatch failed = new CountDownLatch(1);

        GatedFailureInputStream(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(failed);
            throw failure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            failed.countDown();
        }

        void fail() {
            failed.countDown();
        }
    }

    static final class PumpCompletionTracker implements PumpStarter {

        final CountDownLatch stdoutStopped = new CountDownLatch(1);

        @Override
        public Thread start(String namePrefix, Runnable task) {
            return Threading.start(namePrefix, () -> {
                try {
                    task.run();
                } finally {
                    if (namePrefix.contains("stdout")) {
                        stdoutStopped.countDown();
                    }
                }
            });
        }

        boolean awaitStdoutStopped() throws InterruptedException {
            return stdoutStopped.await(1, TimeUnit.SECONDS);
        }
    }
}

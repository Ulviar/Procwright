/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DefaultExpectTest {

    @Test
    void matcherTimeoutIsBoundedRecoverableAndRetainsCapacityUntilTheMatcherStops() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        BlockingFirstRegexEvaluator evaluator = new BlockingFirstRegexEvaluator();
        DefaultExpect expect = new DefaultExpect(
                rawSession,
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                limiter,
                evaluator);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            long started = System.nanoTime();
            Future<ExpectMatch> timedMatch =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofMillis(200)));
            assertTrue(evaluator.awaitStarted());

            ExpectException failure = expectFailure(timedMatch);

            assertEquals(ExpectException.Reason.TIMEOUT, failure.reason());
            assertNull(failure.getCause());
            assertTrue(
                    System.nanoTime() - started < Duration.ofSeconds(1).toNanos(),
                    "regex timeout must bound caller wall-clock waiting");
            assertTrue(process.isAlive(), "a match timeout must leave the owned session open");
            assertEquals(0, limiter.availablePermits(), "the non-cooperative matcher must retain its permit");

            stdout.offer("literal-ready");
            assertEquals(
                    "literal-ready",
                    expect.expectTextMatch("literal-ready", Duration.ofSeconds(1))
                            .matched());

            evaluator.release();
            assertTrue(evaluator.awaitStopped());
            evaluator.awaitInvocationStopped();
            assertTrue(eventually(() -> limiter.availablePermits() == 1));

            stdout.offer(" regex-42");
            ExpectMatch recovered = expect.expectRegexMatch(Pattern.compile("regex-(\\d+)"), Duration.ofSeconds(1));
            assertEquals("regex-42", recovered.matched());
            assertEquals(List.of("42"), recovered.groups());
            assertTrue(process.isAlive());
        } finally {
            evaluator.release();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
        }
    }

    @Test
    void ordinaryNoOutputTimeoutIsRecoverableForLiteralAndRegexOperations() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        CountDownLatch evaluated = new CountDownLatch(1);
        DefaultExpect expect = new DefaultExpect(
                session(process),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(1),
                (pattern, text, searchStart) -> {
                    evaluated.countDown();
                    return DefaultExpect.evaluateRegex(pattern, text, searchStart);
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> timedMatch =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("missing"), Duration.ofMillis(300)));
            assertTrue(evaluated.await(1, TimeUnit.SECONDS));

            ExpectException failure = expectFailure(timedMatch);

            assertEquals(ExpectException.Reason.TIMEOUT, failure.reason());
            assertNull(failure.getCause());
            assertTrue(process.isAlive());

            stdout.offer("literal regex-7");
            assertEquals(
                    "literal",
                    expect.expectTextMatch("literal", Duration.ofSeconds(1)).matched());
            ExpectMatch recovered = expect.expectRegexMatch(Pattern.compile("regex-(\\d+)"), Duration.ofSeconds(1));
            assertEquals("regex-7", recovered.matched());
            assertEquals(List.of("7"), recovered.groups());
        } finally {
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void saturatedRegexLimiterTimesOutRecoverablyWithoutStartingTheMatcher() throws Exception {
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        Future<String> occupier = executor.submit(() -> BoundedTaskRunner.run(
                limiter, "procwright-expect-regex-occupier-", deadline(Duration.ofSeconds(5)), () -> {
                    occupied.countDown();
                    awaitUninterruptibly(release);
                    return "released";
                }));
        assertTrue(occupied.await(1, TimeUnit.SECONDS));

        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        AtomicInteger evaluations = new AtomicInteger();
        CountingMatchBufferProbe workProbe = new CountingMatchBufferProbe();
        DefaultExpect expect = new DefaultExpect(
                rawSession,
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                limiter,
                (pattern, text, searchStart) -> {
                    evaluations.incrementAndGet();
                    return DefaultExpect.evaluateRegex(pattern, text, searchStart);
                },
                () -> {},
                Threading::reportUncaught,
                workProbe);
        try {
            List<Future<ExpectMatch>> waiters = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                waiters.add(executor.submit(
                        () -> expect.expectRegexMatch(Pattern.compile("ready"), Duration.ofSeconds(1))));
            }
            assertTrue(eventually(
                    () -> countOccurrences(expect.transcript().text(), "expect regex: <redacted>") == waiters.size()));

            stdout.offer("literal-ready");
            assertEquals(
                    "literal-ready",
                    expect.expectTextMatch("literal-ready", Duration.ofSeconds(1))
                            .matched());
            assertEquals(0, workProbe.snapshottedCharacters.get());

            for (Future<ExpectMatch> waiting : waiters) {
                ExpectException failure = expectFailure(waiting);
                assertEquals(ExpectException.Reason.TIMEOUT, failure.reason());
                assertNull(failure.getCause());
            }
            assertEquals(0, evaluations.get(), "no matcher task may start without a retained permit");
            assertEquals(0, limiter.availablePermits());
            assertTrue(process.isAlive());

            release.countDown();
            assertEquals("released", occupier.get(1, TimeUnit.SECONDS));
            stdout.offer(" regex-ready");
            assertEquals(
                    "regex-ready",
                    expect.expectRegexMatch(Pattern.compile("regex-ready"), Duration.ofSeconds(1))
                            .matched());
            assertTrue(evaluations.get() >= 1);
            assertTrue(workProbe.snapshottedCharacters.get() > 0);
            assertTrue(process.isAlive());
        } finally {
            release.countDown();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutArbitrationPrefersClosedState() throws Exception {
        BlockingFirstRegexEvaluator evaluator = new BlockingFirstRegexEvaluator();
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
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
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
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
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
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
            assertTrue(process.isAlive());
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
    void closeSelectedBeforeRegexErrorKeepsClosedAndReportsTheLosingErrorOnce() throws Exception {
        AssertionError evaluatorError = new AssertionError("late regex evaluator failure");
        BlockingErrorRegexEvaluator evaluator = new BlockingErrorRegexEvaluator(evaluatorError);
        BlockingTerminalSelectionProbe transition = new BlockingTerminalSelectionProbe();
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> reportedThread = new AtomicReference<>();
        AtomicReference<Error> reportedError = new AtomicReference<>();
        AtomicReference<Object> expectMonitor = new AtomicReference<>();
        AtomicBoolean callbackHeldMonitor = new AtomicBoolean();
        ControllableProcess process = new ControllableProcess(new FeedInputStream(), new FeedInputStream());
        DefaultExpect expect = new DefaultExpect(
                session(process),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(1),
                evaluator,
                transition,
                (thread, error) -> {
                    reports.incrementAndGet();
                    reportedThread.set(thread);
                    reportedError.set(error);
                    callbackHeldMonitor.set(Thread.holdsLock(expectMonitor.get()));
                });
        expectMonitor.set(expectMonitor(expect));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> close = null;
        try {
            Future<ExpectMatch> match =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofHours(1)));
            assertTrue(evaluator.awaitStarted());

            close = executor.submit(expect::close);
            assertTrue(transition.awaitSelected());
            evaluator.release();
            ExpectException failure = expectFailure(match);

            assertEquals(ExpectException.Reason.CLOSED, failure.reason());
            assertNull(failure.getCause());
            assertEquals(1, reports.get());
            assertSame(evaluatorError, reportedError.get());
            assertSame(evaluator.worker(), reportedThread.get());
            assertFalse(callbackHeldMonitor.get(), "late failure reporting must run outside the expect monitor");
        } finally {
            evaluator.release();
            transition.release();
            if (close != null) {
                close.get(1, TimeUnit.SECONDS);
            }
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
        }
    }

    @Test
    void regexErrorBetweenOutputFailureSelectionAndCancellationIsSuppressedByTheSelectedFailure() throws Exception {
        IllegalStateException outputFailure = new IllegalStateException("output failed first");
        AssertionError evaluatorError = new AssertionError("late regex evaluator failure");
        GatedFailureInputStream stdout = new GatedFailureInputStream(outputFailure);
        BlockingErrorRegexEvaluator evaluator = new BlockingErrorRegexEvaluator(evaluatorError);
        BlockingTerminalSelectionProbe transition = new BlockingTerminalSelectionProbe();
        AtomicInteger reports = new AtomicInteger();
        ControllableProcess process = new ControllableProcess(stdout, new FeedInputStream());
        DefaultExpect expect = new DefaultExpect(
                session(process),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(1),
                evaluator,
                transition,
                (thread, error) -> reports.incrementAndGet());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> match =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofHours(1)));
            assertTrue(evaluator.awaitStarted());

            stdout.fail();
            assertTrue(transition.awaitSelected());
            evaluator.release();
            ExpectException failure = expectFailure(match);

            assertEquals(ExpectException.Reason.FAILURE, failure.reason());
            assertSame(outputFailure, failure.getCause());
            assertIdentitySuppressedOnce(outputFailure, evaluatorError);
            assertEquals(0, reports.get());
        } finally {
            evaluator.release();
            transition.release();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            evaluator.awaitInvocationStopped();
        }
    }

    @Test
    void regexErrorAfterOutputFailureCancellationIsStillSuppressedByTheSelectedFailure() throws Exception {
        IllegalStateException outputFailure = new IllegalStateException("output failed first");
        AssertionError evaluatorError = new AssertionError("late regex evaluator failure");
        GatedFailureInputStream stdout = new GatedFailureInputStream(outputFailure);
        BlockingErrorRegexEvaluator evaluator = new BlockingErrorRegexEvaluator(evaluatorError);
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        AtomicInteger lateReports = new AtomicInteger();
        AtomicInteger uncaughtReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaughtReports.incrementAndGet());
        ControllableProcess process = new ControllableProcess(stdout, new FeedInputStream());
        DefaultExpect expect = new DefaultExpect(
                session(process),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                limiter,
                evaluator,
                () -> {},
                (thread, error) -> lateReports.incrementAndGet());
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
            assertIdentitySuppressedOnce(outputFailure, evaluatorError);
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
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(new FeedInputStream(), new FeedInputStream())),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(1),
                evaluator,
                () -> {},
                (thread, error) -> reports.incrementAndGet());
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
                new BoundedTaskRunner.Limiter(1),
                DefaultExpect::evaluateRegex,
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
        DefaultExpect expect = new DefaultExpect(session(process), ExpectSettings.defaults());
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
    void concurrentRegexConsumersDoNotCommitTheSameCursorRevision() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        stdout.offer("TOKEN|TOKEN");
        ControllableProcess process = new ControllableProcess(stdout, new FeedInputStream());
        CountDownLatch firstEvaluations = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger evaluations = new AtomicInteger();
        DefaultExpect expect = new DefaultExpect(
                session(process),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(2),
                (pattern, text, searchStart) -> {
                    int invocation = evaluations.incrementAndGet();
                    if (invocation <= 2) {
                        firstEvaluations.countDown();
                        awaitUninterruptibly(release);
                    }
                    return DefaultExpect.evaluateRegex(pattern, text, searchStart);
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            assertTrue(eventually(() -> expect.transcript().text().contains("TOKEN|TOKEN")));
            Future<ExpectMatch> first =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("TOKEN"), Duration.ofSeconds(1)));
            Future<ExpectMatch> second =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("TOKEN"), Duration.ofSeconds(1)));
            assertTrue(firstEvaluations.await(1, TimeUnit.SECONDS));

            release.countDown();
            ExpectMatch firstMatch = first.get(1, TimeUnit.SECONDS);
            ExpectMatch secondMatch = second.get(1, TimeUnit.SECONDS);

            assertEquals("TOKEN", firstMatch.matched());
            assertEquals("TOKEN", secondMatch.matched());
            assertEquals(Set.of("", "|"), Set.of(firstMatch.before(), secondMatch.before()));
            assertEquals(3, evaluations.get(), "the losing snapshot must be evaluated again from the new cursor");
        } finally {
            release.countDown();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalClaimBeforeRegexCommitPreventsAStaleSuccess() {
        ControllableProcess process = new ControllableProcess(new FeedInputStream(), new FeedInputStream());
        AtomicReference<DefaultExpect> reference = new AtomicReference<>();
        DefaultExpect expect = new DefaultExpect(
                session(process),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(1),
                (pattern, text, searchStart) -> {
                    reference.get().close();
                    return new DefaultExpect.RegexEvaluation(0, 0, "", List.of());
                });
        reference.set(expect);

        ExpectException failure = assertThrows(
                ExpectException.class, () -> expect.expectRegexMatch(Pattern.compile(".*"), Duration.ofHours(1)));

        assertEquals(ExpectException.Reason.CLOSED, failure.reason());
    }

    @Test
    void regexEvaluatorErrorKeepsExactIdentity() {
        AssertionError evaluatorError = new AssertionError("regex evaluator failed");
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(new FeedInputStream(), new FeedInputStream())),
                ExpectSettings.defaults(),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(1),
                (pattern, text, searchStart) -> {
                    throw evaluatorError;
                });
        try {
            AssertionError actual = assertThrows(
                    AssertionError.class,
                    () -> expect.expectRegexMatch(Pattern.compile("never"), Duration.ofSeconds(1)));

            assertSame(evaluatorError, actual);
        } finally {
            expect.close();
        }
    }

    @Test
    void defaultExpectUsesTheSharedProductionRegexLimiter() throws Exception {
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(new FeedInputStream(), new FeedInputStream())),
                ExpectSettings.defaults());
        try {
            java.lang.reflect.Field field = DefaultExpect.class.getDeclaredField("regexLimiter");
            field.setAccessible(true);

            assertSame(BoundedTaskRunner.REGEX_MATCHES, field.get(expect));
        } finally {
            expect.close();
        }
    }

    @Test
    void regexSnapshotReconcilesAfterConcurrentAppendAndFullPrefixTrim() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        stdout.offer("before:TOKEN");
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        DefaultSession rawSession = session(process);
        CountDownLatch matching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DefaultExpect expect = new DefaultExpect(
                rawSession,
                ExpectSettings.defaults().withTranscriptLimit(256).withMatchBufferLimit(24),
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                new BoundedTaskRunner.Limiter(1),
                (pattern, text, searchStart) -> {
                    matching.countDown();
                    awaitUninterruptibly(release);
                    return DefaultExpect.evaluateRegex(pattern, text, searchStart);
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(eventually(() -> expect.transcript().text().contains("TOKEN")));
            Future<ExpectMatch> first =
                    executor.submit(() -> expect.expectRegexMatch(Pattern.compile("(TOKEN)"), Duration.ofSeconds(1)));
            assertTrue(matching.await(1, TimeUnit.SECONDS));

            stdout.offer("x".repeat(20) + "NEXT");
            assertTrue(
                    eventually(() -> expect.transcript().text().contains("NEXT")),
                    "the output pump must publish while regex matching is in progress");
            assertFalse(first.isDone());
            release.countDown();

            ExpectMatch matched = first.get(1, TimeUnit.SECONDS);
            assertEquals("TOKEN", matched.matched());
            assertEquals(List.of("TOKEN"), matched.groups());
            assertEquals("before:", matched.before());

            ExpectMatch next = expect.expectTextMatch("NEXT", Duration.ofSeconds(1));
            assertEquals("x".repeat(20), next.before());
        } finally {
            release.countDown();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void rolledMatchBufferPreservesTimeoutRecoveryAndCloseSemantics() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        ControllableProcess process = new ControllableProcess(stdout, new FeedInputStream());
        DefaultExpect expect = new DefaultExpect(
                session(process),
                ExpectSettings.defaults().withTranscriptLimit(512).withMatchBufferLimit(32));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            stdout.offer("x".repeat(128) + "READY");
            assertTrue(eventually(() -> expect.transcript().text().contains("READY")));

            ExpectException timeout = assertThrows(
                    ExpectException.class, () -> expect.expectTextMatch("missing", Duration.ofMillis(100)));
            assertEquals(ExpectException.Reason.TIMEOUT, timeout.reason());
            assertTrue(process.isAlive());

            ExpectMatch recovered = expect.expectTextMatch("READY", Duration.ofSeconds(1));
            assertEquals("READY", recovered.matched());
            assertEquals("x".repeat(27), recovered.before());

            String beforeWaiting = expect.transcript().text();
            Future<ExpectMatch> waiting = executor.submit(() -> expect.expectTextMatch("never", Duration.ofHours(1)));
            assertTrue(eventually(() -> !expect.transcript().text().equals(beforeWaiting)));
            expect.close();

            ExpectException closed = expectFailure(waiting);
            assertEquals(ExpectException.Reason.CLOSED, closed.reason());
            assertFalse(process.isAlive());
        } finally {
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void literalMatchesAdvanceCursorWithoutImplicitOverlap() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(stdout, new FeedInputStream())), ExpectSettings.defaults());
        try {
            stdout.offer("ababa");
            assertEquals(
                    "aba", expect.expectTextMatch("aba", Duration.ofSeconds(1)).matched());

            ExpectException overlap =
                    assertThrows(ExpectException.class, () -> expect.expectTextMatch("aba", Duration.ofMillis(100)));
            assertEquals(ExpectException.Reason.TIMEOUT, overlap.reason());

            ExpectMatch tail = expect.expectTextMatch("ba", Duration.ofSeconds(1));
            assertEquals("", tail.before());
            assertEquals("ba", tail.matched());
        } finally {
            expect.close();
        }
    }

    @Test
    void oversizedLiteralTimesOutWithoutBreakingARecoverableExpectHelper() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(stdout, new FeedInputStream())),
                ExpectSettings.defaults().withMatchBufferLimit(4));
        try {
            stdout.offer("abcdefgh");
            assertTrue(eventually(() -> expect.transcript().text().contains("abcdefgh")));

            ExpectException oversized =
                    assertThrows(ExpectException.class, () -> expect.expectTextMatch("abcde", Duration.ofMillis(100)));
            assertEquals(ExpectException.Reason.TIMEOUT, oversized.reason());

            ExpectMatch recovered = expect.expectTextMatch("efgh", Duration.ofSeconds(1));
            assertEquals("", recovered.before());
        } finally {
            expect.close();
        }
    }

    @Test
    void repeatedAndZeroWidthRegexMatchesPreserveCursorSemantics() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(stdout, new FeedInputStream())), ExpectSettings.defaults());
        try {
            stdout.offer("abab");
            assertTrue(eventually(() -> expect.transcript().text().contains("abab")));

            ExpectMatch firstZeroWidth = expect.expectRegexMatch(Pattern.compile("(?=a)"), Duration.ofSeconds(1));
            ExpectMatch repeatedZeroWidth = expect.expectRegexMatch(Pattern.compile("(?=a)"), Duration.ofSeconds(1));
            assertEquals("", firstZeroWidth.matched());
            assertEquals("", repeatedZeroWidth.matched());
            assertEquals("", firstZeroWidth.before());
            assertEquals("", repeatedZeroWidth.before());

            assertEquals("ab", expect.expectRegexMatch(Pattern.compile("ab")).matched());
            assertEquals("ab", expect.expectRegexMatch(Pattern.compile("ab")).matched());
        } finally {
            expect.close();
        }
    }

    @Test
    void literalMatchSurvivesRingWrapAndSplitUtf8CodePoint() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(stdout, new FeedInputStream())),
                ExpectSettings.defaults().withMatchBufferLimit(8));
        byte[] emoji = "\uD83D\uDE03".getBytes(StandardCharsets.UTF_8);
        try {
            stdout.offer("0123456789A");
            stdout.offer(java.util.Arrays.copyOfRange(emoji, 0, 2));
            stdout.offer(java.util.Arrays.copyOfRange(emoji, 2, emoji.length));
            stdout.offer("B");

            ExpectMatch match = expect.expectTextMatch("A\uD83D\uDE03B", Duration.ofSeconds(1));

            assertEquals("6789", match.before());
            assertEquals("A\uD83D\uDE03B", match.matched());
        } finally {
            expect.close();
        }
    }

    @Test
    void partialLiteralStateRemainsValidWhileItsOldPrefixIsEvicted() throws Exception {
        int limit = 32;
        String literal = "a".repeat(limit - 1) + "b";
        FeedInputStream stdout = new FeedInputStream();
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(stdout, new FeedInputStream())),
                ExpectSettings.defaults().withTranscriptLimit(1_024).withMatchBufferLimit(limit));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> waiting = executor.submit(() -> expect.expectTextMatch(literal, Duration.ofSeconds(2)));
            assertTrue(eventually(() -> expect.transcript().text().contains("expect text: <redacted>")));

            stdout.offer("a".repeat(limit));
            for (int index = 0; index < 100; index++) {
                stdout.offer("aa");
            }
            stdout.offer("b");

            ExpectMatch match = waiting.get(2, TimeUnit.SECONDS);
            assertEquals(literal, match.matched());
            assertEquals("", match.before());
        } finally {
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void randomizedChunkedOutputMatchesRetainedSuffixReference() throws Exception {
        int limit = 512;
        Random random = new Random(734_921L);
        StringBuilder generated = new StringBuilder("p".repeat(1_000));
        List<String> tokens = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            generated.append(randomUnicodeText(random, random.nextInt(11)));
            String token = "<token-" + index + ">";
            tokens.add(token);
            generated.append(token);
        }
        String output = generated.toString();
        String retained = output.substring(output.length() - limit);
        byte[] encoded = output.getBytes(StandardCharsets.UTF_8);

        FeedInputStream stdout = new FeedInputStream();
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(stdout, new FeedInputStream())),
                ExpectSettings.defaults().withTranscriptLimit(2_048).withMatchBufferLimit(limit));
        try {
            for (int offset = 0; offset < encoded.length; ) {
                int count = Math.min(encoded.length - offset, 1 + random.nextInt(17));
                stdout.offer(java.util.Arrays.copyOfRange(encoded, offset, offset + count));
                offset += count;
            }
            String finalToken = tokens.get(tokens.size() - 1);
            assertTrue(eventually(() -> expect.transcript().text().contains(finalToken)));

            int referenceCursor = 0;
            for (String token : tokens) {
                int expectedStart = retained.indexOf(token, referenceCursor);
                if (expectedStart < 0) {
                    continue;
                }
                ExpectMatch actual = expect.expectTextMatch(token, Duration.ofSeconds(1));
                assertEquals(retained.substring(referenceCursor, expectedStart), actual.before());
                assertEquals(token, actual.matched());
                referenceCursor = expectedStart + token.length();
            }
        } finally {
            expect.close();
        }
    }

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
        AtomicReference<Object> expectMonitor = new AtomicReference<>();
        AtomicReference<Object> coordinatorLock = new AtomicReference<>();
        AtomicBoolean callbackHeldExpectMonitor = new AtomicBoolean();
        AtomicBoolean callbackHeldCoordinatorLock = new AtomicBoolean();
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
                new BoundedTaskRunner.Limiter(1),
                DefaultExpect::evaluateRegex,
                () -> {},
                (thread, error) -> {
                    reports.incrementAndGet();
                    reportedThread.set(thread);
                    reportedError.set(error);
                    suppressionsAtReport.set(List.of(error.getSuppressed()));
                    callbackHeldExpectMonitor.set(Thread.holdsLock(expectMonitor.get()));
                    callbackHeldCoordinatorLock.set(Thread.holdsLock(coordinatorLock.get()));
                    reported.countDown();
                });
        expectMonitor.set(expectMonitor(expect));
        coordinatorLock.set(outputCloseLock(expect));
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
            assertIdentitySuppressedOnce(pumpError, stdoutCloseFailure);
            assertIdentitySuppressedOnce(pumpError, stderrCloseFailure);
            assertEquals(2, pumpError.getSuppressed().length);
            assertEquals(2, suppressionsAtReport.get().size());
            assertFalse(callbackHeldExpectMonitor.get());
            assertFalse(callbackHeldCoordinatorLock.get());
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
    void pumpErrorLosingToEofIsReportedOnceAfterPhysicalCloseFailureIsAttached() throws Exception {
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
                new BoundedTaskRunner.Limiter(1),
                DefaultExpect::evaluateRegex,
                () -> {},
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
            assertIdentitySuppressedOnce(pumpError, stderrCloseFailure);
            assertEquals(List.of(stderrCloseFailure), suppressionsAtReport.get());
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

    private static DefaultSession session(Process process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8);
    }

    private static void assertIdentitySuppressedOnce(Throwable primary, Throwable expected) {
        assertEquals(
                1,
                List.of(primary.getSuppressed()).stream()
                        .filter(suppressed -> suppressed == expected)
                        .count());
    }

    private static String randomUnicodeText(Random random, int codePoints) {
        String[] alphabet = {"a", "b", "\u00E9", "\uD83D\uDE03"};
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < codePoints; index++) {
            text.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return text.toString();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Object expectMonitor(DefaultExpect expect) throws ReflectiveOperationException {
        java.lang.reflect.Field field = DefaultExpect.class.getDeclaredField("monitor");
        field.setAccessible(true);
        return field.get(expect);
    }

    private static Object outputCloseLock(DefaultExpect expect) throws ReflectiveOperationException {
        java.lang.reflect.Field pumpsField = DefaultExpect.class.getDeclaredField("outputPumps");
        pumpsField.setAccessible(true);
        Object pumps = pumpsField.get(expect);
        java.lang.reflect.Field lockField = OutputPumpCoordinator.class.getDeclaredField("outputCloseLock");
        lockField.setAccessible(true);
        return lockField.get(pumps);
    }

    private static DefaultExpect expect(
            ControllableProcess process,
            BoundedTaskRunner.Limiter limiter,
            DefaultExpect.RegexEvaluator evaluator,
            ExpectSettings settings,
            PumpStarter pumpStarter) {
        return new DefaultExpect(
                session(process), settings, ZeroReadBackoff.exponential(), pumpStarter, limiter, evaluator);
    }

    private static ExpectException expectFailure(Future<ExpectMatch> future) throws Exception {
        ExecutionException wrapper = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        return assertInstanceOf(ExpectException.class, wrapper.getCause());
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static CharsetDecoder passthroughDecoder(Charset charset) {
        return new CharsetDecoder(charset, 1, 1) {
            @Override
            protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                while (input.hasRemaining() && output.hasRemaining()) {
                    output.put((char) Byte.toUnsignedInt(input.get()));
                }
                return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
            }
        };
    }

    private static final class BlockingFirstRegexEvaluator implements DefaultExpect.RegexEvaluator {

        private final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public DefaultExpect.RegexEvaluation find(Pattern pattern, String text, int searchStart) {
            if (invocations.getAndIncrement() == 0) {
                started.countDown();
                try {
                    awaitUninterruptibly(release);
                } finally {
                    stopped.countDown();
                }
            }
            return DefaultExpect.evaluateRegex(pattern, text, searchStart);
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private boolean awaitStopped() throws InterruptedException {
            return stopped.await(1, TimeUnit.SECONDS);
        }

        private void awaitInvocationStopped() throws InterruptedException {
            assertTrue(stopped.await(1, TimeUnit.SECONDS), "controlled matcher invocation must terminate");
        }
    }

    private static final class BlockingErrorRegexEvaluator implements DefaultExpect.RegexEvaluator {

        private final Error failure;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicReference<Thread> worker = new AtomicReference<>();

        private BlockingErrorRegexEvaluator(Error failure) {
            this.failure = failure;
        }

        @Override
        public DefaultExpect.RegexEvaluation find(Pattern pattern, String text, int searchStart) {
            worker.set(Thread.currentThread());
            started.countDown();
            try {
                awaitUninterruptibly(release);
                throw failure;
            } finally {
                stopped.countDown();
            }
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private Thread worker() {
            return worker.get();
        }

        private void awaitInvocationStopped() throws InterruptedException {
            assertTrue(stopped.await(1, TimeUnit.SECONDS), "controlled matcher invocation must terminate");
        }
    }

    private static final class BlockingTerminalSelectionProbe implements DefaultExpect.TerminalSelectionProbe {

        private final CountDownLatch selected = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void afterSelection() {
            selected.countDown();
            awaitUninterruptibly(release);
        }

        private boolean awaitSelected() throws InterruptedException {
            return selected.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class GatedEofInputStream extends InputStream {

        private final CountDownLatch finish = new CountDownLatch(1);

        @Override
        public int read() {
            awaitUninterruptibly(finish);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            finish();
        }

        private void finish() {
            finish.countDown();
        }
    }

    private static final class GatedFailureInputStream extends InputStream {

        private final RuntimeException failure;
        private final CountDownLatch failed = new CountDownLatch(1);

        private GatedFailureInputStream(RuntimeException failure) {
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

        private void fail() {
            failed.countDown();
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

    private static final class PumpCompletionTracker implements PumpStarter {

        private final CountDownLatch stdoutStopped = new CountDownLatch(1);

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

        private boolean awaitStdoutStopped() throws InterruptedException {
            return stdoutStopped.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class GatedPublicationCharset extends Charset {

        private final CountDownLatch publicationReady = new CountDownLatch(1);
        private final CountDownLatch publicationRelease = new CountDownLatch(1);

        private GatedPublicationCharset() {
            super("X-Procwright-Expect-Gated-Publication", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains("stdout")) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    publicationReady.countDown();
                    awaitUninterruptibly(publicationRelease);
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitPublicationReady() throws InterruptedException {
            return publicationReady.await(1, TimeUnit.SECONDS);
        }

        private void releasePublication() {
            publicationRelease.countDown();
        }
    }

    private static final class TrackingPumpStarter implements PumpStarter {

        private final List<Thread> threads = new ArrayList<>();

        @Override
        public Thread start(String namePrefix, Runnable task) {
            Thread thread = Threading.start(namePrefix, task);
            threads.add(thread);
            return thread;
        }

        private boolean awaitStopped() throws InterruptedException {
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ThreadSelectedOutputOnlyCharset extends Charset {

        private final String failingThreadFragment;

        private ThreadSelectedOutputOnlyCharset(String failingThreadFragment) {
            super("X-Procwright-Expect-Output-Only-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains(failingThreadFragment)) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (output.hasRemaining()) {
                        output.put('x');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class CloseTrackingInputStream extends InputStream {

        private final byte[] bytes;
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private int index;

        private CloseTrackingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            return index < bytes.length ? Byte.toUnsignedInt(bytes[index++]) : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            int remaining = bytes.length - index;
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            System.arraycopy(bytes, index, target, offset, count);
            index += count;
            return count;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class FeedInputStream extends InputStream {

        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private byte[] current;
        private int index;
        private boolean closed;

        private synchronized void offer(String text) {
            offer(text.getBytes(StandardCharsets.UTF_8));
        }

        private synchronized void offer(byte[] bytes) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            chunks.add(bytes.clone());
            notifyAll();
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            while ((current == null || index == current.length) && chunks.isEmpty() && !closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for test input", exception);
                }
            }
            if (current == null || index == current.length) {
                current = chunks.poll();
                index = 0;
            }
            if (current == null) {
                return -1;
            }
            int count = Math.min(length, current.length - index);
            System.arraycopy(current, index, target, offset, count);
            index += count;
            return count;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private static final class CountingMatchBufferProbe implements BoundedMatchBuffer.WorkProbe {

        private final AtomicInteger snapshottedCharacters = new AtomicInteger();

        @Override
        public void appended(int count) {}

        @Override
        public void compared(int count) {}

        @Override
        public void snapshotted(int count) {
            snapshottedCharacters.addAndGet(count);
        }
    }

    private static final class BlockingUntilClosedInputStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() {
            awaitUninterruptibly(closed);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingCloseInputStream extends InputStream {

        private final AtomicBoolean processAlive;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);
        private final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();

        private BlockingCloseInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(closeCompleted);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            destroyedBeforeClose.set(!processAlive.get());
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            closeRelease.countDown();
        }

        private boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        private boolean closeCompleted() {
            return closeCompleted.getCount() == 0;
        }

        private int closeCalls() {
            return closes.get();
        }
    }

    private static final class ZeroForeverInputStream extends InputStream {

        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
        }

        private int reads() {
            return reads.get();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private Thread readerThread() {
            return readerThread;
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

    private static final class ControllableProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive;
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final InputStream stdout;
        private final InputStream stderr;

        private ControllableProcess(InputStream stdout, InputStream stderr) {
            this(stdout, stderr, new AtomicBoolean(true));
        }

        private ControllableProcess(InputStream stdout, InputStream stderr, AtomicBoolean alive) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.alive = alive;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException exception) {
                return false;
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer exitCode = exit.getNow(null);
            if (exitCode == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            alive.set(false);
            exit.complete(143);
            destroyed.countDown();
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
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private boolean awaitDestroyed() throws InterruptedException {
            return destroyed.await(1, TimeUnit.SECONDS);
        }
    }
}

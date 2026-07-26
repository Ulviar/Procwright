/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.BlockingFirstRegexEvaluator;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.FeedInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.expectFailure;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.openExpect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectMatcherAdmissionTest {

    @Test
    void abandonedMatcherMakesItsHandleTerminalUntilTheMatcherStops() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BlockingFirstRegexEvaluator evaluator = new BlockingFirstRegexEvaluator();
        DefaultExpect expect = openExpect(
                process,
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        limiter,
                        evaluator));
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
            assertTrue(eventually(() -> !process.isAlive()), "an abandoned matcher must retire its owned session");
            assertEquals(0, limiter.availablePermits(), "the non-cooperative matcher must retain its permit");

            ExpectException repeated = org.junit.jupiter.api.Assertions.assertThrows(
                    ExpectException.class, () -> expect.expectTextMatch("literal-ready", Duration.ofSeconds(1)));
            assertEquals(ExpectException.Reason.TIMEOUT, repeated.reason());

            evaluator.release();
            assertTrue(evaluator.awaitStopped());
            evaluator.awaitInvocationStopped();
            assertTrue(eventually(() -> limiter.availablePermits() == 1));

            ExpectException stillTerminal = org.junit.jupiter.api.Assertions.assertThrows(
                    ExpectException.class,
                    () -> expect.expectRegexMatch(Pattern.compile("regex-(\\d+)"), Duration.ofSeconds(1)));
            assertEquals(ExpectException.Reason.TIMEOUT, stillTerminal.reason());
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
        DefaultExpect expect = openExpect(
                process,
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        new BoundedTaskLimiter(1),
                        (pattern, text, searchStart) -> {
                            evaluated.countDown();
                            return ExpectRegexMatcher.evaluate(pattern, text, searchStart);
                        }));
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
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
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
        AtomicInteger evaluations = new AtomicInteger();
        DefaultExpect expect = openExpect(
                process,
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
                        limiter,
                        (pattern, text, searchStart) -> {
                            evaluations.incrementAndGet();
                            return ExpectRegexMatcher.evaluate(pattern, text, searchStart);
                        },
                        Threading::reportUncaught));
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
            assertTrue(process.isAlive());
        } finally {
            release.countDown();
            expect.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
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

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }
}

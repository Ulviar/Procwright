/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.BlockingFirstRegexEvaluator;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.FeedInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.expectFailure;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.openExpect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectMatcherAdmissionTest {

    @Test
    void abandonedMatcherMakesItsHandleTerminalUntilTheMatcherStops() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        BlockingFirstRegexEvaluator evaluator = new BlockingFirstRegexEvaluator();
        DefaultExpect expect = openExpect(
                process,
                session -> new DefaultExpect(
                        session,
                        ExpectSettings.defaults(),
                        ZeroReadBackoff.exponential(),
                        PumpStarter.threading(),
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

            ExpectException repeated = org.junit.jupiter.api.Assertions.assertThrows(
                    ExpectException.class, () -> expect.expectTextMatch("literal-ready", Duration.ofSeconds(1)));
            assertEquals(ExpectException.Reason.TIMEOUT, repeated.reason());

            evaluator.release();
            assertTrue(evaluator.awaitStopped());
            evaluator.awaitInvocationStopped();

            ExpectException stillTerminal = org.junit.jupiter.api.Assertions.assertThrows(
                    ExpectException.class,
                    () -> expect.expectRegexMatch(Pattern.compile("regex-(\\d+)"), Duration.ofSeconds(1)));
            assertEquals(ExpectException.Reason.TIMEOUT, stillTerminal.reason());
            assertEquals(1, evaluator.invocations.get(), "a terminal handle must not start another matcher");
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
}

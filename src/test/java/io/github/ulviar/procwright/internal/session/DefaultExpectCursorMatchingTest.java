/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.FeedInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.expectFailure;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectCursorMatchingTest {

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
                new BoundedTaskLimiter(2),
                (pattern, text, searchStart) -> {
                    int invocation = evaluations.incrementAndGet();
                    if (invocation <= 2) {
                        firstEvaluations.countDown();
                        awaitUninterruptibly(release);
                    }
                    return ExpectRegexMatcher.evaluate(pattern, text, searchStart);
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
                new BoundedTaskLimiter(1),
                (pattern, text, searchStart) -> {
                    matching.countDown();
                    awaitUninterruptibly(release);
                    return ExpectRegexMatcher.evaluate(pattern, text, searchStart);
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

    private static String randomUnicodeText(Random random, int codePoints) {
        String[] alphabet = {"a", "b", "\u00E9", "\uD83D\uDE03"};
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < codePoints; index++) {
            text.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return text.toString();
    }
}

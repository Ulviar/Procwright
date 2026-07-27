/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ExpectRegexMatcherTest {

    @Test
    void localAdmissionPrecedesOutputSnapshotAndEvaluation() throws Exception {
        SerializedRequestGate gate = new SerializedRequestGate();
        assertTrue(gate.acquireUntil(deadline(Duration.ofSeconds(1))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountingWorkProbe workProbe = new CountingWorkProbe();
        ExpectSessionState state = new ExpectSessionState(
                new BoundedTranscriptBuffer(256), new BoundedMatchBuffer(256, workProbe), (thread, error) -> {});
        AtomicInteger evaluations = new AtomicInteger();
        ExpectRegexMatcher matcher = new ExpectRegexMatcher(
                state,
                (pattern, text, searchStart) -> {
                    evaluations.incrementAndGet();
                    return ExpectRegexMatcher.evaluate(pattern, text, searchStart);
                },
                ignored -> {},
                gate);
        state.publishDecoded("stdout", true, "ready");
        boolean gateReleased = false;

        try {
            Future<ExpectMatch> blocked = executor.submit(() -> matcher.match(
                    Pattern.compile("ready"), deadline(Duration.ofMillis(200)), "not found", "expect regex: ready"));
            ExecutionException wrapper = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> blocked.get(1, TimeUnit.SECONDS));
            ExpectException timeout =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(ExpectException.class, wrapper.getCause());

            assertEquals(ExpectException.Reason.TIMEOUT, timeout.reason());
            assertEquals(0, evaluations.get());
            assertEquals(0, workProbe.snapshottedCharacters.get());

            gate.release();
            gateReleased = true;
            ExpectMatch match = matcher.match(
                    Pattern.compile("ready"), deadline(Duration.ofSeconds(1)), "not found", "expect regex: ready");

            assertEquals("ready", match.matched());
            assertEquals(1, evaluations.get());
            assertTrue(workProbe.snapshottedCharacters.get() > 0);
        } finally {
            if (!gateReleased) {
                gate.release();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static final class CountingWorkProbe implements BoundedMatchBuffer.WorkProbe {

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
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.awaitIgnoringInterrupt;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.awaitRetired;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.poolDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledLineSessionWorkerHooksIntegrationTest {

    private static final long EXTERNAL_WATCHDOG_SECONDS = 5;

    @Test
    void resetFailureRetiresWorkerWithoutChangingCompletedRequestOutcome() throws InterruptedException {
        try (PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withReset(worker -> {
                    throw new IllegalStateException("dirty worker");
                })
                .open()) {
            LineResponse response = pool.request("hello");

            assertEquals("response:hello", response.text());
            assertEquals(1, pool.metrics().completedRequests());
            assertEquals(0, pool.metrics().failedRequests());
            assertTrue(awaitRetired(pool, 1));
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().size());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
        }
    }

    @Test
    void resetErrorIsRethrownAfterRecordingCompletedRequestAndResetRetirement() throws InterruptedException {
        AssertionError resetError = new AssertionError("reset invariant failed");
        try (PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withReset(worker -> {
                    throw resetError;
                })
                .open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(resetError, thrown);
            assertEquals(1, pool.metrics().completedRequests());
            assertEquals(0, pool.metrics().failedRequests());
            assertTrue(awaitRetired(pool, 1));
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
        }
    }

    @Test
    void resetHookTimeoutRetiresWorkerWithoutChangingCompletedRequestOutcome() throws Exception {
        NonCooperativeTask reset = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                    .withMaxSize(1)
                    .withHookTimeout(Duration.ofMillis(50))
                    .withReset(worker -> reset.run())
                    .open()) {
                Future<LineResponse> request = executor.submit(() -> pool.request("hello"));
                LineResponse response = request.get(1, TimeUnit.SECONDS);

                assertTrue(reset.awaitEntered());
                assertEquals("response:hello", response.text());
                assertEquals(1, pool.metrics().completedRequests());
                assertEquals(0, pool.metrics().failedRequests());
                assertTrue(awaitRetired(pool, 1));
                assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
                reset.releaseAndJoin();
                assertEquals("response:second", pool.request("second").text());
                assertEquals(2, pool.metrics().created());
            }
        } finally {
            reset.release();
            reset.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void healthHookTimeoutIsBoundedAndRetiresWorker() throws Exception {
        NonCooperativeTask health = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledLineSession pool = poolDraft(
                            fixtureScenario().withRequestTimeout(Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS + 1)),
                            "controlled-line-repl")
                    .withMaxSize(1)
                    .withWarmupSize(1)
                    .withAcquireTimeout(Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS + 1))
                    .withHookTimeout(Duration.ofMillis(50))
                    .withHealthCheck(worker -> {
                        health.run();
                        return true;
                    })
                    .open()) {
                Future<PooledSessionException> request = executor.submit(() -> {
                    try {
                        pool.request("hello");
                        throw new AssertionError("expected health timeout");
                    } catch (PooledSessionException exception) {
                        return exception;
                    }
                });
                PooledSessionException exception = request.get(1, TimeUnit.SECONDS);

                assertTrue(health.awaitEntered());
                assertEquals(PooledSessionException.Reason.HOOK_TIMEOUT, exception.reason());
                assertTrue(PoolTestAccess.awaitLineMetrics(
                        pool,
                        metrics -> metrics.retired() == 1
                                && metrics.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED) == 1,
                        Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS)));
                health.releaseAndJoin();
                Future<LineResponse> replacement = executor.submit(() -> pool.request("second"));
                assertEquals(
                        "response:second",
                        replacement
                                .get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS)
                                .text());
                PooledSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
                assertEquals(2, metrics.created());
            }
        } finally {
            health.release();
            health.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void resetHookRunsBeforeWorkerReturnsToPool() {
        AtomicInteger resetCalls = new AtomicInteger();

        try (PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withReset(worker -> {
                    resetCalls.incrementAndGet();
                    assertEquals("response:reset", worker.request("reset").text());
                })
                .open()) {
            assertEquals("response:first", pool.request("first").text());
            assertEquals("response:second", pool.request("second").text());

            assertEquals(2, resetCalls.get());
            assertEquals(1, pool.metrics().created());
            assertEquals(2, pool.metrics().completedRequests());
        }
    }

    @Test
    void unhealthyIdleWorkerIsRetiredAndReplaced() {
        AtomicInteger checks = new AtomicInteger();

        try (PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withHealthCheck(worker -> {
                    int attempt = checks.incrementAndGet();
                    return attempt > 1
                            && "response:healthy"
                                    .equals(worker.request("health").text());
                })
                .open()) {
            assertEquals("response:hello", pool.request("hello").text());

            assertEquals(2, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
        }
    }

    private static final class NonCooperativeTask {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread thread;

        private void run() {
            thread = Thread.currentThread();
            entered.countDown();
            awaitIgnoringInterrupt(release);
        }

        private boolean awaitEntered() {
            try {
                return entered.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void release() {
            release.countDown();
        }

        private void releaseAndJoin() throws InterruptedException {
            release();
            join();
        }

        private void join() throws InterruptedException {
            Thread callback = thread;
            if (callback != null) {
                callback.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(callback.isAlive(), "lifecycle callback did not finish");
            }
        }
    }
}

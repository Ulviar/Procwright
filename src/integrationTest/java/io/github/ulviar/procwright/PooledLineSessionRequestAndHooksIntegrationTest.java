/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledLineSessionRequestAndHooksIntegrationTest extends PooledLineSessionRequestAndHooksIntegrationSupport {

    @Test
    void callerValidationHappensBeforeWorkerAcquire() {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            assertThrows(IllegalArgumentException.class, () -> pool.request("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> pool.request("a", Duration.ZERO));
            assertThrows(NullPointerException.class, () -> pool.request(null));
            assertThrows(NullPointerException.class, () -> pool.request("a", null));

            PooledLineSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.size());
            assertEquals(1, metrics.idle());
            assertEquals(1, metrics.created());
            assertEquals(0, metrics.retired());
            assertEquals(0, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
        }
    }

    @Test
    void requestSizeValidationHappensBeforeWorkerAcquire() {
        LineSessionScenario.Draft scenario = fixtureScenario().withMaxRequestChars(4);

        try (PooledLineSession pool =
                scenario.withArgs("controlled-line-repl").pooled().open()) {
            LineSessionException exception = assertThrows(LineSessionException.class, () -> pool.request("hello"));

            assertEquals(LineSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
            assertEquals(0, pool.metrics().created());
            assertEquals(1, pool.metrics().failedRequests());
        }
    }

    @Test
    void validatedPooledRequestIsEncodedOnlyOnce() {
        CountingUtf8Charset charset = new CountingUtf8Charset();
        LineSessionScenario.Draft scenario = fixtureScenario().withCharset(charset);

        try (PooledLineSession pool =
                scenario.withArgs("controlled-line-repl").pooled().open()) {
            assertThrows(IllegalArgumentException.class, () -> pool.request("hello", Duration.ZERO));
            assertEquals(0, charset.encoderCreations());
            assertEquals("response:hello", pool.request("hello").text());
        }

        assertEquals(2, charset.encoderCreations());
    }

    @Test
    void pooledRequestEncodingIsBoundedBeforeWorkerAcquire() throws Exception {
        BlockingUtf8Charset charset = new BlockingUtf8Charset();
        LineSessionScenario.Draft scenario = fixtureScenario().withCharset(charset);

        try (PooledLineSession pool = scenario.withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1)
                .open()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request =
                        executor.submit(() -> captureFailure(() -> pool.request("first", Duration.ofMillis(50))));
                assertTrue(charset.awaitEncoderStarted());

                Throwable failure = request.get(500, TimeUnit.MILLISECONDS);

                assertTrue(failure instanceof LineSessionException);
                assertEquals(LineSessionException.Reason.TIMEOUT, ((LineSessionException) failure).reason());
                assertEquals(0, pool.metrics().created());
                assertEquals(1, pool.metrics().failedRequests());
            } finally {
                charset.releaseEncoder();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }

            assertEquals(
                    "response:second",
                    pool.request("second", Duration.ofSeconds(1)).text());
            assertEquals(1, pool.metrics().created());
        }
    }

    @Test
    void acquireTimeoutIsDistinctWhenAllWorkersAreBusy() throws Exception {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withAcquireTimeout(Duration.ofMillis(100))
                .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch firstStarted = new CountDownLatch(1);
            try {
                Future<LineResponse> first = executor.submit(() -> {
                    firstStarted.countDown();
                    return pool.request("hold", Duration.ofSeconds(2));
                });
                assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
                assertTrue(awaitLeased(pool, 1));

                PooledLineSessionException exception =
                        assertThrows(PooledLineSessionException.class, () -> pool.request("hello"));

                PooledLineSessionMetrics waiting = pool.metrics();
                assertEquals(PooledLineSessionException.Reason.ACQUIRE_TIMEOUT, exception.reason());
                assertTrue(waiting.totalAcquireWaitNanos() > 0);
                assertTrue(waiting.totalRequestDurationNanos() > 0);
                assertEquals("response:hold", first.get().text());
                assertEquals(1, pool.metrics().completedRequests());
                assertEquals(1, pool.metrics().failedRequests());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void resetFailureRetiresWorkerWithoutChangingCompletedRequestOutcome() {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
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
    void resetErrorIsRethrownAfterRecordingCompletedRequestAndResetRetirement() {
        AssertionError resetError = new AssertionError("reset invariant failed");
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
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
    void requestErrorIsRethrownAndRecordedAsFailedRequest() {
        AssertionError decoderError = new AssertionError("decoder invariant failed");
        LineSessionScenario.Draft scenario = fixtureScenario().withResponseDecoder(reader -> {
            reader.readLine();
            throw decoderError;
        });
        try (PooledLineSession pool =
                pool(scenario, "controlled-line-repl").withMaxSize(1).open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(decoderError, thrown);
            assertEquals(0, pool.metrics().completedRequests());
            assertEquals(1, pool.metrics().failedRequests());
            assertTrue(awaitRetired(pool, 1));
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
        }
    }

    @Test
    void resetHookTimeoutRetiresWorkerWithoutChangingCompletedRequestOutcome() throws Exception {
        NonCooperativeTask reset = new NonCooperativeTask();
        int permitsBefore = PoolTestAccess.availableWorkerHookPermits();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
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
                assertEquals(permitsBefore, PoolTestAccess.availableWorkerHookPermits());
                assertEquals("response:second", pool.request("second").text());
                assertEquals(2, pool.metrics().created());
            }
        } finally {
            reset.release();
            reset.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, PoolTestAccess.availableWorkerHookPermits());
        }
    }

    @Test
    void healthHookTimeoutIsBoundedAndRetiresWorker() throws Exception {
        NonCooperativeTask health = new NonCooperativeTask();
        int permitsBefore = PoolTestAccess.availableWorkerHookPermits();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledLineSession pool = pool(
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
                Future<PooledLineSessionException> request = executor.submit(() -> {
                    try {
                        pool.request("hello");
                        throw new AssertionError("expected health timeout");
                    } catch (PooledLineSessionException exception) {
                        return exception;
                    }
                });
                PooledLineSessionException exception = request.get(1, TimeUnit.SECONDS);

                assertTrue(health.awaitEntered());
                assertEquals(PooledLineSessionException.Reason.HOOK_TIMEOUT, exception.reason());
                assertTrue(PoolTestAccess.awaitLineMetrics(
                        pool,
                        metrics -> metrics.retired() == 1
                                && metrics.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED) == 1,
                        Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS)));
                health.releaseAndJoin();
                assertEquals(permitsBefore, PoolTestAccess.availableWorkerHookPermits());
                Future<LineResponse> replacement = executor.submit(() -> pool.request("second"));
                assertEquals(
                        "response:second",
                        replacement
                                .get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS)
                                .text());
                PooledLineSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
                assertEquals(2, metrics.created());
            }
        } finally {
            health.release();
            health.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(permitsBefore, PoolTestAccess.availableWorkerHookPermits());
        }
    }

    @Test
    void resetHookRunsBeforeWorkerReturnsToPool() {
        AtomicInteger resetCalls = new AtomicInteger();

        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
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

        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
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
}

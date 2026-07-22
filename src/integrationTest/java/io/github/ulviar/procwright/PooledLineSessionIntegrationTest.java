/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PooledLineSessionIntegrationTest {

    // This only bounds the test harness; scenario timeouts remain the values asserted by each test.
    private static final long EXTERNAL_WATCHDOG_SECONDS = 5;

    @Test
    void warmupLaunchFailureIsPooledStartupFailure() {
        CommandService missingExecutable = Procwright.command("procwright-missing-executable-for-startup-test");

        PooledLineSessionException exception = assertThrows(
                PooledLineSessionException.class,
                () -> missingExecutable.lineSession().pooled().withWarmupSize(1).open());

        assertEquals(PooledLineSessionException.Reason.STARTUP_FAILED, exception.reason());
    }

    @Test
    void warmPoolReusesLineSessionWorkers() {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            String firstPid = pool.request("pid").text();
            String secondPid = pool.request("pid").text();

            assertEquals(firstPid, secondPid);
            PooledLineSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.size());
            assertEquals(1, metrics.idle());
            assertEquals(0, metrics.leased());
            assertEquals(1, metrics.created());
            assertEquals(2, metrics.completedRequests());
        }
    }

    @Test
    void maxRequestsPerWorkerRetiresWorkersAfterUseLimit() {
        AtomicInteger resetCalls = new AtomicInteger();
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withMaxRequestsPerWorker(1)
                .withReset(worker -> resetCalls.incrementAndGet())
                .open()) {
            String firstPid = pool.request("pid").text();

            PooledLineSessionMetrics metrics = pool.metrics();
            assertTrue(firstPid.startsWith("response:pid:"));
            assertEquals(1, metrics.created());
            assertTrue(awaitRetired(pool, 1));
            metrics = pool.metrics();
            assertEquals(1, metrics.retired());
            assertEquals(0, metrics.size());
            assertEquals(1, metrics.completedRequests());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
            assertEquals(0, resetCalls.get());
        }
    }

    @Test
    void maxWorkerAgeRetiresWorkerAfterUse() {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withMaxWorkerAge(Duration.ofNanos(1))
                .open()) {
            String firstPid = pool.request("pid").text();

            PooledLineSessionMetrics metrics = pool.metrics();
            assertTrue(firstPid.startsWith("response:pid:"));
            assertEquals(1, metrics.created());
            assertTrue(awaitRetired(pool, 1));
            metrics = pool.metrics();
            assertEquals(1, metrics.retired());
            assertEquals(0, metrics.size());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.AGE));
        }
    }

    @Test
    void requestTimeoutRetiresWorkerBeforeNextRequest() {
        try (PooledLineSession pool =
                pool(fixtureScenario(), "controlled-line-repl").withMaxSize(1).open()) {
            String firstPid = pool.request("pid").text();

            LineSessionException timeout = assertThrows(
                    LineSessionException.class, () -> pool.request("slow-response", Duration.ofMillis(100)));
            String nextPid = pool.request("pid").text();

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertNotEquals(firstPid, nextPid);
            assertEquals(2, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
            assertEquals(2, pool.metrics().completedRequests());
            assertEquals(1, pool.metrics().failedRequests());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.TIMEOUT));
        }
    }

    @Test
    void requestFailureRetiresWorkerBeforeNextRequest() {
        try (PooledLineSession pool =
                pool(fixtureScenario(), "exit-after-read").withMaxSize(1).open()) {
            LineSessionException eof =
                    assertThrows(LineSessionException.class, () -> pool.request("hello", Duration.ofSeconds(1)));
            LineSessionException nextFailure =
                    assertThrows(LineSessionException.class, () -> pool.request("again", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.EOF, eof.reason());
            assertEquals(LineSessionException.Reason.EOF, nextFailure.reason());
            assertEquals(2, pool.metrics().created());
            assertTrue(awaitRetired(pool, 2));
            assertEquals(2, pool.metrics().retired());
            assertEquals(0, pool.metrics().completedRequests());
            assertEquals(2, pool.metrics().failedRequests());
            assertEquals(2, pool.metrics().retireReasons().get(PooledWorkerRetireReason.PROCESS_EXITED));
        }
    }

    @Test
    void retiredWorkerCleansDescendantBeforeReportingCloseCompletion() throws Exception {
        AtomicLong observedChildPid = new AtomicLong();
        AtomicBoolean firstResponse = new AtomicBoolean(true);
        long childPid = -1;
        try (PooledLineSession pool = fixtureScenario()
                .withResponseDecoder(reader -> {
                    String child = reader.readLine();
                    if (firstResponse.compareAndSet(true, false)) {
                        observedChildPid.set(Long.parseLong(child.substring("child:".length())));
                    }
                    return List.of(child);
                })
                .withReadiness(ready -> ready.request("observe-child"))
                .withReadinessTimeout(Duration.ofSeconds(5))
                .withArgs("spawn-child", "--child-scenario=never-exit", "--wait=true")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> pool.request("request", Duration.ofMillis(200)));
            childPid = observedChildPid.get();

            assertEquals(LineSessionException.Reason.TIMEOUT, failure.reason());
            assertTrue(childPid > 0, "worker output did not publish the child process id");
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            assertFalse(
                    ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                    "retired pooled worker left an observed descendant alive");
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.TIMEOUT));
        } finally {
            if (childPid >= 0) {
                ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void exitedProcessUsesProcessExitedRetirementReason() {
        try (PooledLineSession pool = pool(fixtureScenario(), "exit-after-read", "--stdout=ok")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withReset(worker -> worker.onExit().join())
                .open()) {
            assertEquals("ok", pool.request("first").text());
            assertEquals("ok", pool.request("second").text());

            assertTrue(awaitRetireReason(pool, PooledWorkerRetireReason.PROCESS_EXITED, 1));
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.PROCESS_EXITED));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.HEALTH_FAILED));
        }
    }

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

    @Test
    void minIdleReplenishesRetiredLineWorkersInBackground() throws Exception {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withMinIdle(1)
                .withMaxRequestsPerWorker(1)
                .open()) {
            assertEquals("response:hello", pool.request("hello").text());

            assertTrue(awaitIdle(pool, 1));
            PooledLineSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.size());
            assertEquals(1, metrics.idle());
            assertEquals(2, metrics.created());
            assertEquals(1, metrics.retired());
        }
    }

    @Test
    void closeDrainsLeasedWorkersAndRejectsNewRequests() throws Exception {
        PooledLineSession pool =
                pool(fixtureScenario(), "controlled-line-repl").withMaxSize(1).open();
        try {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<LineResponse> inFlight = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
                assertTrue(awaitLeased(pool, 1));

                pool.close();

                PooledLineSessionException closed =
                        assertThrows(PooledLineSessionException.class, () -> pool.request("hello"));
                assertEquals(PooledLineSessionException.Reason.CLOSED, closed.reason());
                assertEquals("response:hold", inFlight.get().text());
                assertEquals(0, pool.metrics().size());
                assertEquals(1, pool.metrics().completedRequests());
                assertEquals(1, pool.metrics().failedRequests());
                assertTrue(pool.closeAsync().isDone());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        } finally {
            pool.close();
        }
    }

    @Test
    void closePreservesLeasedMetricsWhileRetiringIdleWorkers() throws Exception {
        PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(2)
                .withWarmupSize(2)
                .open();
        try {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<LineResponse> inFlight = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
                assertTrue(awaitLeased(pool, 1));

                CompletableFuture<Void> close = pool.closeAsync();
                PooledLineSessionMetrics closing = pool.metrics();

                assertEquals(2, closing.size());
                assertEquals(0, closing.idle());
                assertEquals(1, closing.leased());
                assertEquals(1, closing.retiring());
                assertEquals("response:hold", inFlight.get().text());
                close.get(2, TimeUnit.SECONDS);
                assertEquals(0, pool.metrics().size());
                assertEquals(0, pool.metrics().leased());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        } finally {
            pool.close();
        }
    }

    @Test
    void poolDraftSettingsAreAppliedAtOpen() {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(2)
                .withWarmupSize(2)
                .open()) {
            PooledLineSessionMetrics metrics = pool.metrics();

            assertEquals(2, metrics.size());
            assertEquals(2, metrics.idle());
            assertEquals(0, metrics.leased());
            assertEquals(2, metrics.created());
        }
    }

    @Test
    void repeatedCloseOfAlreadyDrainedPoolSucceeds() throws Exception {
        PooledLineSession pool =
                pool(fixtureScenario(), "controlled-line-repl").withMaxSize(1).open();

        pool.close();
        pool.close();

        assertTrue(pool.closeAsync().isDone());
        pool.closeAsync().get(1, TimeUnit.SECONDS);
    }

    @Test
    void closeTimeoutKeepsCleanupObservableAndAsyncViewsCancellationIsolated() throws Exception {
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofMillis(40))
                .withReset(worker -> {
                    resetEntered.countDown();
                    awaitIgnoringInterrupt(releaseReset);
                })
                .open();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LineResponse> request = executor.submit(() -> pool.request("hello"));
            assertTrue(resetEntered.await(1, TimeUnit.SECONDS));

            Future<PooledLineSessionException> closeAttempt =
                    executor.submit(() -> assertThrows(PooledLineSessionException.class, pool::close));
            PooledLineSessionException timeout = closeAttempt.get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS);
            assertEquals(PooledLineSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
            CompletableFuture<Void> cancelled = pool.closeAsync();
            CompletableFuture<Void> eventual = pool.closeAsync();
            assertTrue(cancelled.cancel(true));
            assertFalse(eventual.isCancelled());

            releaseReset.countDown();

            assertEquals(
                    "response:hello",
                    request.get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS).text());
            eventual.get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS);
            pool.close();
            assertEquals(0, pool.metrics().size());
        } finally {
            releaseReset.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void interruptedCloseRestoresFlagAndCleanupStillCompletes() throws Exception {
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofSeconds(1))
                .withReset(worker -> {
                    resetEntered.countDown();
                    awaitIgnoringInterrupt(releaseReset);
                })
                .open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<LineResponse> request = executor.submit(() -> pool.request("hello"));
            assertTrue(resetEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> eventual = pool.closeAsync();

            PooledLineSessionException interrupted;
            try {
                Thread.currentThread().interrupt();
                interrupted = assertThrows(PooledLineSessionException.class, pool::close);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(PooledLineSessionException.Reason.INTERRUPTED, interrupted.reason());

            releaseReset.countDown();
            assertEquals("response:hello", request.get(1, TimeUnit.SECONDS).text());
            eventual.get(1, TimeUnit.SECONDS);
        } finally {
            releaseReset.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void concurrentCloseAndCloseAsyncShareOneTerminalCleanup() throws Exception {
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        CountDownLatch terminalCallsStarted = new CountDownLatch(3);
        PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS + 1))
                .withReset(worker -> {
                    resetEntered.countDown();
                    awaitIgnoringInterrupt(releaseReset);
                })
                .open();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Future<LineResponse> request = executor.submit(() -> pool.request("hello"));
            assertTrue(resetEntered.await(1, TimeUnit.SECONDS));
            List<Future<?>> closers = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                Runnable closePool = () -> {
                    CompletableFuture<Void> view = pool.closeAsync();
                    terminalCallsStarted.countDown();
                    pool.close();
                    view.join();
                };
                closers.add(executor.submit(closePool));
            }
            assertTrue(terminalCallsStarted.await(1, TimeUnit.SECONDS));

            releaseReset.countDown();

            assertEquals(
                    "response:hello",
                    request.get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS).text());
            for (Future<?> closer : closers) {
                closer.get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS);
            }
            PooledLineSessionMetrics metrics = pool.metrics();
            assertEquals(0, metrics.size());
            assertEquals(1, metrics.retired());
            assertEquals(0, metrics.failedWorkerCloses());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.CLOSED));
        } finally {
            releaseReset.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void closeFromResetCallbackIsBoundedAndEventuallyDrains() throws Exception {
        AtomicReference<PooledLineSession> poolReference = new AtomicReference<>();
        AtomicReference<PooledLineSessionException> closeFailure = new AtomicReference<>();
        PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofMillis(40))
                .withReset(worker -> closeFailure.set(assertThrows(
                        PooledLineSessionException.class,
                        () -> poolReference.get().close())))
                .open();
        poolReference.set(pool);
        try {
            assertEquals("response:hello", pool.request("hello").text());
            assertEquals(
                    PooledLineSessionException.Reason.DRAIN_TIMEOUT,
                    closeFailure.get().reason());

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            pool.close();
        } finally {
            pool.close();
        }
    }

    @Test
    void tryWithResourcesSuppressesBoundedCloseFailureOnPrimaryFailure() throws Exception {
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<PooledLineSession> opened = new AtomicReference<>();
        AtomicReference<Future<LineResponse>> request = new AtomicReference<>();
        IllegalStateException primary = new IllegalStateException("body failed");
        try {
            IllegalStateException observed = assertThrows(IllegalStateException.class, () -> {
                try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                        .withWarmupSize(1)
                        .withCloseTimeout(Duration.ofMillis(40))
                        .withReset(worker -> {
                            resetEntered.countDown();
                            awaitIgnoringInterrupt(releaseReset);
                        })
                        .open()) {
                    opened.set(pool);
                    request.set(executor.submit(() -> pool.request("hello")));
                    assertTrue(resetEntered.await(1, TimeUnit.SECONDS));
                    throw primary;
                }
            });

            assertSame(primary, observed);
            assertEquals(1, observed.getSuppressed().length);
            assertEquals(
                    PooledLineSessionException.Reason.DRAIN_TIMEOUT,
                    ((PooledLineSessionException) observed.getSuppressed()[0]).reason());
        } finally {
            releaseReset.countDown();
            Future<LineResponse> activeRequest = request.get();
            if (activeRequest != null) {
                activeRequest.get(1, TimeUnit.SECONDS);
            }
            PooledLineSession pool = opened.get();
            if (pool != null) {
                pool.closeAsync().get(1, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static LineSessionScenario.Draft fixtureScenario() {
        return Procwright.command(fixtureCommand()).lineSession();
    }

    private static CommandSpec fixtureCommand() {
        return TestCliSupport.command();
    }

    private static LineSessionScenario.PoolDraft pool(LineSessionScenario.Draft scenario, String... workerArguments) {
        return scenario.withArgs(workerArguments).pooled();
    }

    private static boolean awaitLeased(PooledLineSession pool, int expectedLeased) throws InterruptedException {
        return PoolTestAccess.awaitLineMetrics(
                pool, metrics -> metrics.leased() == expectedLeased, Duration.ofSeconds(2));
    }

    private static boolean awaitIdle(PooledLineSession pool, int expectedIdle) throws InterruptedException {
        return PoolTestAccess.awaitLineMetrics(pool, metrics -> metrics.idle() == expectedIdle, Duration.ofSeconds(2));
    }

    private static boolean awaitRetired(PooledLineSession pool, long expectedRetired) {
        try {
            return PoolTestAccess.awaitLineMetrics(
                    pool, metrics -> metrics.retired() == expectedRetired, Duration.ofSeconds(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean awaitRetireReason(
            PooledLineSession pool, PooledWorkerRetireReason reason, long expectedCount) {
        try {
            return PoolTestAccess.awaitLineMetrics(
                    pool,
                    metrics -> metrics.retireReasons().getOrDefault(reason, 0L) == expectedCount,
                    Duration.ofSeconds(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
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
                assertFalse(callback.isAlive(), "lifecycle callback retained its bounded-runner permit");
            }
        }
    }

    private static final class CountingUtf8Charset extends Charset {

        private final AtomicInteger encoderCreations = new AtomicInteger();

        private CountingUtf8Charset() {
            super("X-Procwright-Counting-UTF-8", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations.incrementAndGet();
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int encoderCreations() {
            return encoderCreations.get();
        }
    }

    private static final class BlockingUtf8Charset extends Charset {

        private final AtomicBoolean blockNextEncoder = new AtomicBoolean(true);
        private final CountDownLatch encoderStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEncoder = new CountDownLatch(1);

        private BlockingUtf8Charset() {
            super("X-Procwright-Pooled-Line-Blocking-UTF-8", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            if (blockNextEncoder.compareAndSet(true, false)) {
                encoderStarted.countDown();
                awaitIgnoringInterrupt(releaseEncoder);
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitEncoderStarted() throws InterruptedException {
            return encoderStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseEncoder() {
            releaseEncoder.countDown();
        }
    }
}

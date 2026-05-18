package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ulviar.icli.command.CommandSpec;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.session.LineResponse;
import com.github.ulviar.icli.session.LineSessionException;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledLineSessionException;
import com.github.ulviar.icli.session.PooledLineSessionMetrics;
import com.github.ulviar.icli.session.SessionOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledLineSessionIntegrationTest {

    @Test
    void warmPoolReusesLineSessionWorkers() {
        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(1).warmupSize(1))) {
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
        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(1).maxRequestsPerWorker(1))) {
            String firstPid = pool.request("pid").text();

            PooledLineSessionMetrics metrics = pool.metrics();
            assertTrue(firstPid.startsWith("response:pid:"));
            assertEquals(1, metrics.created());
            assertEquals(1, metrics.retired());
            assertEquals(0, metrics.size());
            assertEquals(1, metrics.completedRequests());
        }
    }

    @Test
    void maxWorkerAgeRetiresWorkerAfterUse() {
        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(1).maxWorkerAge(Duration.ofNanos(1)))) {
            String firstPid = pool.request("pid").text();

            PooledLineSessionMetrics metrics = pool.metrics();
            assertTrue(firstPid.startsWith("response:pid:"));
            assertEquals(1, metrics.created());
            assertEquals(1, metrics.retired());
            assertEquals(0, metrics.size());
        }
    }

    @Test
    void requestTimeoutRetiresWorkerBeforeNextRequest() {
        try (PooledLineSession pool =
                fixtureService().pooled(call -> call.args("line-repl").maxSize(1))) {
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
        }
    }

    @Test
    void requestFailureRetiresWorkerBeforeNextRequest() {
        try (PooledLineSession pool =
                fixtureService().pooled(call -> call.args("exit-after-read").maxSize(1))) {
            LineSessionException eof =
                    assertThrows(LineSessionException.class, () -> pool.request("hello", Duration.ofSeconds(1)));
            LineSessionException nextFailure =
                    assertThrows(LineSessionException.class, () -> pool.request("again", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.EOF, eof.reason());
            assertEquals(LineSessionException.Reason.EOF, nextFailure.reason());
            assertEquals(2, pool.metrics().created());
            assertEquals(2, pool.metrics().retired());
            assertEquals(0, pool.metrics().completedRequests());
            assertEquals(2, pool.metrics().failedRequests());
        }
    }

    @Test
    void callerValidationHappensBeforeWorkerAcquire() {
        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(1).warmupSize(1))) {
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
    void acquireTimeoutIsDistinctWhenAllWorkersAreBusy() throws Exception {
        try (PooledLineSession pool = fixtureService()
                        .pooled(call -> call.args("line-repl").maxSize(1).acquireTimeout(Duration.ofMillis(100)));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            Future<LineResponse> first = executor.submit(() -> {
                firstStarted.countDown();
                return pool.request("hold", Duration.ofSeconds(2));
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(awaitLeased(pool, 1));

            PooledLineSessionException exception =
                    assertThrows(PooledLineSessionException.class, () -> pool.request("hello"));

            assertEquals(PooledLineSessionException.Reason.ACQUIRE_TIMEOUT, exception.reason());
            assertEquals("response:hold", first.get().text());
            assertEquals(1, pool.metrics().completedRequests());
            assertEquals(1, pool.metrics().failedRequests());
        }
    }

    @Test
    void resetFailureCountsAsFailedRequestAndRetiresWorker() {
        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(1).reset(worker -> {
                    throw new IllegalStateException("dirty worker");
                }))) {
            PooledLineSessionException exception =
                    assertThrows(PooledLineSessionException.class, () -> pool.request("hello"));

            assertEquals(PooledLineSessionException.Reason.WORKER_FAILED, exception.reason());
            assertEquals(0, pool.metrics().completedRequests());
            assertEquals(1, pool.metrics().failedRequests());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().size());
        }
    }

    @Test
    void resetHookRunsBeforeWorkerReturnsToPool() {
        AtomicInteger resetCalls = new AtomicInteger();

        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(1).reset(worker -> {
                    resetCalls.incrementAndGet();
                    assertEquals("response:reset", worker.request("reset").text());
                }))) {
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

        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(1).warmupSize(1).healthCheck(worker -> {
                    int attempt = checks.incrementAndGet();
                    return attempt > 1
                            && "response:healthy"
                                    .equals(worker.request("health").text());
                }))) {
            assertEquals("response:hello", pool.request("hello").text());

            assertEquals(2, pool.metrics().created());
            assertEquals(1, pool.metrics().retired());
        }
    }

    @Test
    void closeDrainsLeasedWorkersAndRejectsNewRequests() throws Exception {
        try (PooledLineSession pool =
                        fixtureService().pooled(call -> call.args("line-repl").maxSize(1));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LineResponse> inFlight = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
            assertTrue(awaitLeased(pool, 1));

            pool.close();

            PooledLineSessionException closed =
                    assertThrows(PooledLineSessionException.class, () -> pool.request("hello"));
            assertEquals(PooledLineSessionException.Reason.CLOSED, closed.reason());
            assertEquals("response:hold", inFlight.get().text());
            assertTrue(pool.awaitDrained(Duration.ofSeconds(2)));
            assertEquals(0, pool.metrics().size());
            assertEquals(1, pool.metrics().completedRequests());
            assertEquals(1, pool.metrics().failedRequests());
            assertFalse(pool.onDrained().isCompletedExceptionally());
        }
    }

    @Test
    void closePreservesLeasedMetricsWhileRetiringIdleWorkers() throws Exception {
        try (PooledLineSession pool = fixtureService()
                        .pooled(call -> call.args("line-repl").maxSize(2).warmupSize(2));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LineResponse> inFlight = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
            assertTrue(awaitLeased(pool, 1));

            pool.close();
            PooledLineSessionMetrics closing = pool.metrics();

            assertEquals(1, closing.size());
            assertEquals(0, closing.idle());
            assertEquals(1, closing.leased());
            assertEquals(1, closing.retired());
            assertEquals("response:hold", inFlight.get().text());
            assertTrue(pool.awaitDrained(Duration.ofSeconds(2)));
            assertEquals(0, pool.metrics().size());
            assertEquals(0, pool.metrics().leased());
        }
    }

    @Test
    void awaitDrainedSaturatesHugeTimeout() {
        try (PooledLineSession pool =
                fixtureService().pooled(call -> call.args("line-repl").maxSize(1))) {
            pool.close();

            assertTrue(pool.awaitDrained(Duration.ofSeconds(Long.MAX_VALUE)));
        }
    }

    private static CommandService fixtureService() {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), ProcessFixtureProgram.class.getName())
                .build();
        return new CommandService(
                command, RunOptions.defaults(), SessionOptions.defaults(), LineSessionOptions.defaults());
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean awaitLeased(PooledLineSession pool, int expectedLeased) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.metrics().leased() == expectedLeased) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}

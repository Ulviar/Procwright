/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class PooledLineSessionWorkerLifecycleIntegrationTest extends PooledLineSessionWorkerLifecycleIntegrationSupport {

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
}

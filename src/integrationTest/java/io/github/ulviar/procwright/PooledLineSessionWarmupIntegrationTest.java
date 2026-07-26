/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class PooledLineSessionWarmupIntegrationTest extends PooledLineSessionIntegrationSupport {

    @Test
    void warmupLaunchFailureIsPooledStartupFailure() {
        CommandService missingExecutable = Procwright.command("procwright-missing-executable-for-startup-test");

        PooledSessionException exception = assertThrows(
                PooledSessionException.class,
                () -> missingExecutable.lineSession().pooled().withWarmupSize(1).open());

        assertEquals(PooledSessionException.Reason.STARTUP_FAILED, exception.reason());
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
            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.size());
            assertEquals(1, metrics.idle());
            assertEquals(0, metrics.leased());
            assertEquals(1, metrics.created());
            assertEquals(2, metrics.completedRequests());
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
            PooledSessionMetrics metrics = pool.metrics();
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
            PooledSessionMetrics metrics = pool.metrics();

            assertEquals(2, metrics.size());
            assertEquals(2, metrics.idle());
            assertEquals(0, metrics.leased());
            assertEquals(2, metrics.created());
        }
    }

    private static boolean awaitIdle(PooledLineSession pool, int expectedIdle) throws InterruptedException {
        return PoolTestAccess.awaitLineMetrics(pool, metrics -> metrics.idle() == expectedIdle, Duration.ofSeconds(2));
    }
}

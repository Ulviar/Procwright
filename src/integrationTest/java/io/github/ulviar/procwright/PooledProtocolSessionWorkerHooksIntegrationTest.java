/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.poolDraft;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PooledProtocolSessionWorkerHooksIntegrationTest {

    @Test
    void protocolPoolHealthHookTimeoutIsBounded() throws Exception {
        NonCooperativeTask health = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledProtocolSession<String, String> pool =
                    poolDraft(fixtureService(), FramedStringAdapter::new, "length-line-frame")
                            .withMaxSize(1)
                            .withWarmupSize(1)
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
                health.releaseAndJoin();
                assertEquals("second", pool.request("second"));
                PooledSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
                assertFalse(metrics.retireReasons().containsKey(PooledWorkerRetireReason.PROCESS_EXITED));
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
    void protocolPoolResetHookTimeoutRetiresWorkerWithoutChangingCompletedRequestOutcome() throws Exception {
        NonCooperativeTask reset = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledProtocolSession<String, String> pool =
                    poolDraft(fixtureService(), FramedStringAdapter::new, "length-line-frame")
                            .withMaxSize(1)
                            .withHookTimeout(Duration.ofMillis(50))
                            .withReset(worker -> reset.run())
                            .open()) {
                Future<String> request = executor.submit(() -> pool.request("hello"));
                String response = request.get(1, TimeUnit.SECONDS);

                assertTrue(reset.awaitEntered());
                assertEquals("hello", response);
                assertEquals(1, pool.metrics().completedRequests());
                assertEquals(0, pool.metrics().failedRequests());
                reset.releaseAndJoin();
                assertEquals("second", pool.request("second"));
                PooledSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
                assertEquals(2, metrics.created());
            }
        } finally {
            reset.release();
            reset.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void protocolPoolResetErrorIsRethrownAfterRecordingCompletedRequestAndResetRetirement() throws Exception {
        AssertionError resetError = new AssertionError("reset invariant failed");
        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), FramedStringAdapter::new, "length-line-frame")
                        .withMaxSize(1)
                        .withReset(worker -> {
                            throw resetError;
                        })
                        .open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(resetError, thrown);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
        }
    }

    private static final class NonCooperativeTask {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread thread;

        private void run() {
            thread = Thread.currentThread();
            entered.countDown();
            awaitIgnoringInterrupts(release);
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

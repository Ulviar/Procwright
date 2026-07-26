/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.awaitIgnoringInterrupt;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.poolDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PooledLineSessionCloseCoordinationIntegrationTest {

    private static final long EXTERNAL_WATCHDOG_SECONDS = 5;

    @Test
    void closeTimeoutKeepsCleanupObservableAndAsyncViewsCancellationIsolated() throws Exception {
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
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

            Future<PooledSessionException> closeAttempt =
                    executor.submit(() -> assertThrows(PooledSessionException.class, pool::close));
            PooledSessionException timeout = closeAttempt.get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS);
            assertEquals(PooledSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
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
        PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
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

            PooledSessionException interrupted;
            try {
                Thread.currentThread().interrupt();
                interrupted = assertThrows(PooledSessionException.class, pool::close);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(PooledSessionException.Reason.INTERRUPTED, interrupted.reason());

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
        int concurrentCloserCount = 3;
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        CountDownLatch terminalCallsStarted = new CountDownLatch(concurrentCloserCount);
        PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS + 1))
                .withReset(worker -> {
                    resetEntered.countDown();
                    awaitIgnoringInterrupt(releaseReset);
                })
                .open();
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCloserCount + 1);
        try {
            Future<LineResponse> request = executor.submit(() -> pool.request("hello"));
            assertTrue(resetEntered.await(1, TimeUnit.SECONDS));
            List<Future<?>> closers = new ArrayList<>();
            for (int index = 0; index < concurrentCloserCount; index++) {
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
            PooledSessionMetrics metrics = pool.metrics();
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
        AtomicReference<PooledSessionException> closeFailure = new AtomicReference<>();
        PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofMillis(40))
                .withReset(worker -> closeFailure.set(assertThrows(
                        PooledSessionException.class, () -> poolReference.get().close())))
                .open();
        poolReference.set(pool);
        try {
            assertEquals("response:hello", pool.request("hello").text());
            assertEquals(
                    PooledSessionException.Reason.DRAIN_TIMEOUT,
                    closeFailure.get().reason());

            pool.closeAsync().get(1, TimeUnit.SECONDS);
            pool.close();
        } finally {
            pool.close();
        }
    }
}

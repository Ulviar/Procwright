/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
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

final class PooledLineSessionCloseAndDrainIntegrationTest extends PooledLineSessionIntegrationSupport {

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
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.awaitIgnoringInterrupt;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.awaitLeased;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.PooledLineSessionIntegrationFixtures.poolDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PooledLineSessionCloseIntegrationTest {

    @Test
    void closeDrainsLeasedWorkersAndRejectsNewRequests() throws Exception {
        PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .open();
        try {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<LineResponse> inFlight = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
                assertTrue(awaitLeased(pool, 1));

                pool.close();

                PooledSessionException closed = assertThrows(PooledSessionException.class, () -> pool.request("hello"));
                assertEquals(PooledSessionException.Reason.CLOSED, closed.reason());
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
        PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(2)
                .withWarmupSize(2)
                .open();
        try {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<LineResponse> inFlight = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
                assertTrue(awaitLeased(pool, 1));

                CompletableFuture<Void> close = pool.closeAsync();
                PooledSessionMetrics closing = pool.metrics();

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
        PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .open();

        pool.close();
        pool.close();

        assertTrue(pool.closeAsync().isDone());
        pool.closeAsync().get(1, TimeUnit.SECONDS);
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
                try (PooledLineSession pool = poolDraft(fixtureScenario(), "controlled-line-repl")
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
                    PooledSessionException.Reason.DRAIN_TIMEOUT,
                    ((PooledSessionException) observed.getSuppressed()[0]).reason());
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

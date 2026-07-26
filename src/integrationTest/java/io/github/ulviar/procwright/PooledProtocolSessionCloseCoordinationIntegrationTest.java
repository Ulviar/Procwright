/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.poolDraft;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.CoordinatedResponseAdapter;
import io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PooledProtocolSessionCloseCoordinationIntegrationTest {

    @Test
    void pooledProtocolCloseDistinguishesInterruptionFromDrainTimeout() throws Exception {
        CoordinatedResponseAdapter adapter = new CoordinatedResponseAdapter();
        PooledProtocolSession<String, String> pool = poolDraft(
                        fixtureService(), () -> adapter, "ignore-stdin", "--millis=5000")
                .withMaxSize(1)
                .open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> request = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
            assertTrue(adapter.awaitResponseEntered());
            assertEquals(1, pool.metrics().leased());
            CompletableFuture<Void> eventual = pool.closeAsync();

            PooledSessionException interrupted;
            try {
                Thread.currentThread().interrupt();
                interrupted = assertThrows(PooledSessionException.class, pool::close);
                assertEquals(true, Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }

            assertEquals(PooledSessionException.Reason.INTERRUPTED, interrupted.reason());
            adapter.releaseResponse();
            assertEquals("slept:hold", request.get(2, TimeUnit.SECONDS));
            eventual.get(2, TimeUnit.SECONDS);
        } finally {
            adapter.releaseResponse();
            executor.shutdownNow();
            assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void closeTimeoutKeepsCleanupObservableAndAsyncViewsCancellationIsolated() throws Exception {
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        PooledProtocolSession<String, String> pool = Procwright.command(TestCliSupport.command())
                .protocolSession(TextLineAdapter::new)
                .withArgs("controlled-line-repl")
                .pooled()
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofMillis(40))
                .withReset(worker -> {
                    resetEntered.countDown();
                    awaitIgnoringInterrupts(releaseReset);
                })
                .open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> request = executor.submit(() -> pool.request("hello"));
            assertTrue(resetEntered.await(1, TimeUnit.SECONDS));

            PooledSessionException timeout = assertThrows(PooledSessionException.class, pool::close);
            assertEquals(PooledSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
            CompletableFuture<Void> cancelled = pool.closeAsync();
            CompletableFuture<Void> eventual = pool.closeAsync();
            assertTrue(cancelled.cancel(true));
            assertFalse(eventual.isCancelled());

            releaseReset.countDown();
            assertEquals("response:hello", request.get(1, TimeUnit.SECONDS));
            eventual.get(1, TimeUnit.SECONDS);
            pool.close();
            assertEquals(0, pool.metrics().size());
        } finally {
            releaseReset.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.close();
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.poolDraft;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.CoordinatedResponseAdapter;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class PooledProtocolSessionRequestIntegrationTest {

    @Test
    void pooledProtocolSessionReusesTypedWorkersWithoutExposingLease() {
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .protocolSession(FramedStringAdapter::new)
                .withArgs("length-line-frame")
                .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withMinIdle(1)
                .open()) {
            assertEquals("first", pool.request("first"));
            assertEquals("second", pool.request("second"));

            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.created());
            assertEquals(2, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
            assertEquals(1, metrics.idle());
        }
    }

    @Test
    void pooledProtocolRejectsNullBeforeLeasingOrRetiringWorker() {
        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), FramedStringAdapter::new, "length-line-frame")
                        .withMaxSize(1)
                        .withWarmupSize(1)
                        .open()) {
            assertThrows(NullPointerException.class, () -> pool.request(null));
            assertThrows(NullPointerException.class, () -> pool.request(null, Duration.ofSeconds(1)));

            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.idle());
            assertEquals(0, metrics.leased());
            assertEquals(0, metrics.retired());
            assertEquals(0, metrics.failedRequests());
            assertEquals("valid", pool.request("valid"));
        }
    }

    @Test
    void pooledProtocolAcquireTimeoutIsDistinctFromRequestTimeout() throws Exception {
        CoordinatedResponseAdapter adapter = new CoordinatedResponseAdapter();
        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), () -> adapter, "ignore-stdin", "--millis=5000")
                        .withMaxSize(1)
                        .withAcquireTimeout(Duration.ofMillis(100))
                        .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch firstStarted = new CountDownLatch(1);
            try {
                Future<String> first = executor.submit(() -> {
                    firstStarted.countDown();
                    return pool.request("first", Duration.ofSeconds(2));
                });
                assertEquals(true, firstStarted.await(1, TimeUnit.SECONDS));
                assertTrue(adapter.awaitResponseEntered());
                assertEquals(1, pool.metrics().leased());

                PooledSessionException exception =
                        assertThrows(PooledSessionException.class, () -> pool.request("second"));

                assertEquals(PooledSessionException.Reason.ACQUIRE_TIMEOUT, exception.reason());
                adapter.releaseResponse();
                assertEquals("slept:first", first.get(2, TimeUnit.SECONDS));
            } finally {
                adapter.releaseResponse();
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolPoolRequestErrorIsRethrownAndRecordedAsFailedRequest() throws Exception {
        AssertionError decoderError = new AssertionError("decoder invariant failed");
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new FramedStringAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                throw decoderError;
            }
        };
        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), adapterFactory, "length-line-frame")
                        .withMaxSize(1)
                        .open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(decoderError, thrown);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(0, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
        }
    }
}

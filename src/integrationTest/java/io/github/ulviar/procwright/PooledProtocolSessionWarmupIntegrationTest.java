/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.poolDraft;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.parseLength;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class PooledProtocolSessionWarmupIntegrationTest {

    @Test
    void configuredWarmupReplenishesRetiredWorkerWithinMaxSize() throws Exception {
        AtomicInteger responses = new AtomicInteger();
        CountDownLatch replacementCreated = new CountDownLatch(1);
        CountDownLatch secondResponseEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondResponse = new CountDownLatch(1);
        AtomicInteger adapters = new AtomicInteger();
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> {
            if (adapters.incrementAndGet() == 2) {
                replacementCreated.countDown();
            }
            return new FramedStringAdapter() {
                @Override
                public String readResponse(ProtocolReaders readers) {
                    String response = super.readResponse(readers);
                    if (responses.incrementAndGet() == 2) {
                        secondResponseEntered.countDown();
                        awaitIgnoringInterrupts(releaseSecondResponse);
                    }
                    return response;
                }
            };
        };
        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), adapterFactory, "length-line-frame")
                        .withMaxSize(1)
                        .withWarmupSize(1)
                        .withMinIdle(1)
                        .withMaxRequestsPerWorker(1)
                        .open()) {
            assertEquals("first", pool.request("first"));
            assertTrue(replacementCreated.await(2, TimeUnit.SECONDS));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> second = executor.submit(() -> pool.request("second"));
                assertTrue(secondResponseEntered.await(2, TimeUnit.SECONDS));

                PooledSessionMetrics metrics = pool.metrics();
                assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
                assertEquals(1, metrics.size());
                assertEquals(1, metrics.leased());
                assertEquals(2, metrics.created());

                releaseSecondResponse.countDown();
                assertEquals("second", second.get(2, TimeUnit.SECONDS));
            } finally {
                releaseSecondResponse.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolWarmupReadinessFailureIsStartupFailure() {
        PooledSessionException exception = assertThrows(PooledSessionException.class, () -> fixtureService()
                .protocolSession(FramedStringAdapter::new)
                .withArgs("length-line-frame")
                .withReadiness(ready -> {
                    throw new IllegalStateException("not ready");
                })
                .pooled()
                .withWarmupSize(1)
                .open());

        assertEquals(PooledSessionException.Reason.STARTUP_FAILED, exception.reason());
    }

    @Test
    void pooledProtocolCreatesOneAdapterPerWorker() throws Exception {
        ConcurrentLinkedQueue<Integer> adapterIds = new ConcurrentLinkedQueue<>();
        AtomicIntegerAdapterFactory factory = new AtomicIntegerAdapterFactory(adapterIds);

        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), factory::newAdapter, "length-line-frame")
                        .withMaxSize(2)
                        .withWarmupSize(2)
                        .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<String> first = executor.submit(() -> pool.request("first"));
                Future<String> second = executor.submit(() -> pool.request("second"));

                assertEquals(true, first.get(2, TimeUnit.SECONDS).matches("adapter-[12]:first"));
                assertEquals(true, second.get(2, TimeUnit.SECONDS).matches("adapter-[12]:second"));
                assertEquals(2, adapterIds.size());
                assertTrue(adapterIds.containsAll(List.of(1, 2)));
            } finally {
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    private static final class AtomicIntegerAdapterFactory {

        private final ConcurrentLinkedQueue<Integer> adapterIds;
        private final AtomicInteger nextId = new AtomicInteger();

        private AtomicIntegerAdapterFactory(ConcurrentLinkedQueue<Integer> adapterIds) {
            this.adapterIds = adapterIds;
        }

        private ProtocolAdapter<String, String> newAdapter() {
            int id = nextId.incrementAndGet();
            adapterIds.add(id);
            return new WorkerScopedAdapter(id);
        }
    }

    private static final class WorkerScopedAdapter implements ProtocolAdapter<String, String> {

        private final int id;
        private String request;

        private WorkerScopedAdapter(int id) {
            this.id = id;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            byte[] body = request.getBytes(StandardCharsets.UTF_8);
            writer.writeLine(Integer.toString(body.length));
            writer.write(body);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            stdout.readExactly(length);
            assertEquals("", stdout.readLine(1));
            assertEquals("END", stdout.readLine(8));
            return "adapter-" + id + ":" + request;
        }
    }
}

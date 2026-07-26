/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.poolDraft;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.parseLength;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.invokeConcurrently;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.protocolPid;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.responsePid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;

final class ProtocolAdapterFactoryIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void concurrentDirectOpensCanOverlapAdapterFactoryCalls() throws Exception {
        OverlappingAdapterFactory factory = new OverlappingAdapterFactory();
        ProtocolSessionScenario.Draft<String, String> draft = Procwright.command(TestCliSupport.command())
                .protocolSession(factory)
                .withArgs("controlled-line-repl");

        List<Long> pids = invokeConcurrently(() -> protocolPid(draft), () -> protocolPid(draft));

        assertNotEquals(pids.get(0), pids.get(1));
        assertEquals(2, factory.calls());
        assertEquals(2, factory.maximumConcurrentCalls());
    }

    @Test
    void pooledProtocolCreatesOneAdapterPerWorker() throws Exception {
        ConcurrentLinkedQueue<Integer> adapterIds = new ConcurrentLinkedQueue<>();
        AtomicIntegerAdapterFactory factory = new AtomicIntegerAdapterFactory(adapterIds);

        try (PooledProtocolSession<String, String> pool = poolDraft(
                        fixtureService(), factory::newAdapter, "length-line-frame")
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

    @Test
    void concurrentPoolDraftOpensCanOverlapAdapterFactoryCalls() throws Exception {
        OverlappingAdapterFactory factory = new OverlappingAdapterFactory();
        ProtocolSessionScenario.PoolDraft<String, String> draft = Procwright.command(TestCliSupport.command())
                .protocolSession(factory)
                .withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1);

        List<Long> pids = invokeConcurrently(() -> pooledProtocolPid(draft), () -> pooledProtocolPid(draft));

        assertNotEquals(pids.get(0), pids.get(1));
        assertEquals(2, factory.calls());
        assertEquals(2, factory.maximumConcurrentCalls());
    }

    @Test
    void throwingFactoryPreventsDirectAndPoolProcessLaunch() {
        Path missingExecutable = temporaryDirectory.resolve("missing-throwing-factory-executable");
        IllegalStateException factoryFailure = new IllegalStateException("factory failed");
        Supplier<ProtocolAdapter<String, String>> factory = () -> {
            throw factoryFailure;
        };
        ProtocolSessionScenario.Draft<String, String> draft = protocolDraft(factory, missingExecutable);

        assertSame(factoryFailure, assertThrows(IllegalStateException.class, draft::open));
        PooledSessionException poolFailure = assertThrows(
                PooledSessionException.class,
                () -> draft.pooled().withWarmupSize(1).open());

        assertEquals(PooledSessionException.Reason.STARTUP_FAILED, poolFailure.reason());
        assertSame(factoryFailure, poolFailure.getCause());
    }

    @Test
    void nullFactoryResultPreventsDirectAndPoolProcessLaunch() {
        Path missingExecutable = temporaryDirectory.resolve("missing-null-factory-executable");
        Supplier<ProtocolAdapter<String, String>> factory = () -> null;
        ProtocolSessionScenario.Draft<String, String> draft = protocolDraft(factory, missingExecutable);

        NullPointerException directFailure = assertThrows(NullPointerException.class, draft::open);
        PooledSessionException poolFailure = assertThrows(
                PooledSessionException.class,
                () -> draft.pooled().withWarmupSize(1).open());

        assertEquals("adapterFactory returned null", directFailure.getMessage());
        assertEquals(PooledSessionException.Reason.STARTUP_FAILED, poolFailure.reason());
        assertEquals("adapterFactory returned null", poolFailure.getCause().getMessage());
    }

    private static ProtocolSessionScenario.Draft<String, String> protocolDraft(
            Supplier<ProtocolAdapter<String, String>> factory, Path missingExecutable) {
        return Procwright.command(missingExecutable.toString())
                .protocolSession(factory)
                .withArg("unused");
    }

    private static long pooledProtocolPid(ProtocolSessionScenario.PoolDraft<String, String> draft) {
        try (PooledProtocolSession<String, String> pool = draft.open()) {
            return responsePid(pool.request("pid"));
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

    private static final class OverlappingAdapterFactory implements Supplier<ProtocolAdapter<String, String>> {

        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch bothInsideFactory = new CountDownLatch(2);

        @Override
        public ProtocolAdapter<String, String> get() {
            calls.incrementAndGet();
            int active = activeCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);
            bothInsideFactory.countDown();
            try {
                if (!bothInsideFactory.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Procwright serialized concurrent adapter factory calls");
                }
                return new TextLineAdapter();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while proving concurrent factory calls", exception);
            } finally {
                activeCalls.decrementAndGet();
            }
        }

        private int calls() {
            return calls.get();
        }

        private int maximumConcurrentCalls() {
            return maximumConcurrentCalls.get();
        }
    }
}

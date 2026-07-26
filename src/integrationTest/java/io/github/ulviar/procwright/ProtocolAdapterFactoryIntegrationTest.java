/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.invokeConcurrently;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.protocolPid;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.responsePid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

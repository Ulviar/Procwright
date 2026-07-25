/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.invokeConcurrently;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.responsePid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ScenarioPoolDraftSemanticsIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void linePoolDraftSnapshotsWorkerAndCanBeOpenedSequentiallyAndConcurrently() throws Exception {
        LineSessionScenario.Draft base = Procwright.command(TestCliSupport.command())
                .lineSession()
                .withArgs("controlled-line-repl", "--response-prefix=snapshot:");
        LineSessionScenario.PoolDraft snapshot = base.pooled().withMaxSize(1).withWarmupSize(1);
        LineSessionScenario.PoolDraft changed = base.withArg("--response-prefix=changed:")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1);

        long firstPid;
        try (PooledLineSession pool = snapshot.open()) {
            assertEquals("snapshot:value", pool.request("value").text());
            firstPid = responsePid(pool.request("pid").text());
        }
        long secondPid;
        try (PooledLineSession pool = snapshot.open()) {
            assertEquals("snapshot:value", pool.request("value").text());
            secondPid = responsePid(pool.request("pid").text());
        }
        List<Long> concurrent = invokeConcurrently(() -> pooledLinePid(snapshot), () -> pooledLinePid(snapshot));

        assertEquals(
                4,
                java.util.stream.Stream.of(firstPid, secondPid, concurrent.get(0), concurrent.get(1))
                        .distinct()
                        .count());
        try (PooledLineSession pool = changed.open()) {
            assertEquals("changed:value", pool.request("value").text());
        }
    }

    @Test
    void protocolPoolDraftSnapshotsWorkerAndCanBeOpenedRepeatedly() {
        ProtocolSessionScenario.Draft<String, String> base = Procwright.command(TestCliSupport.command())
                .protocolSession(TextLineAdapter::new)
                .withArgs("controlled-line-repl", "--response-prefix=snapshot:");
        ProtocolSessionScenario.PoolDraft<String, String> snapshot =
                base.pooled().withMaxSize(1).withWarmupSize(1);
        ProtocolSessionScenario.PoolDraft<String, String> changed = base.withArg("--response-prefix=changed:")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1);

        long firstPid;
        try (PooledProtocolSession<String, String> pool = snapshot.open()) {
            assertEquals("snapshot:value", pool.request("value"));
            firstPid = responsePid(pool.request("pid"));
        }
        try (PooledProtocolSession<String, String> pool = snapshot.open()) {
            assertEquals("snapshot:value", pool.request("value"));
            assertNotEquals(firstPid, responsePid(pool.request("pid")));
        }
        try (PooledProtocolSession<String, String> pool = changed.open()) {
            assertEquals("changed:value", pool.request("value"));
        }
    }

    @Test
    void lineAndProtocolPoolSettingsCreateIndependentBranches() {
        LineSessionScenario.PoolDraft lineBase = Procwright.command(TestCliSupport.command())
                .lineSession()
                .withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1);
        ProtocolSessionScenario.PoolDraft<String, String> protocolBase = Procwright.command(TestCliSupport.command())
                .protocolSession(TextLineAdapter::new)
                .withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1);
        LineSessionScenario.PoolDraft lineEager = lineBase.withWarmupSize(1);
        ProtocolSessionScenario.PoolDraft<String, String> protocolEager = protocolBase.withWarmupSize(1);

        try (PooledLineSession eager = lineEager.open();
                PooledLineSession lazy = lineBase.open();
                PooledProtocolSession<String, String> eagerProtocol = protocolEager.open();
                PooledProtocolSession<String, String> lazyProtocol = protocolBase.open()) {
            assertEquals(0, lazy.metrics().size());
            assertEquals(1, eager.metrics().size());
            assertEquals(0, lazyProtocol.metrics().size());
            assertEquals(1, eagerProtocol.metrics().size());
        }
    }

    @Test
    void linePoolRejectsInvalidCrossFieldConfigurationBeforeLaunch() {
        Path missingExecutable = temporaryDirectory.resolve("missing-line-pool-executable");
        LineSessionScenario.PoolDraft invalid = Procwright.command(missingExecutable.toString())
                .lineSession()
                .withArg("unused")
                .pooled()
                .withWarmupSize(2)
                .withMaxSize(1);

        assertThrows(IllegalArgumentException.class, invalid::open);
    }

    @Test
    void protocolPoolRejectsInvalidCrossFieldConfigurationBeforeFactoryOrLaunch() {
        Path missingExecutable = temporaryDirectory.resolve("missing-protocol-pool-executable");
        AtomicInteger adapters = new AtomicInteger();
        ProtocolSessionScenario.PoolDraft<String, String> invalid = Procwright.command(missingExecutable.toString())
                .protocolSession(() -> {
                    adapters.incrementAndGet();
                    return new TextLineAdapter();
                })
                .withArg("unused")
                .pooled()
                .withMinIdle(2)
                .withMaxSize(1);

        assertThrows(IllegalArgumentException.class, invalid::open);
        assertEquals(0, adapters.get());
    }

    private static long pooledLinePid(LineSessionScenario.PoolDraft draft) {
        try (PooledLineSession pool = draft.open()) {
            return responsePid(pool.request("pid").text());
        }
    }
}

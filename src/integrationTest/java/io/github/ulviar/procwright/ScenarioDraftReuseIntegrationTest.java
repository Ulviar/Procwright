/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.SCENARIO_WATCHDOG_SECONDS;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.awaitStream;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.interactivePid;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.invokeConcurrently;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.linePid;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.protocolPid;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.responsePid;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.session.LineSession;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ScenarioDraftReuseIntegrationTest {

    @Test
    void runDraftSupportsSequentialAndConcurrentTerminalCalls() throws Exception {
        RunScenario.Draft draft = Procwright.command(TestCliSupport.command())
                .run()
                .withArgs("controlled-line-repl")
                .withInput("pid\n");

        assertSequentialAndConcurrentProcesses(() -> {
            CommandResult result = draft.execute();
            return responsePid(result.stdout());
        });
    }

    @Test
    void interactiveDraftSupportsSequentialAndConcurrentTerminalCalls() throws Exception {
        InteractiveScenario.Draft draft =
                Procwright.command(TestCliSupport.command()).interactive().withArgs("controlled-line-repl");

        assertSequentialAndConcurrentProcesses(() -> interactivePid(draft));
    }

    @Test
    void lineDraftSupportsSequentialAndConcurrentTerminalCalls() throws Exception {
        LineSessionScenario.Draft draft =
                Procwright.command(TestCliSupport.command()).lineSession().withArgs("controlled-line-repl");

        assertSequentialAndConcurrentProcesses(() -> linePid(draft));
    }

    @Test
    void protocolDraftSupportsSequentialAndConcurrentTerminalCallsWithFreshAdapters() throws Exception {
        AtomicInteger adapters = new AtomicInteger();
        ProtocolSessionScenario.Draft<String, String> draft = Procwright.command(TestCliSupport.command())
                .protocolSession(() -> {
                    adapters.incrementAndGet();
                    return new TextLineAdapter();
                })
                .withArgs("controlled-line-repl");

        assertSequentialAndConcurrentProcesses(() -> protocolPid(draft));
        assertEquals(4, adapters.get());
    }

    @Test
    void streamDraftRetainsItsListenerAcrossSequentialAndConcurrentTerminalCalls() throws Exception {
        AtomicInteger outputCharacters = new AtomicInteger();
        StreamScenario.Draft draft = Procwright.command(TestCliSupport.command())
                .listen()
                .withArgs("exit", "--stdout=x")
                .onOutput(chunk -> outputCharacters.addAndGet(chunk.text().length()));

        assertEquals(true, awaitStream(draft));
        assertEquals(true, awaitStream(draft));
        assertEquals(List.of(true, true), invokeConcurrently(() -> awaitStream(draft), () -> awaitStream(draft)));
        assertEquals(4, outputCharacters.get());
    }

    @Test
    void retainedReadinessProbeCanRunConcurrentlyForIndependentOpens() throws Exception {
        OverlappingReadinessProbe readiness = new OverlappingReadinessProbe();
        LineSessionScenario.Draft draft = Procwright.command(TestCliSupport.command())
                .lineSession()
                .withArgs("controlled-line-repl")
                .withReadiness(readiness);

        List<Long> pids = invokeConcurrently(() -> linePid(draft), () -> linePid(draft));

        assertEquals(2, pids.stream().distinct().count());
        assertEquals(2, readiness.calls());
        assertEquals(2, readiness.maximumConcurrentCalls());
    }

    private static void assertSequentialAndConcurrentProcesses(Callable<Long> terminal) throws Exception {
        long first = terminal.call();
        long second = terminal.call();
        List<Long> concurrent = invokeConcurrently(terminal, terminal);

        assertEquals(
                4,
                Stream.of(first, second, concurrent.get(0), concurrent.get(1))
                        .distinct()
                        .count());
    }

    private static final class OverlappingReadinessProbe implements Consumer<LineSession> {

        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch bothInsideReadiness = new CountDownLatch(2);

        @Override
        public void accept(LineSession ignored) {
            calls.incrementAndGet();
            int active = activeCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);
            bothInsideReadiness.countDown();
            try {
                if (!bothInsideReadiness.await(SCENARIO_WATCHDOG_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("Procwright serialized retained readiness probe calls");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while proving concurrent readiness calls", exception);
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

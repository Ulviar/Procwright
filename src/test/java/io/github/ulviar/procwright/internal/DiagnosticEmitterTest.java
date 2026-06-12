/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.diagnostics.DiagnosticsOptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class DiagnosticEmitterTest {

    @Test
    void listenerReceivesLifecycleEventsInEmitOrder() {
        for (int run = 0; run < 200; run++) {
            CopyOnWriteArrayList<DiagnosticEventType> delivered = new CopyOnWriteArrayList<>();
            DiagnosticEmitter emitter = DiagnosticEmitter.of(
                    DiagnosticsOptions.defaults().withListener(event -> delivered.add(event.type())),
                    "run",
                    CommandEcho.empty());

            emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
            emitter.emit(DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "42"));
            emitter.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

            awaitCount(delivered, 3);
            assertEquals(
                    List.of(
                            DiagnosticEventType.COMMAND_PREPARED,
                            DiagnosticEventType.PROCESS_STARTED,
                            DiagnosticEventType.PROCESS_EXITED),
                    List.copyOf(delivered),
                    "events must arrive in lifecycle order on run " + run);
        }
    }

    @Test
    void listenerFailureDoesNotDropOrReorderLaterEvents() {
        CopyOnWriteArrayList<DiagnosticEventType> delivered = new CopyOnWriteArrayList<>();
        DiagnosticEmitter emitter = DiagnosticEmitter.of(
                DiagnosticsOptions.defaults().withListener(event -> {
                    if (event.type() == DiagnosticEventType.PROCESS_STARTED) {
                        throw new AssertionError("listener failed");
                    }
                    delivered.add(event.type());
                }),
                "run",
                CommandEcho.empty());

        emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
        emitter.emit(DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "42"));
        emitter.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

        awaitCount(delivered, 2);
        assertEquals(
                List.of(DiagnosticEventType.COMMAND_PREPARED, DiagnosticEventType.PROCESS_EXITED),
                List.copyOf(delivered));
    }

    @Test
    void listenerAndTranscriptSinkAreIsolatedFromEachOther() {
        CopyOnWriteArrayList<DiagnosticEvent> recorded = new CopyOnWriteArrayList<>();
        DiagnosticEmitter emitter = DiagnosticEmitter.of(
                DiagnosticsOptions.defaults()
                        .withListener(event -> {
                            throw new AssertionError("listener failed");
                        })
                        .withTranscriptSink(recorded::add),
                "run",
                CommandEcho.empty());

        emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
        emitter.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

        awaitCount(recorded, 2);
        assertEquals(DiagnosticEventType.COMMAND_PREPARED, recorded.get(0).type());
        assertEquals(DiagnosticEventType.PROCESS_EXITED, recorded.get(1).type());
    }

    private static void awaitCount(List<?> delivered, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline && delivered.size() < expected) {
            Thread.onSpinWait();
        }
        assertTrue(delivered.size() >= expected, "expected " + expected + " deliveries, got " + delivered.size());
    }
}

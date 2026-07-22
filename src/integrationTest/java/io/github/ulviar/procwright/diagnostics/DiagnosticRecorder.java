/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class DiagnosticRecorder implements DiagnosticListener, DiagnosticTranscriptSink {

    private final CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
    private final Map<DiagnosticEventType, CountDownLatch> observed = observedLatches();

    @Override
    public void onEvent(DiagnosticEvent event) {
        add(event);
    }

    @Override
    public void record(DiagnosticEvent event) {
        add(event);
    }

    List<DiagnosticEvent> events() {
        return List.copyOf(events);
    }

    boolean awaitContains(DiagnosticEventType type) {
        try {
            return observed.get(type).await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting diagnostic event " + type, exception);
        }
    }

    DiagnosticEvent first(DiagnosticEventType type) {
        awaitContains(type);
        return events.stream()
                .filter(event -> event.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing diagnostic event: " + type));
    }

    private void add(DiagnosticEvent event) {
        events.add(event);
        observed.get(event.type()).countDown();
    }

    private static Map<DiagnosticEventType, CountDownLatch> observedLatches() {
        EnumMap<DiagnosticEventType, CountDownLatch> latches = new EnumMap<>(DiagnosticEventType.class);
        for (DiagnosticEventType type : DiagnosticEventType.values()) {
            latches.put(type, new CountDownLatch(1));
        }
        return latches;
    }
}

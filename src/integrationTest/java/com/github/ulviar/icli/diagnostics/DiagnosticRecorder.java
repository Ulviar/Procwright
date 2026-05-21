package com.github.ulviar.icli.diagnostics;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class DiagnosticRecorder implements DiagnosticListener, DiagnosticTranscriptSink {

    private final CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(DiagnosticEvent event) {
        events.add(event);
    }

    @Override
    public void record(DiagnosticEvent event) {
        events.add(event);
    }

    List<DiagnosticEvent> events() {
        return List.copyOf(events);
    }

    boolean awaitContains(DiagnosticEventType type) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (contains(type)) {
                return true;
            }
            sleep();
        }
        return contains(type);
    }

    DiagnosticEvent first(DiagnosticEventType type) {
        awaitContains(type);
        return events.stream()
                .filter(event -> event.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing diagnostic event: " + type));
    }

    private boolean contains(DiagnosticEventType type) {
        return events.stream().anyMatch(event -> event.type() == type);
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
    }
}

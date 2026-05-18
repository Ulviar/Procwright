package com.github.ulviar.icli.testing.diagnostics;

import com.github.ulviar.icli.diagnostics.DiagnosticEvent;
import com.github.ulviar.icli.diagnostics.DiagnosticEventType;
import com.github.ulviar.icli.diagnostics.DiagnosticListener;
import com.github.ulviar.icli.diagnostics.DiagnosticTranscriptSink;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe diagnostic recorder for tests.
 */
public final class DiagnosticRecorder implements DiagnosticListener, DiagnosticTranscriptSink {

    private final CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(DiagnosticEvent event) {
        events.add(event);
    }

    @Override
    public void record(DiagnosticEvent event) {
        events.add(event);
    }

    /**
     * Returns recorded events.
     *
     * @return immutable recorded events
     */
    public List<DiagnosticEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns whether an event of the given type was recorded.
     *
     * @param type event type
     * @return true when present
     */
    public boolean contains(DiagnosticEventType type) {
        return events.stream().anyMatch(event -> event.type() == type);
    }

    /**
     * Waits until an event of the given type is recorded.
     *
     * @param type event type
     * @return true when present before the deadline
     */
    public boolean awaitContains(DiagnosticEventType type) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (contains(type)) {
                return true;
            }
            sleep();
        }
        return contains(type);
    }

    /**
     * Returns the first event of the given type.
     *
     * @param type event type
     * @return first matching event
     */
    public DiagnosticEvent first(DiagnosticEventType type) {
        awaitContains(type);
        return events.stream()
                .filter(event -> event.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing diagnostic event: " + type));
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

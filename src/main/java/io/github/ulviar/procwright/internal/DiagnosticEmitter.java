/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.diagnostics.DiagnosticsOptions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class DiagnosticEmitter {

    private static final DiagnosticEmitter DISABLED =
            new DiagnosticEmitter(DiagnosticsOptions.defaults(), "disabled", "disabled", CommandEcho.empty(), false);

    private final DiagnosticsOptions options;
    private final String runId;
    private final String scenario;
    private final CommandEcho command;
    private final boolean enabled;
    private final SerialDelivery listenerDelivery = new SerialDelivery("procwright-diagnostics-listener-");
    private final SerialDelivery transcriptDelivery = new SerialDelivery("procwright-diagnostics-transcript-");

    private DiagnosticEmitter(
            DiagnosticsOptions options, String runId, String scenario, CommandEcho command, boolean enabled) {
        this.options = Objects.requireNonNull(options, "options");
        this.runId = CommandValidation.requireText(runId, "runId");
        this.scenario = CommandValidation.requireText(scenario, "scenario");
        this.command = Objects.requireNonNull(command, "command");
        this.enabled = enabled;
    }

    public static DiagnosticEmitter of(DiagnosticsOptions options, String scenario, CommandEcho command) {
        Objects.requireNonNull(options, "options");
        scenario = CommandValidation.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        if (!options.enabled()) {
            return DISABLED;
        }
        return new DiagnosticEmitter(options, UUID.randomUUID().toString(), scenario, command, true);
    }

    public static DiagnosticEmitter of(DiagnosticsOptions options, String scenario, Supplier<CommandEcho> command) {
        Objects.requireNonNull(options, "options");
        scenario = CommandValidation.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        if (!options.enabled()) {
            return DISABLED;
        }
        return new DiagnosticEmitter(options, UUID.randomUUID().toString(), scenario, command.get(), true);
    }

    public boolean enabled() {
        return enabled;
    }

    public void emit(DiagnosticEventType type) {
        emit(type, Map.of());
    }

    public void emit(DiagnosticEventType type, Map<String, String> attributes) {
        if (!enabled) {
            return;
        }
        DiagnosticEvent event = new DiagnosticEvent(
                type, runId, Instant.now(), scenario, command, DiagnosticAttributeSchema.validate(type, attributes));
        if (options.listenerEnabled()) {
            listenerDelivery.submit(() -> deliverListener(event));
        }
        if (options.transcriptSinkEnabled()) {
            transcriptDelivery.submit(() -> deliverTranscript(event));
        }
    }

    private void deliverListener(DiagnosticEvent event) {
        try {
            options.listener().onEvent(event);
        } catch (Throwable ignored) {
            // Diagnostics are observational and must not change command behavior.
        }
    }

    private void deliverTranscript(DiagnosticEvent event) {
        try {
            options.transcriptSink().record(event);
        } catch (Throwable ignored) {
            // Diagnostics are observational and must not change command behavior.
        }
    }

    public static Map<String, String> attributes(String name, String value) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put(name, value);
        return attributes;
    }

    public static Map<String, String> attributes(
            String firstName, String firstValue, String secondName, String secondValue) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put(firstName, firstValue);
        attributes.put(secondName, secondValue);
        return attributes;
    }

    /**
     * Per-destination sequential asynchronous delivery.
     *
     * <p>Events for one emitter are delivered to one destination in submission order without blocking the emitting
     * thread. A single drainer runs at a time and exits as soon as the queue is empty, so no idle thread outlives a
     * run; bursts within a run reuse one drainer instead of starting one thread per event.
     */
    private static final class SerialDelivery {

        private final String threadPrefix;
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();

        private SerialDelivery(String threadPrefix) {
            this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        }

        void submit(Runnable task) {
            tasks.add(task);
            if (draining.compareAndSet(false, true)) {
                Threading.start(threadPrefix, this::drain);
            }
        }

        private void drain() {
            while (true) {
                Runnable task;
                while ((task = tasks.poll()) != null) {
                    task.run();
                }
                draining.set(false);
                // A submitter that raced the shutdown either re-acquired the drain flag itself or left a task behind
                // for this thread to claim again.
                if (tasks.isEmpty() || !draining.compareAndSet(false, true)) {
                    return;
                }
            }
        }
    }
}

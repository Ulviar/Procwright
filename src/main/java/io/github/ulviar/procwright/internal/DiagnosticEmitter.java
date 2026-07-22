/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class DiagnosticEmitter {

    static final int MAX_PENDING_PER_DESTINATION = 32;
    static final int MAX_DELIVERIES_PER_TURN = 8;
    static final int MAX_ERROR_ATTRIBUTE_LENGTH = 240;
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final BoundedIsolatedTaskDispatcher DELIVERY_DISPATCHER = new BoundedIsolatedTaskDispatcher(4, 256);
    private static final EventFactory DEFAULT_EVENT_FACTORY = DiagnosticEvent::new;
    private static final DiagnosticEmitter DISABLED = new DiagnosticEmitter(
            DiagnosticsSettings.disabled(), "disabled", "disabled", CommandEcho.empty(), false, DEFAULT_EVENT_FACTORY);

    private final DiagnosticsSettings settings;
    private final String runId;
    private final String scenario;
    private final CommandEcho command;
    private final boolean enabled;
    private final EventFactory eventFactory;
    private final SerialDelivery listenerDelivery = new SerialDelivery();
    private final SerialDelivery transcriptDelivery = new SerialDelivery();
    private final AtomicBoolean processFailureEmitted = new AtomicBoolean();

    private DiagnosticEmitter(
            DiagnosticsSettings settings,
            String runId,
            String scenario,
            CommandEcho command,
            boolean enabled,
            EventFactory eventFactory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runId = CommandValidation.requireText(runId, "runId");
        this.scenario = CommandValidation.requireText(scenario, "scenario");
        this.command = Objects.requireNonNull(command, "command");
        this.enabled = enabled;
        this.eventFactory = Objects.requireNonNull(eventFactory, "eventFactory");
    }

    public static DiagnosticEmitter of(DiagnosticsSettings settings, String scenario, CommandEcho command) {
        Objects.requireNonNull(settings, "settings");
        scenario = CommandValidation.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        if (!settings.enabled()) {
            return DISABLED;
        }
        return new DiagnosticEmitter(
                settings, UUID.randomUUID().toString(), scenario, command, true, DEFAULT_EVENT_FACTORY);
    }

    public static DiagnosticEmitter of(DiagnosticsSettings settings, String scenario, Supplier<CommandEcho> command) {
        Objects.requireNonNull(settings, "settings");
        scenario = CommandValidation.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        if (!settings.enabled()) {
            return DISABLED;
        }
        return new DiagnosticEmitter(
                settings, UUID.randomUUID().toString(), scenario, command.get(), true, DEFAULT_EVENT_FACTORY);
    }

    static DiagnosticEmitter of(
            DiagnosticsSettings settings, String scenario, CommandEcho command, EventFactory eventFactory) {
        Objects.requireNonNull(settings, "settings");
        scenario = CommandValidation.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(eventFactory, "eventFactory");
        if (!settings.enabled()) {
            return DISABLED;
        }
        return new DiagnosticEmitter(settings, UUID.randomUUID().toString(), scenario, command, true, eventFactory);
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
        DiagnosticEvent event = eventFactory.create(
                type, runId, Instant.now(), scenario, command, DiagnosticAttributeSchema.validate(type, attributes));
        if (type == DiagnosticEventType.PROCESS_FAILED && !processFailureEmitted.compareAndSet(false, true)) {
            return;
        }
        if (settings.listenerEnabled()) {
            listenerDelivery.submit(() -> deliverListener(event));
        }
        if (settings.transcriptSinkEnabled()) {
            transcriptDelivery.submit(() -> deliverTranscript(event));
        }
    }

    private void deliverListener(DiagnosticEvent event) {
        try {
            settings.listener().onEvent(event);
        } catch (Throwable ignored) {
            // Diagnostics are observational and must not change command behavior.
        }
    }

    private void deliverTranscript(DiagnosticEvent event) {
        try {
            settings.transcriptSink().record(event);
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

    public static Map<String, String> failureAttributes(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Class<?> type = failure.getClass();
        while (type != null && Throwable.class.isAssignableFrom(type)) {
            String name = type.getName();
            if (name.length() <= MAX_ERROR_ATTRIBUTE_LENGTH
                    && CLASS_NAME.matcher(name).matches()) {
                return attributes("error", name);
            }
            type = type.getSuperclass();
        }
        return attributes("error", Throwable.class.getName());
    }

    public void emitProcessFailure(Throwable primaryFailure) {
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        try {
            emit(DiagnosticEventType.PROCESS_FAILED, failureAttributes(primaryFailure));
        } catch (RuntimeException | Error diagnosticFailure) {
            SuppressionSupport.attach(primaryFailure, diagnosticFailure);
        }
    }

    @FunctionalInterface
    interface EventFactory {

        DiagnosticEvent create(
                DiagnosticEventType type,
                String runId,
                Instant timestamp,
                String scenario,
                CommandEcho command,
                Map<String, String> attributes);
    }

    /**
     * Per-destination sequential asynchronous delivery.
     *
     * <p>Events for one emitter are delivered to one destination in submission order without blocking the emitting
     * thread. A single drainer runs at a time for a bounded turn. A non-empty destination then rejoins the tail of the
     * shared FIFO dispatcher, so continuous emission cannot starve another accepted destination and no idle thread
     * outlives a run.
     */
    private static final class SerialDelivery {

        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean draining;

        private SerialDelivery() {}

        synchronized void submit(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (tasks.size() == MAX_PENDING_PER_DESTINATION) {
                tasks.removeFirst();
            }
            tasks.addLast(task);
            if (draining) {
                return;
            }
            draining = true;
            if (!DELIVERY_DISPATCHER.executeRequeueing(
                    "procwright-diagnostics-", this::drainBatch, ignored -> rejectPending())) {
                rejectPending();
            }
        }

        private boolean drainBatch() {
            for (int delivered = 0; delivered < MAX_DELIVERIES_PER_TURN; delivered++) {
                Runnable task;
                synchronized (this) {
                    task = tasks.pollFirst();
                    if (task == null) {
                        draining = false;
                        return false;
                    }
                }
                task.run();
            }
            synchronized (this) {
                if (tasks.isEmpty()) {
                    draining = false;
                    return false;
                }
                return true;
            }
        }

        private synchronized void rejectPending() {
            tasks.clear();
            draining = false;
        }
    }
}

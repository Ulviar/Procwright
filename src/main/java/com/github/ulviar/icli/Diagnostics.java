package com.github.ulviar.icli;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class Diagnostics {

    private static final Diagnostics DISABLED =
            new Diagnostics(DiagnosticsOptions.defaults(), "disabled", "disabled", CommandEcho.empty(), false);

    private final DiagnosticsOptions options;
    private final String runId;
    private final String scenario;
    private final CommandEcho command;
    private final boolean enabled;

    private Diagnostics(
            DiagnosticsOptions options, String runId, String scenario, CommandEcho command, boolean enabled) {
        this.options = Objects.requireNonNull(options, "options");
        this.runId = CommandSpec.requireText(runId, "runId");
        this.scenario = CommandSpec.requireText(scenario, "scenario");
        this.command = Objects.requireNonNull(command, "command");
        this.enabled = enabled;
    }

    static Diagnostics of(DiagnosticsOptions options, String scenario, CommandEcho command) {
        Objects.requireNonNull(options, "options");
        scenario = CommandSpec.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        if (!options.enabled()) {
            return DISABLED;
        }
        return new Diagnostics(options, UUID.randomUUID().toString(), scenario, command, true);
    }

    static Diagnostics of(DiagnosticsOptions options, String scenario, Supplier<CommandEcho> command) {
        Objects.requireNonNull(options, "options");
        scenario = CommandSpec.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        if (!options.enabled()) {
            return DISABLED;
        }
        return new Diagnostics(options, UUID.randomUUID().toString(), scenario, command.get(), true);
    }

    boolean enabled() {
        return enabled;
    }

    void emit(DiagnosticEventType type) {
        emit(type, Map.of());
    }

    void emit(DiagnosticEventType type, Map<String, String> attributes) {
        if (!enabled) {
            return;
        }
        DiagnosticEvent event = new DiagnosticEvent(
                type, runId, Instant.now(), scenario, command, DiagnosticAttributeSchema.validate(type, attributes));
        if (options.listenerEnabled()) {
            Thread.ofVirtual().name("icli-diagnostics-listener-", 0).start(() -> deliverListener(event));
        }
        if (options.transcriptSinkEnabled()) {
            Thread.ofVirtual().name("icli-diagnostics-transcript-", 0).start(() -> deliverTranscript(event));
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

    static Map<String, String> attributes(String name, String value) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put(name, value);
        return attributes;
    }

    static Map<String, String> attributes(String firstName, String firstValue, String secondName, String secondValue) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put(firstName, firstValue);
        attributes.put(secondName, secondValue);
        return attributes;
    }
}

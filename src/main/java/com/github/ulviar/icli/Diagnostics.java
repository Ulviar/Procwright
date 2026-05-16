package com.github.ulviar.icli;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class Diagnostics {

    private final DiagnosticsOptions options;
    private final String scenario;
    private final CommandEcho command;

    private Diagnostics(DiagnosticsOptions options, String scenario, CommandEcho command) {
        this.options = Objects.requireNonNull(options, "options");
        this.scenario = CommandSpec.requireText(scenario, "scenario");
        this.command = Objects.requireNonNull(command, "command");
    }

    static Diagnostics of(DiagnosticsOptions options, String scenario, CommandEcho command) {
        return new Diagnostics(options, scenario, command);
    }

    void emit(DiagnosticEventType type) {
        emit(type, Map.of());
    }

    void emit(DiagnosticEventType type, Map<String, String> attributes) {
        DiagnosticEvent event =
                new DiagnosticEvent(type, Instant.now(), scenario, command, new LinkedHashMap<>(attributes));
        Thread.ofVirtual().name("icli-diagnostics-", 0).start(() -> deliver(event));
    }

    private void deliver(DiagnosticEvent event) {
        try {
            options.listener().onEvent(event);
        } catch (Throwable ignored) {
            // Diagnostics are observational and must not change command behavior.
        }
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

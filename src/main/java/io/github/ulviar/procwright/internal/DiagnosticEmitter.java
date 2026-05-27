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
import java.util.function.Supplier;

public final class DiagnosticEmitter {

    private static final DiagnosticEmitter DISABLED =
            new DiagnosticEmitter(DiagnosticsOptions.defaults(), "disabled", "disabled", CommandEcho.empty(), false);

    private final DiagnosticsOptions options;
    private final String runId;
    private final String scenario;
    private final CommandEcho command;
    private final boolean enabled;

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
            Threading.start("procwright-diagnostics-listener-", () -> deliverListener(event));
        }
        if (options.transcriptSinkEnabled()) {
            Threading.start("procwright-diagnostics-transcript-", () -> deliverTranscript(event));
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
}

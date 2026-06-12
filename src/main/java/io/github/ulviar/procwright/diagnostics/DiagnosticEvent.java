/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

import io.github.ulviar.procwright.internal.CommandValidation;
import io.github.ulviar.procwright.internal.DiagnosticAttributeSchema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured diagnostic event.
 *
 * @param type event type
 * @param runId process-lifecycle correlation id
 * @param timestamp event timestamp
 * @param scenario scenario that emitted the event
 * @param command redaction-friendly command echo
 * @param attributes structured event attributes
 */
public record DiagnosticEvent(
        DiagnosticEventType type,
        String runId,
        Instant timestamp,
        String scenario,
        CommandEcho command,
        Map<String, String> attributes) {

    /**
     * Creates a diagnostic event with a fresh correlation id.
     *
     * @param type event type
     * @param timestamp event timestamp
     * @param scenario scenario that emitted the event
     * @param command redaction-friendly command echo
     * @param attributes structured event attributes
     */
    public DiagnosticEvent(
            DiagnosticEventType type,
            Instant timestamp,
            String scenario,
            CommandEcho command,
            Map<String, String> attributes) {
        this(type, UUID.randomUUID().toString(), timestamp, scenario, command, attributes);
    }

    /**
     * Validates and snapshots a diagnostic event.
     *
     * @param type event type
     * @param runId process-lifecycle correlation id
     * @param timestamp event timestamp
     * @param scenario scenario that emitted the event
     * @param command redaction-friendly command echo
     * @param attributes structured event attributes
     */
    public DiagnosticEvent {
        Objects.requireNonNull(type, "type");
        CommandValidation.requireText(runId, "runId");
        Objects.requireNonNull(timestamp, "timestamp");
        CommandValidation.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        attributes = DiagnosticAttributeSchema.validate(
                type, new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes")));
    }
}

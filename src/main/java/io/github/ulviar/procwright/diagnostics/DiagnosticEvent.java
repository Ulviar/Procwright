/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

import io.github.ulviar.procwright.internal.CommandValidation;
import io.github.ulviar.procwright.internal.DiagnosticAttributeSchema;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable structured diagnostic event correlated with one process lifecycle.
 *
 * <p>The attributes map is defensively copied and must match the schema documented on {@link DiagnosticEventType};
 * arbitrary extra keys are rejected. Attribute values contain metadata rather than process input/output. Runtime
 * events from one lifecycle share a {@code runId}; separate processes, including pool workers, have separate ids.
 * Timestamps record wall-clock instants and must not be used as monotonic duration measurements.
 *
 * @param type event type
 * @param runId non-blank process-lifecycle correlation id without NUL characters
 * @param timestamp event timestamp
 * @param scenario non-blank scenario name without NUL characters
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
     * <p>Each call chooses a new id. Use the canonical constructor with an explicit shared id when manually creating
     * several events belonging to one lifecycle.
     *
     * @param type event type
     * @param timestamp event timestamp
     * @param scenario scenario that emitted the event
     * @param command redaction-friendly command echo
     * @param attributes structured event attributes
     * @throws IllegalArgumentException if {@code scenario} is blank or contains NUL, or attributes violate the event schema
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
     * @param runId non-blank process-lifecycle correlation id without NUL characters
     * @param timestamp event timestamp
     * @param scenario non-blank scenario name without NUL characters
     * @param command redaction-friendly command echo
     * @param attributes structured event attributes
     * @throws IllegalArgumentException if {@code runId} or {@code scenario} is blank or contains NUL, or attributes
     *     violate the event schema
     */
    public DiagnosticEvent {
        Objects.requireNonNull(type, "type");
        CommandValidation.requireText(runId, "runId");
        Objects.requireNonNull(timestamp, "timestamp");
        CommandValidation.requireText(scenario, "scenario");
        Objects.requireNonNull(command, "command");
        attributes = DiagnosticAttributeSchema.validate(type, attributes);
    }
}

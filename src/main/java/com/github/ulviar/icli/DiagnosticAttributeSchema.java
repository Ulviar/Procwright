package com.github.ulviar.icli;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DiagnosticAttributeSchema {

    private static final Map<DiagnosticEventType, Set<String>> ALLOWED_ATTRIBUTES = allowedAttributes();

    private DiagnosticAttributeSchema() {}

    static Map<String, String> validate(DiagnosticEventType type, Map<String, String> attributes) {
        Objects.requireNonNull(type, "type");
        LinkedHashMap<String, String> snapshot = new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes"));
        Set<String> allowed = ALLOWED_ATTRIBUTES.get(type);
        if (allowed == null) {
            throw new IllegalArgumentException("Unknown diagnostic event type: " + type);
        }
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            CommandSpec.requireText(entry.getKey(), "attributeName");
            Objects.requireNonNull(entry.getValue(), "attributeValue");
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Attribute " + entry.getKey() + " is not allowed for diagnostic event " + type);
            }
        }
        validateShape(type, snapshot);
        return Map.copyOf(snapshot);
    }

    static Map<DiagnosticEventType, Set<String>> allowedAttributesByType() {
        return ALLOWED_ATTRIBUTES;
    }

    private static Map<DiagnosticEventType, Set<String>> allowedAttributes() {
        EnumMap<DiagnosticEventType, Set<String>> schema = new EnumMap<>(DiagnosticEventType.class);
        schema.put(DiagnosticEventType.COMMAND_PREPARED, Set.of());
        schema.put(DiagnosticEventType.PROCESS_STARTED, Set.of("pid"));
        schema.put(DiagnosticEventType.OUTPUT_TRUNCATED, Set.of("source", "limitBytes", "limitChars"));
        schema.put(DiagnosticEventType.TIMEOUT_REACHED, Set.of());
        schema.put(DiagnosticEventType.SHUTDOWN_REQUESTED, Set.of("reason"));
        schema.put(DiagnosticEventType.LISTENER_FAILED, Set.of());
        schema.put(DiagnosticEventType.PROCESS_EXITED, Set.of("timedOut", "exitCode"));
        schema.put(DiagnosticEventType.PROCESS_FAILED, Set.of("error"));
        if (schema.keySet().size() != DiagnosticEventType.values().length) {
            throw new IllegalStateException("Diagnostic attribute schema must cover every event type");
        }
        return Map.copyOf(schema);
    }

    private static void validateShape(DiagnosticEventType type, Map<String, String> attributes) {
        switch (type) {
            case COMMAND_PREPARED, TIMEOUT_REACHED, LISTENER_FAILED -> requireOnly(type, attributes);
            case PROCESS_STARTED -> {
                requireOnly(type, attributes, "pid");
                requireLong("pid", attributes.get("pid"));
            }
            case OUTPUT_TRUNCATED -> {
                if (!(attributes.keySet().equals(Set.of("source", "limitBytes"))
                        || attributes.keySet().equals(Set.of("source", "limitChars")))) {
                    throw invalid(type, "requires source plus exactly one limit attribute");
                }
                requireSource(attributes.get("source"));
                if (attributes.containsKey("limitBytes")) {
                    requirePositiveInt("limitBytes", attributes.get("limitBytes"));
                }
                if (attributes.containsKey("limitChars")) {
                    requirePositiveInt("limitChars", attributes.get("limitChars"));
                }
            }
            case SHUTDOWN_REQUESTED -> {
                requireOnly(type, attributes, "reason");
                requireReason(attributes.get("reason"));
            }
            case PROCESS_EXITED -> {
                if (!attributes.keySet().contains("timedOut")
                        || !(attributes.keySet().equals(Set.of("timedOut"))
                                || attributes.keySet().equals(Set.of("timedOut", "exitCode")))) {
                    throw invalid(type, "requires timedOut and optional exitCode");
                }
                requireBoolean("timedOut", attributes.get("timedOut"));
                if (attributes.containsKey("exitCode")) {
                    requireInt("exitCode", attributes.get("exitCode"));
                }
            }
            case PROCESS_FAILED -> {
                requireOnly(type, attributes, "error");
                requireClassName("error", attributes.get("error"));
            }
        }
    }

    private static void requireOnly(DiagnosticEventType type, Map<String, String> attributes, String... required) {
        Set<String> requiredSet = Set.of(required);
        if (!attributes.keySet().equals(requiredSet)) {
            throw invalid(type, "requires attributes " + requiredSet);
        }
    }

    private static void requireLong(String name, String value) {
        try {
            Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalidAttribute(name, "must be a long integer");
        }
    }

    private static void requireInt(String name, String value) {
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidAttribute(name, "must be an integer");
        }
    }

    private static void requirePositiveInt(String name, String value) {
        try {
            if (Integer.parseInt(value) <= 0) {
                throw invalidAttribute(name, "must be positive");
            }
        } catch (NumberFormatException exception) {
            throw invalidAttribute(name, "must be a positive integer");
        }
    }

    private static void requireBoolean(String name, String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw invalidAttribute(name, "must be true or false");
        }
    }

    private static void requireReason(String value) {
        if (!Set.of("timeout", "close", "failure", "idleTimeout").contains(value)) {
            throw invalidAttribute("reason", "must be a known shutdown reason");
        }
    }

    private static void requireSource(String value) {
        if (!Set.of("stdout", "stderr", "diagnostics").contains(value)) {
            throw invalidAttribute("source", "must be stdout, stderr, or diagnostics");
        }
    }

    private static void requireClassName(String name, String value) {
        if (!value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw invalidAttribute(name, "must be a class name");
        }
    }

    private static IllegalArgumentException invalid(DiagnosticEventType type, String message) {
        return new IllegalArgumentException("Diagnostic event " + type + " " + message);
    }

    private static IllegalArgumentException invalidAttribute(String name, String message) {
        return new IllegalArgumentException("Diagnostic attribute " + name + " " + message);
    }
}

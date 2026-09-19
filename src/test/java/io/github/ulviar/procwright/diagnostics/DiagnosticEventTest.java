/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

final class DiagnosticEventTest {

    @ParameterizedTest
    @EnumSource(DiagnosticEventType.class)
    void everyEventAcceptsItsAttributesAndRejectsUnknownFields(DiagnosticEventType type) {
        Map<String, String> attributes =
                switch (type) {
                    case COMMAND_PREPARED, TIMEOUT_REACHED, LISTENER_FAILED -> Map.of();
                    case PROCESS_STARTED -> Map.of("pid", "42");
                    case OUTPUT_TRUNCATED -> Map.of("source", "stdout", "limitBytes", "1024");
                    case SHUTDOWN_REQUESTED -> Map.of("reason", "interrupted");
                    case PROCESS_EXITED -> Map.of("timedOut", "false");
                    case PROCESS_FAILED -> Map.of("error", "java.io.IOException");
                };
        assertEquals(attributes, event(type, attributes).attributes());

        Map<String, String> extraField = new HashMap<>(attributes);
        extraField.put("rawCommand", "secret");
        assertThrows(IllegalArgumentException.class, () -> event(type, extraField));
        for (String required : attributes.keySet()) {
            Map<String, String> missingField = new HashMap<>(attributes);
            missingField.remove(required);
            assertThrows(IllegalArgumentException.class, () -> event(type, missingField));
        }
    }

    @Test
    void optionalExitCodeAndCharacterLimitsAreAccepted() {
        Map<String, String> exit = Map.of("timedOut", "true", "exitCode", "-1");
        Map<String, String> truncation = Map.of("source", "diagnostics", "limitChars", "16");
        assertEquals(exit, event(DiagnosticEventType.PROCESS_EXITED, exit).attributes());
        assertEquals(
                truncation,
                event(DiagnosticEventType.OUTPUT_TRUNCATED, truncation).attributes());
    }

    @ParameterizedTest
    @MethodSource("invalidAttributes")
    void invalidValuesAndAmbiguousShapesAreRejected(DiagnosticEventType type, Map<String, String> attributes) {
        assertThrows(IllegalArgumentException.class, () -> event(type, attributes));
    }

    static Stream<Arguments> invalidAttributes() {
        return Stream.of(
                Arguments.of(DiagnosticEventType.PROCESS_STARTED, Map.of("pid", "not-a-pid")),
                Arguments.of(DiagnosticEventType.PROCESS_EXITED, Map.of("timedOut", "yes")),
                Arguments.of(DiagnosticEventType.PROCESS_EXITED, Map.of("timedOut", "false", "exitCode", "1.5")),
                Arguments.of(DiagnosticEventType.SHUTDOWN_REQUESTED, Map.of("reason", "unknown")),
                Arguments.of(DiagnosticEventType.PROCESS_FAILED, Map.of("error", "exception: secret message")),
                Arguments.of(DiagnosticEventType.OUTPUT_TRUNCATED, Map.of("source", "unknown", "limitBytes", "1")),
                Arguments.of(DiagnosticEventType.OUTPUT_TRUNCATED, Map.of("source", "stdout", "limitBytes", "0")),
                Arguments.of(DiagnosticEventType.OUTPUT_TRUNCATED, Map.of("source", "stderr", "limitChars", "-1")),
                Arguments.of(
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        Map.of("source", "stdout", "limitBytes", "1", "limitChars", "1")));
    }

    @Test
    void attributesAreAnImmutableSnapshotOfCallerData() {
        Map<String, String> input = new HashMap<>(Map.of("pid", "42"));
        DiagnosticEvent event = event(DiagnosticEventType.PROCESS_STARTED, input);
        input.put("pid", "invalid");
        input.put("secret", "not retained");

        assertEquals(Map.of("pid", "42"), event.attributes());
        assertThrows(
                UnsupportedOperationException.class, () -> event.attributes().put("pid", "43"));
    }

    @Test
    void nullAttributeValuesAreRejected() {
        Map<String, String> input = new HashMap<>();
        input.put("pid", null);
        assertThrows(NullPointerException.class, () -> event(DiagnosticEventType.PROCESS_STARTED, input));
    }

    private static DiagnosticEvent event(DiagnosticEventType type, Map<String, String> attributes) {
        return new DiagnosticEvent(type, "run-id", Instant.EPOCH, "run", CommandEcho.empty(), attributes);
    }
}

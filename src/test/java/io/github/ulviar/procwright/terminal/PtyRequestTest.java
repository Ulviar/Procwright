package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PtyRequestTest {

    @Test
    void snapshotsCommandAndEnvironment() {
        ArrayList<String> command = new ArrayList<>(List.of("sh", "-c", "true"));
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("TERM", "dumb");

        PtyRequest request = new PtyRequest(
                command, Optional.of(Path.of("work")), EnvironmentPolicy.CLEAN, environment, new TerminalSize(100, 40));

        command.add("ignored");
        environment.put("IGNORED", "true");

        assertEquals(List.of("sh", "-c", "true"), request.command());
        assertEquals("dumb", request.environment().get("TERM"));
        assertFalse(request.environment().containsKey("IGNORED"));
        assertThrows(
                UnsupportedOperationException.class, () -> request.command().add("later"));
        assertThrows(
                UnsupportedOperationException.class, () -> request.environment().put("LATER", "true"));
    }

    @Test
    void rejectsInvalidComponents() {
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(
                        null, Optional.empty(), EnvironmentPolicy.INHERIT, Map.of(), new TerminalSize(80, 24)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PtyRequest(
                        List.of(), Optional.empty(), EnvironmentPolicy.INHERIT, Map.of(), new TerminalSize(80, 24)));
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(
                        commandWithNullElement(),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        Map.of(),
                        new TerminalSize(80, 24)));
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(
                        List.of("sh"), null, EnvironmentPolicy.INHERIT, Map.of(), new TerminalSize(80, 24)));
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(List.of("sh"), Optional.empty(), null, Map.of(), new TerminalSize(80, 24)));
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(
                        List.of("sh"), Optional.empty(), EnvironmentPolicy.INHERIT, null, new TerminalSize(80, 24)));
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(
                        List.of("sh"),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        environmentWithNullKey(),
                        new TerminalSize(80, 24)));
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(
                        List.of("sh"),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        environmentWithNullValue(),
                        new TerminalSize(80, 24)));
        assertThrows(
                NullPointerException.class,
                () -> new PtyRequest(List.of("sh"), Optional.empty(), EnvironmentPolicy.INHERIT, Map.of(), null));
    }

    @Test
    void unavailableProviderRejectsStartsWithDiagnosticReason() {
        PtyProvider provider = PtyProvider.unavailable("no pty");

        assertFalse(provider.available());
        assertEquals("no pty", provider.description());
        assertThrows(CommandExecutionException.class, () -> provider.start(validRequest()));
        assertThrows(NullPointerException.class, () -> provider.start(null));
        assertThrows(IllegalArgumentException.class, () -> PtyProvider.unavailable(" "));
    }

    private static PtyRequest validRequest() {
        return new PtyRequest(
                List.of("sh"), Optional.empty(), EnvironmentPolicy.INHERIT, Map.of(), new TerminalSize(80, 24));
    }

    private static List<String> commandWithNullElement() {
        ArrayList<String> command = new ArrayList<>();
        command.add(null);
        return command;
    }

    private static Map<String, String> environmentWithNullKey() {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put(null, "value");
        return environment;
    }

    private static Map<String, String> environmentWithNullValue() {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("NAME", null);
        return environment;
    }
}

package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CommandServiceTest {

    @Test
    void createsServiceForExecutableWithDefaultOptions() {
        CommandService service = CommandService.forCommand("git");

        assertEquals("git", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
        assertEquals(SessionOptions.defaults(), service.sessionOptions());
    }

    @Test
    void runValidatesConfigurationCallback() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.run(null));
    }

    @Test
    void interactiveValidatesConfigurationCallback() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.interactive(null));
    }

    @Test
    void createsServiceForShellCommandWithDefaultOptions() {
        CommandService service = CommandService.forShellCommand("echo hello");

        assertEquals("echo hello", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
    }
}

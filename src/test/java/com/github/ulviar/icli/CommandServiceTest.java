package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSessionOptions;
import com.github.ulviar.icli.session.PooledProtocolSessionOptions;
import com.github.ulviar.icli.session.SessionOptions;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class CommandServiceTest {

    @Test
    void commandServiceDoesNotExposePositionalConstructors() {
        assertEquals(0, CommandService.class.getConstructors().length);
    }

    @Test
    void commandServiceDoesNotExposeRootPoolShortcuts() {
        assertEquals(
                0,
                Arrays.stream(CommandService.class.getMethods())
                        .filter(method -> method.getName().equals("pooled")
                                || method.getName().equals("pooledProtocol"))
                        .count());
    }

    @Test
    void createsServiceForExecutableWithDefaultOptions() {
        CommandService service = CommandService.forCommand("git");

        assertEquals("git", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
        assertEquals(SessionOptions.defaults(), service.sessionOptions());
        assertEquals(LineSessionOptions.defaults(), service.lineSessionOptions());
        assertEquals(PooledLineSessionOptions.defaults(), service.pooledLineSessionOptions());
        assertEquals(PooledProtocolSessionOptions.defaults(), service.pooledProtocolSessionOptions());
    }

    @Test
    void icliCommandUsesCommandServiceDefaults() {
        CommandService service = Icli.command("git");

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
    void lineSessionValidatesConfigurationCallback() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.lineSession(null));
    }

    @Test
    void pooledValidatesConfigurationCallback() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.pooled(null));
    }

    @Test
    void createsServiceForShellCommandWithDefaultOptions() {
        CommandService service = CommandService.forShellCommand("echo hello");

        assertEquals("echo hello", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
    }
}

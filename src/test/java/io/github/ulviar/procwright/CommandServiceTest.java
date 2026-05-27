package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.PooledLineSessionOptions;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.SessionOptions;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
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
    void commandServiceDoesNotExposeDirectConsumerShortcuts() {
        Set<String> shortcutNames = Set.of("run", "interactive", "lineSession", "protocolSession", "listen");

        assertEquals(
                0,
                Arrays.stream(CommandService.class.getMethods())
                        .filter(method -> shortcutNames.contains(method.getName()))
                        .filter(method ->
                                Arrays.stream(method.getParameterTypes()).anyMatch(type -> type.equals(Consumer.class)))
                        .count());
    }

    @Test
    void createsServiceForExecutableWithDefaultOptions() {
        CommandService service = CommandService.forCommand("git");

        assertEquals("git", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
        assertSame(SessionOptions.defaults(), service.sessionOptions());
        assertSame(LineSessionOptions.defaults(), service.lineSessionOptions());
        assertSame(PooledLineSessionOptions.defaults(), service.pooledLineSessionOptions());
        assertEquals(PooledProtocolSessionOptions.defaults(), service.pooledProtocolSessionOptions());
    }

    @Test
    void procwrightCommandUsesCommandServiceDefaults() {
        CommandService service = Procwright.command("git");

        assertEquals("git", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
        assertSame(SessionOptions.defaults(), service.sessionOptions());
    }

    @Test
    void runValidatesConfigurationCallback() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.run().configuredBy(null));
    }

    @Test
    void interactiveValidatesConfigurationCallback() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.interactive().configuredBy(null));
    }

    @Test
    void lineSessionValidatesConfigurationCallback() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.lineSession().configuredBy(null));
    }

    @Test
    void serviceDefaultReplacementsValidateNulls() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.withRunOptions(null));
        assertThrows(NullPointerException.class, () -> service.withSessionOptions(null));
        assertThrows(NullPointerException.class, () -> service.withLineSessionOptions(null));
        assertThrows(NullPointerException.class, () -> service.withStreamOptions(null));
        assertThrows(NullPointerException.class, () -> service.withPooledLineSessionOptions(null));
        assertThrows(NullPointerException.class, () -> service.withProtocolSessionOptions(null));
        assertThrows(NullPointerException.class, () -> service.withPooledProtocolSessionOptions(null));
        assertThrows(NullPointerException.class, () -> service.withDiagnostics(null));
    }

    @Test
    void createsServiceForShellCommandWithDefaultOptions() {
        CommandService service = CommandService.forShellCommand("echo hello");

        assertEquals("echo hello", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
    }
}

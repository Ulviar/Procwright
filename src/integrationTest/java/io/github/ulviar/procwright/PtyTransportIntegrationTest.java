package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionOptions;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PtyTransportIntegrationTest {

    @Test
    void requiredTerminalFailsWhenProviderIsUnavailable() {
        CommandService service = new CommandService(
                CommandSpec.of("never-started"),
                RunOptions.defaults(),
                SessionOptions.defaults().withPtyProvider(PtyProvider.unavailable()));

        assertThrows(
                CommandExecutionException.class,
                () -> service.interactive(
                        call -> call.terminal(TerminalPolicy.REQUIRED).args("-c", "echo never")));
    }

    @Test
    void autoTerminalFallsBackToPipesWhenProviderIsUnavailable() throws Exception {
        assumePosixShellAvailable();

        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults().withPtyProvider(PtyProvider.unavailable()));

        try (Session session = service.interactive(call -> call.terminal(TerminalPolicy.AUTO)
                        .args("-c", "if [ -t 0 ]; then echo mode:tty; else echo mode:pipe; fi"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:pipe", stdout.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void disabledTerminalUsesPipesEvenWhenProviderIsAvailable() throws Exception {
        assumePosixShellAvailable();

        PtyProvider forbiddenProvider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "provider that must not be called";
            }

            @Override
            public Process start(PtyRequest request) {
                throw new AssertionError("disabled terminal policy must not call PTY provider");
            }
        };
        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults().withPtyProvider(forbiddenProvider));

        try (Session session = service.interactive(
                        call -> call.args("-c", "if [ -t 0 ]; then echo mode:tty; else echo mode:pipe; fi"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:pipe", stdout.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void autoTerminalUsesSystemPtyWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults().withPtyProvider(PtyProvider.system()));

        try (Session session = service.interactive(call -> call.terminal(TerminalPolicy.AUTO)
                        .args("-c", "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:tty", readUntil(stdout, "mode:"));
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void requiredTerminalUsesSystemPtyWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults()
                        .withPtyProvider(PtyProvider.system())
                        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(50), Duration.ofMillis(500))));

        try (Session session = service.interactive(
                        call -> call.terminal(TerminalPolicy.REQUIRED)
                                .args(
                                        "-c",
                                        "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi; read line; echo got:$line"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:tty", readUntil(stdout, "mode:"));

            session.sendLine("hello");

            assertEquals("got:hello", readUntil(stdout, "got:"));
        }
    }

    @Test
    void requiredTerminalReceivesConfiguredSizeWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults()
                        .withPtyProvider(PtyProvider.system())
                        .withTerminalSize(new TerminalSize(100, 40)));

        try (Session session = service.interactive(call ->
                        call.terminal(TerminalPolicy.REQUIRED).args("-c", "echo size:${COLUMNS}x${LINES}; stty size"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("size:100x40", readUntil(stdout, "size:"));
            assertEquals("40 100", readUntil(stdout, "40 "));
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void lineSessionCanUseRequiredTerminalWithEchoAwareDecoderWhenAvailable() {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        ResponseDecoder responseOnly = reader -> {
            while (true) {
                String line = reader.readLine();
                if (line.startsWith("response:")) {
                    return List.of(line);
                }
            }
        };
        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults().withPtyProvider(PtyProvider.system()),
                LineSessionOptions.defaults().withResponseDecoder(responseOnly));

        try (LineSession session = service.lineSession(call -> call.terminal(TerminalPolicy.REQUIRED)
                .args("-c", "while IFS= read -r line; do printf 'response:%s\\n' \"$line\"; done"))) {
            assertEquals("response:alpha", session.request("alpha").text());
            assertEquals("response:beta", session.request("beta").text());
            assertTrue(session.transcript().text().contains("stdout: alpha"));
        }
    }

    @Test
    void terminalSignalInterruptReachesForegroundCommandWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults()
                        .withPtyProvider(PtyProvider.system())
                        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(50), Duration.ofMillis(500))));

        try (Session session = service.interactive(call -> call.terminal(TerminalPolicy.REQUIRED)
                        .args("-c", "trap 'echo interrupted; exit 0' INT; echo ready; while :; do sleep 1; done"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("ready", readUntil(stdout, "ready"));

            session.sendSignal(TerminalSignal.INTERRUPT);

            assertTrue(readUntilContaining(stdout, "interrupted").contains("interrupted"));
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    private static String readUntil(BufferedReader reader, String prefix) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        String line;
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("missing line with prefix " + prefix);
    }

    private static String readUntilContaining(BufferedReader reader, String text) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        String line;
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
            if (line.contains(text)) {
                return line;
            }
        }
        throw new AssertionError("missing line containing " + text);
    }

    private static void assumePosixShellAvailable() {
        assumeFalse(isWindows(), "POSIX shell fixture requires sh");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}

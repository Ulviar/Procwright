/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PtyTransportIntegrationTestSupport.assumePosixShellAvailable;
import static io.github.ulviar.procwright.PtyTransportIntegrationTestSupport.readUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class PtyTransportPolicyIntegrationTest {

    @Test
    void systemPtyProviderIsAvailableWhenTheBuildRequiresIt() {
        if (Boolean.getBoolean("procwright.requireSystemPty")) {
            assertTrue(
                    PtyProvider.system().available(),
                    () -> "required system PTY provider is unavailable: "
                            + PtyProvider.system().description());
        }
    }

    @Test
    void requiredTerminalFailsWhenProviderIsUnavailable() {
        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> Procwright.command(CommandSpec.of("never-started"))
                        .interactive()
                        .withPtyProvider(PtyProvider.unavailable())
                        .withTerminal(TerminalPolicy.REQUIRED)
                        .withArgs("-c", "echo never")
                        .open());

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
    }

    @Test
    void autoTerminalFallsBackToPipesWhenProviderIsUnavailable() throws Exception {
        assumePosixShellAvailable();

        InteractiveScenario.Draft scenario =
                Procwright.command(CommandSpec.of("sh")).interactive().withPtyProvider(PtyProvider.unavailable());

        try (Session session = scenario.withTerminal(TerminalPolicy.AUTO)
                        .withArgs(
                                "-c",
                                "if [ -t 0 ]; then echo mode:tty; else echo mode:pipe; fi; read release; ["
                                        + " \"$release\" = release ]")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:pipe", readUntil(session, stdout, "mode:"));
            session.sendLine("release");
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
        InteractiveScenario.Draft scenario =
                Procwright.command(CommandSpec.of("sh")).interactive().withPtyProvider(forbiddenProvider);

        try (Session session = scenario.withTerminal(TerminalPolicy.DISABLED)
                        .withArgs(
                                "-c",
                                "if [ -t 0 ]; then echo mode:tty; else echo mode:pipe; fi; read release; ["
                                        + " \"$release\" = release ]")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:pipe", readUntil(session, stdout, "mode:"));
            session.sendLine("release");
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void interactiveTerminalDefaultDoesNotLeakIntoLineOrProtocolWorkers() {
        assumePosixShellAvailable();

        PtyProvider forbiddenProvider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "provider that must require an explicit scenario terminal policy";
            }

            @Override
            public Process start(PtyRequest request) {
                throw new AssertionError("line and protocol workers must not inherit interactive terminal defaults");
            }
        };
        CommandService service = Procwright.command(CommandSpec.of("sh"));
        InteractiveScenario.Draft configuredInteractive =
                service.interactive().withTerminal(TerminalPolicy.REQUIRED).withPtyProvider(forbiddenProvider);
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return readers.stdout().readLine(1024);
            }
        };

        assertTrue(configuredInteractive != service.interactive());

        try (LineSession line = service.lineSession().withArgs("-c", "cat").open();
                PooledLineSession pooledLine = service.lineSession()
                        .withArgs("-c", "cat")
                        .pooled()
                        .withWarmupSize(1)
                        .open();
                ProtocolSession<String, String> protocol = service.protocolSession(adapterFactory)
                        .withArgs("-c", "cat")
                        .open();
                PooledProtocolSession<String, String> pooledProtocol = service.protocolSession(adapterFactory)
                        .withArgs("-c", "cat")
                        .pooled()
                        .withWarmupSize(1)
                        .open()) {
            assertEquals("line", line.request("line").text());
            assertEquals("pooled-line", pooledLine.request("pooled-line").text());
            assertEquals("protocol", protocol.request("protocol"));
            assertEquals("pooled-protocol", pooledProtocol.request("pooled-protocol"));
        }
    }

    @Test
    void autoTerminalUsesSystemPtyWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        InteractiveScenario.Draft scenario =
                Procwright.command(CommandSpec.of("sh")).interactive().withPtyProvider(PtyProvider.system());

        try (Session session = scenario.withTerminal(TerminalPolicy.AUTO)
                        .withArgs("-c", "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:tty", readUntil(session, stdout, "mode:"));
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void requiredTerminalUsesSystemPtyWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        InteractiveScenario.Draft scenario = Procwright.command(CommandSpec.of("sh"))
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(50), Duration.ofMillis(500)));

        try (Session session = scenario.withTerminal(TerminalPolicy.REQUIRED)
                        .withArgs(
                                "-c",
                                "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi; read line; echo"
                                        + " got:$line")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:tty", readUntil(session, stdout, "mode:"));

            session.sendLine("hello");

            assertEquals("got:hello", readUntil(session, stdout, "got:"));
        }
    }
}

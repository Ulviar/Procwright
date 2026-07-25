/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PtyTransportIntegrationTestSupport.assumePosixShellAvailable;
import static io.github.ulviar.procwright.PtyTransportIntegrationTestSupport.readUntil;
import static io.github.ulviar.procwright.PtyTransportIntegrationTestSupport.readUntilContaining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SystemPtyBehaviorIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void requiredSystemPtyPreservesChildExitCodeInSessionAndDiagnostics() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        ExitDiagnostic diagnostic = new ExitDiagnostic();

        InteractiveScenario.Draft scenario = Procwright.command(TestCliSupport.command())
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withTerminal(TerminalPolicy.REQUIRED)
                .withDiagnosticListener(diagnostic::record)
                .withArgs("argv-env-cwd", "--exit-code=37");

        try (Session session = scenario.open()) {
            SessionExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(OptionalInt.of(37), exit.exitCode());
            assertEquals("37", diagnostic.awaitExitCode());
        }
    }

    @Test
    void requiredSystemPtyKeepsMissingAbsoluteExecutableFailureNonzero() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        ExitDiagnostic diagnostic = new ExitDiagnostic();
        Path missingExecutable =
                temporaryDirectory.resolve("missing-absolute-command").toAbsolutePath();

        InteractiveScenario.Draft scenario = Procwright.command(CommandSpec.of(missingExecutable.toString()))
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withTerminal(TerminalPolicy.REQUIRED)
                .withDiagnosticListener(diagnostic::record)
                .withCleanEnvironment();

        try (Session session = scenario.open()) {
            int exitCode = session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow();

            assertTrue(exitCode != 0, "missing PTY command must not be reported as successful");
            assertEquals(Integer.toString(exitCode), diagnostic.awaitExitCode());
        }
    }

    @Test
    void requiredTerminalReceivesConfiguredSizeWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        InteractiveScenario.Draft scenario = Procwright.command(CommandSpec.of("sh"))
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withTerminalSize(new TerminalSize(100, 40));

        try (Session session = scenario.withTerminal(TerminalPolicy.REQUIRED)
                        .withArgs("-c", "echo size:${COLUMNS}x${LINES}; stty size")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("size:100x40", readUntil(session, stdout, "size:"));
            assertEquals("40 100", readUntil(session, stdout, "40 "));
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
        LineSessionScenario.Draft scenario = Procwright.command(CommandSpec.of("sh"))
                .lineSession()
                .withPtyProvider(PtyProvider.system())
                .withResponseDecoder(responseOnly);

        try (LineSession session = scenario.withTerminal(TerminalPolicy.REQUIRED)
                .withArgs("-c", "while IFS= read -r line; do printf 'response:%s\\n' \"$line\"; done")
                .open()) {
            assertEquals("response:alpha", session.request("alpha").text());
            assertEquals("response:beta", session.request("beta").text());
            assertTrue(session.transcript().text().contains("stdout: alpha"));
        }
    }

    @Test
    void terminalSignalInterruptReachesForegroundCommandWhenAvailable() throws Exception {
        assumePosixShellAvailable();
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        InteractiveScenario.Draft scenario = Procwright.command(CommandSpec.of("sh"))
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(50), Duration.ofMillis(500)));

        try (Session session = scenario.withTerminal(TerminalPolicy.REQUIRED)
                        .withArgs("-c", "trap 'echo interrupted; exit 0' INT; echo ready; while :; do sleep 1; done")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("ready", readUntil(session, stdout, "ready"));

            session.sendSignal(TerminalSignal.INTERRUPT);

            assertTrue(readUntilContaining(session, stdout, "interrupted").contains("interrupted"));
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    private static final class ExitDiagnostic {

        private final AtomicReference<DiagnosticEvent> processExited = new AtomicReference<>();
        private final CountDownLatch delivered = new CountDownLatch(1);

        private void record(DiagnosticEvent event) {
            if (event.type() == DiagnosticEventType.PROCESS_EXITED && processExited.compareAndSet(null, event)) {
                delivered.countDown();
            }
        }

        private String awaitExitCode() throws InterruptedException {
            assertTrue(delivered.await(2, TimeUnit.SECONDS), "PROCESS_EXITED diagnostic was not delivered");
            return processExited.get().attributes().get("exitCode");
        }
    }
}

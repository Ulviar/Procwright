/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PtyTransportIntegrationTest {

    @TempDir
    Path temporaryDirectory;

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
                        .withArgs("-c", "if [ -t 0 ]; then echo mode:tty; else echo mode:pipe; fi")
                        .open();
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
        InteractiveScenario.Draft scenario =
                Procwright.command(CommandSpec.of("sh")).interactive().withPtyProvider(forbiddenProvider);

        try (Session session = scenario.withArgs("-c", "if [ -t 0 ]; then echo mode:tty; else echo mode:pipe; fi")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:pipe", stdout.readLine());
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
                                "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi; read line; echo got:$line")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("mode:tty", readUntil(session, stdout, "mode:"));

            session.sendLine("hello");

            assertEquals("got:hello", readUntil(session, stdout, "got:"));
        }
    }

    @Test
    void requiredSystemPtyLaunchesAbsoluteCommandWithoutPathOrShell() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        InteractiveScenario.Draft scenario = Procwright.command(TestCliSupport.command())
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withTerminal(TerminalPolicy.REQUIRED)
                .withCleanEnvironment()
                .withArgs("argv-env-cwd", "--env", "PATH", "--env", "SHELL");

        try (Session session = scenario.open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("env:PATH=<missing>", readUntil(session, stdout, "env:PATH="));
            assertEquals("env:SHELL=<missing>", readUntil(session, stdout, "env:SHELL="));
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void requiredSystemPtyDoesNotUseCallerPathOrShellForItsWrapper() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");

        Path marker = temporaryDirectory.resolve("hostile-wrapper-ran");
        Path hostileShell = temporaryDirectory.resolve("sh");
        Path hostileStty = temporaryDirectory.resolve("stty");
        writeHostileExecutable(hostileShell, marker);
        writeHostileExecutable(hostileStty, marker);

        InteractiveScenario.Draft scenario = Procwright.command(TestCliSupport.command())
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withTerminal(TerminalPolicy.REQUIRED)
                .withCleanEnvironment()
                .withEnvironment("PATH", temporaryDirectory.toString())
                .withEnvironment("SHELL", hostileShell.toString())
                .withArgs("argv-env-cwd", "--env", "PATH", "--env", "SHELL");

        try (Session session = scenario.open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("env:PATH=" + temporaryDirectory, readUntil(session, stdout, "env:PATH="));
            assertEquals("env:SHELL=" + hostileShell, readUntil(session, stdout, "env:SHELL="));
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
        assertFalse(Files.exists(marker), "PTY wrapper executed the caller-controlled shell");
    }

    @Test
    void publicSystemProviderDoesNotExposeChildEnvironmentToItsTrustedWrapper() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        Path marker =
                temporaryDirectory.resolve("wrapper-environment-injection").toAbsolutePath();
        Path bashEnvironment = temporaryDirectory.resolve("malicious-bash-env");
        Path posixEnvironment = temporaryDirectory.resolve("malicious-posix-env");
        String sourcedCommand = "/usr/bin/touch " + shellQuote(marker.toString()) + "\n";
        Files.writeString(bashEnvironment, sourcedCommand, StandardCharsets.UTF_8);
        Files.writeString(posixEnvironment, sourcedCommand, StandardCharsets.UTF_8);
        String secret = "PTY-WRAPPER-SECRET-7d58f5";
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("SHELLOPTS", "xtrace");
        environment.put("PS4", "$(/usr/bin/touch " + shellQuote(marker.toString()) + ")" + secret);
        environment.put("BASH_ENV", bashEnvironment.toString());
        environment.put("ENV", posixEnvironment.toString());
        environment.put("PATH", temporaryDirectory.toString());
        environment.put("IFS", " hostile-ifs \t\n");
        environment.put("CDPATH", temporaryDirectory.toString());
        environment.put("LD_PRELOAD", "");
        environment.put("DYLD_INSERT_LIBRARIES", "");
        PtyRequest request = new PtyRequest(
                List.of("/usr/bin/true"),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                environment,
                new TerminalSize(80, 24));

        Process process = PtyProvider.system().start(request);
        byte[] stdout;
        byte[] stderr;
        try (java.io.InputStream processStdout = process.getInputStream();
                java.io.InputStream processStderr = process.getErrorStream()) {
            assertTrue(process.waitFor(3, TimeUnit.SECONDS), "PTY child did not exit");
            stdout = processStdout.readAllBytes();
            stderr = processStderr.readAllBytes();
            assertEquals(0, process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        String combined = new String(stdout, StandardCharsets.UTF_8) + new String(stderr, StandardCharsets.UTF_8);
        assertFalse(Files.exists(marker), "wrapper interpreted child-controlled shell variables");
        assertFalse(combined.contains("PROCWRIGHT_PTY"), "wrapper handshake leaked into child output");
        assertFalse(combined.contains(secret), "wrapper output exposed a child environment secret");
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                assertFalse(combined.contains(entry.getValue()), "wrapper output exposed " + entry.getKey());
            }
        }
    }

    @Test
    void publicSystemProviderPreservesExactCleanChildEnvironmentAndAsciiArgv() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        String exactValue = " spaces ' quotes\nline=two=ASCII\n\n";
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("EXACT", exactValue);
        environment.put("EMPTY", "");
        environment.put("WITH_EQUALS", "odd=value=again");
        environment.put("SHELLOPTS", "xtrace");
        environment.put("PS4", "$(must-not-run)");
        List<String> hostileArguments = List.of("", "a=b", "line\nbreak\n", "'\"$()`;*", "ascii-only");

        PtyProbeOutput probe = runEnvironmentProbe(environment, hostileArguments, EnvironmentPolicy.CLEAN);

        for (Map.Entry<String, String> entry : environment.entrySet()) {
            assertEquals(entry.getValue(), probe.environment().get(entry.getKey()), entry.getKey());
        }
        assertEquals("xterm-256color", probe.environment().get("TERM"));
        assertEquals("91", probe.environment().get("COLUMNS"));
        assertEquals("37", probe.environment().get("LINES"));
        assertEquals("<missing>", probe.environment().get("PATH"));
        assertEquals("<missing>", probe.environment().get("SHELL"));
        assertEquals(hostileArguments, probe.arguments());
        assertEquals(temporaryDirectory.toRealPath().toString(), probe.workingDirectory());
    }

    @Test
    void publicSystemProviderPreservesUnicodeWithInheritedLocale() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        String exactValue = "Ж юникод";
        List<String> arguments = List.of("Ж", "юникод");
        Charset nativeCharset = Charset.forName(
                System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));
        assumeTrue(
                nativeCharset.newEncoder().canEncode(exactValue),
                () -> "native charset cannot represent the Unicode fixture: " + nativeCharset);
        Map<String, String> environment = Map.of("UNICODE", exactValue);

        PtyProbeOutput probe = runEnvironmentProbe(environment, arguments, EnvironmentPolicy.INHERIT);

        assertEquals(exactValue, probe.environment().get("UNICODE"));
        assertEquals(arguments, probe.arguments());
        assertEquals(temporaryDirectory.toRealPath().toString(), probe.workingDirectory());
    }

    @Test
    void publicSystemProviderRejectsAssignmentLikeExecutableWithoutDisclosingIt() {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        String executable = temporaryDirectory.resolve("secret=target-93f761").toString();
        String argument = "secret-argument-93f761";
        String environmentValue = "secret-environment-93f761";
        PtyRequest request = new PtyRequest(
                List.of(executable, argument),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                Map.of("SECRET", environmentValue),
                new TerminalSize(80, 24));

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class, () -> PtyProvider.system().start(request));

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage());
            assertFalse(message.contains(executable));
            assertFalse(message.contains(argument));
            assertFalse(message.contains(environmentValue));
        }
    }

    @Test
    void targetStdinBeginsAfterTheStartedHandshakeFrame() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        byte[] targetInput = "post-started-input\n".getBytes(StandardCharsets.UTF_8);
        String bootstrapSecret = "BOOTSTRAP-FRAME-SECRET-2b8f6d";
        PtyRequest request = new PtyRequest(
                List.of(
                        javaExecutable(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        PtyEnvironmentProbe.class.getName(),
                        "--stdin-bytes",
                        Integer.toString(targetInput.length)),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                Map.of("BOOTSTRAP_SECRET", bootstrapSecret),
                new TerminalSize(80, 24));

        Process process = PtyProvider.system().start(request);
        String output;
        try {
            process.getOutputStream().write(targetInput);
            process.getOutputStream().flush();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "stdin probe did not exit");
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        assertTrue(output.contains("stdin:" + Base64.getEncoder().encodeToString(targetInput)), output);
        assertFalse(output.contains("PROCWRIGHT_PTY"));
        assertFalse(output.contains(bootstrapSecret), "bootstrap payload remained in target input or terminal echo");
    }

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
    void requiredSystemPtyPreservesAdversarialArgvWithoutShellInjection() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        Path marker = temporaryDirectory.resolve("argv injection marker").toAbsolutePath();
        String markerCommand = "/usr/bin/touch " + shellQuote(marker.toString());
        List<String> adversarialArguments = List.of(
                "",
                "space and ' single quote",
                "semicolon;value",
                "$(" + markerCommand + ")",
                "`" + markerCommand + "`",
                "*",
                "line one\nline two");
        java.util.ArrayList<String> commandArguments = new java.util.ArrayList<>();
        commandArguments.add("argv-env-cwd");
        commandArguments.add("--");
        commandArguments.addAll(adversarialArguments);

        InteractiveScenario.Draft scenario = Procwright.command(TestCliSupport.command())
                .interactive()
                .withPtyProvider(PtyProvider.system())
                .withTerminal(TerminalPolicy.REQUIRED)
                .withCleanEnvironment()
                .withArgs(commandArguments);

        String output;
        try (Session session = scenario.open()) {
            output = new String(session.stdout().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }

        String expectedArgv = "argv:" + String.join("|", adversarialArguments) + "\n";
        assertTrue(output.contains(expectedArgv), () -> "PTY changed argv; output was: " + output);
        assertFalse(Files.exists(marker), "PTY wrapper interpreted a command-like argv value");
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

    private static String readUntil(Session session, BufferedReader reader, String prefix) throws Exception {
        return readUntilMatch(session, reader, line -> line.startsWith(prefix), "line with prefix " + prefix);
    }

    private static String readUntilContaining(Session session, BufferedReader reader, String text) throws Exception {
        return readUntilMatch(session, reader, line -> line.contains(text), "line containing " + text);
    }

    private PtyProbeOutput runEnvironmentProbe(
            Map<String, String> environment, List<String> arguments, EnvironmentPolicy environmentPolicy)
            throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(PtyEnvironmentProbe.class.getName());
        command.addAll(environment.keySet());
        command.add("--");
        command.addAll(arguments);
        PtyRequest request = new PtyRequest(
                command, Optional.of(temporaryDirectory), environmentPolicy, environment, new TerminalSize(91, 37));

        Process process = PtyProvider.system().start(request);
        String output;
        try (java.io.InputStream stdout = process.getInputStream()) {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "PTY environment probe did not exit");
            output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        assertFalse(output.contains("PROCWRIGHT_PTY"), "wrapper handshake leaked into child output");
        return PtyProbeOutput.parse(output);
    }

    private static String readUntilMatch(
            Session session, BufferedReader reader, java.util.function.Predicate<String> matcher, String description)
            throws Exception {
        // The deadline must preempt a blocked readLine(), so the read runs on a worker thread and
        // a timeout closes the session to unblock it (mirrors ProcessStressTest.readUntil).
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "procwright-pty-read-until");
            thread.setDaemon(true);
            return thread;
        });
        try {
            java.util.concurrent.Future<String> match = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (matcher.test(line)) {
                        return line;
                    }
                }
                throw new AssertionError("missing " + description);
            });
            try {
                return match.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException exception) {
                session.close();
                match.cancel(true);
                throw new AssertionError("timed out waiting for " + description, exception);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static void assumePosixShellAvailable() {
        assumeFalse(isWindows(), "POSIX shell fixture requires sh");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void writeHostileExecutable(Path executable, Path marker) throws IOException {
        Files.writeString(
                executable,
                "#!/bin/sh\nprintf hostile > " + shellQuote(marker.toString()) + "\nexit 97\n",
                StandardCharsets.UTF_8);
        assertTrue(executable.toFile().setExecutable(true), "could not make hostile fixture executable: " + executable);
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

    private record PtyProbeOutput(Map<String, String> environment, List<String> arguments, String workingDirectory) {

        private static PtyProbeOutput parse(String output) {
            HashMap<String, String> environment = new HashMap<>();
            ArrayList<String> arguments = new ArrayList<>();
            String workingDirectory = null;
            for (String line : output.lines().toList()) {
                if (line.startsWith("environment:")) {
                    String[] fields = line.split(":", 3);
                    environment.put(decode(fields[1]), decode(fields[2]));
                } else if (line.startsWith("argument:")) {
                    arguments.add(decode(line.substring("argument:".length())));
                } else if (line.startsWith("working-directory:")) {
                    workingDirectory = decode(line.substring("working-directory:".length()));
                }
            }
            return new PtyProbeOutput(Map.copyOf(environment), List.copyOf(arguments), workingDirectory);
        }

        private static String decode(String value) {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }
}

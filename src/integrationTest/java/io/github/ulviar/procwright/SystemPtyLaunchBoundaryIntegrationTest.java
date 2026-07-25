/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PtyTransportIntegrationTestSupport.readUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SystemPtyLaunchBoundaryIntegrationTest {

    @TempDir
    Path temporaryDirectory;

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
        ArrayList<String> commandArguments = new ArrayList<>();
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

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
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

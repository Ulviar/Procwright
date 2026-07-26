/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.assertStdoutEquals;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.isWindows;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.normalizeLineEndings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunLaunchConfigurationIntegrationTest {

    @Test
    void directArgvPreservesArgumentsWithoutShellExpansion() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("argv-env-cwd", "--", "hello world", "$PROCWRIGHT_NOT_EXPANDED")
                .execute();

        assertTrue(normalizeLineEndings(result.stdout()).contains("argv:hello world|$PROCWRIGHT_NOT_EXPANDED\n"));
    }

    @Test
    void commandReceivesWorkingDirectoryAndEnvironmentOverride(@TempDir Path workingDirectory) throws IOException {
        CommandResult result = fixtureService()
                .run()
                .withArgs("argv-env-cwd", "--env=PROCWRIGHT_TEST_VALUE")
                .withWorkingDirectory(workingDirectory)
                .withEnvironment("PROCWRIGHT_TEST_VALUE", "configured")
                .execute();

        List<String> stdoutLines = normalizeLineEndings(result.stdout()).lines().toList();
        assertEquals(3, stdoutLines.size());
        assertEquals(
                workingDirectory.toRealPath(),
                Path.of(stdoutLines.get(0).substring("cwd:".length())).toRealPath());
        assertEquals("env:PROCWRIGHT_TEST_VALUE=configured", stdoutLines.get(1));
    }

    @Test
    void cleanEnvironmentDoesNotExposeInheritedValues() {
        String inheritedName = System.getenv().keySet().stream()
                .filter(name -> !"PROCWRIGHT_TEST_VALUE".equals(name))
                .filter(name -> !"SystemRoot".equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("test requires one inherited environment variable"));

        RunScenario.Draft draft = fixtureService()
                .run()
                .withArgs("argv-env-cwd", "--env=" + inheritedName)
                .withCleanEnvironment()
                .withEnvironment("PROCWRIGHT_TEST_VALUE", "configured");
        CommandResult result = putWindowsSystemRootIfNeeded(draft).execute();

        assertTrue(result.succeeded());
        assertTrue(normalizeLineEndings(result.stdout()).contains("env:" + inheritedName + "=<missing>\n"));
    }

    @Test
    void launchFailureDoesNotExposeRawArguments() {
        CommandService service = Procwright.command("procwright-missing-executable-" + System.nanoTime());

        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class,
                () -> service.run().withArgs("--token", "secret-argument").execute());

        assertTrue(exception.getMessage().contains("argumentCount=2"));
        assertFalse(exception.getMessage().contains("--token"));
        assertFalse(exception.getMessage().contains("secret-argument"));
    }

    @Test
    void invalidEnvironmentValueDoesNotExposeRawValue() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> fixtureService()
                .run()
                .withEnvironment("SECRET_VALUE", "hidden\0value")
                .execute());

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void shellModeIsExplicitAndReceivesEnvironmentOverride() {
        CommandService shell =
                Procwright.command(CommandSpec.shell(shellEchoEnvironmentCommand("PROCWRIGHT_SHELL_VALUE")));

        CommandResult result = shell.run()
                .withEnvironment("PROCWRIGHT_SHELL_VALUE", "configured")
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("shell:configured\n", result);
    }

    @Test
    void windowsShellIgnoresPoisonedJvmWorkingDirectoryWithInheritedEnvironment(@TempDir Path directory)
            throws Exception {
        assumeTrue(isWindows(), "Windows executable-search regression requires Windows");

        assertWindowsShellIgnoresPoisonedJvmWorkingDirectory(directory, false);
    }

    @Test
    void windowsShellIgnoresPoisonedJvmWorkingDirectoryWithCleanEnvironment(@TempDir Path directory) throws Exception {
        assumeTrue(isWindows(), "Windows executable-search regression requires Windows");

        assertWindowsShellIgnoresPoisonedJvmWorkingDirectory(directory, true);
    }

    static RunScenario.Draft putWindowsSystemRootIfNeeded(RunScenario.Draft draft) {
        if (!isWindows()) {
            return draft;
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            return draft.withEnvironment("SystemRoot", systemRoot);
        }
        return draft;
    }

    static String shellEchoEnvironmentCommand(String variableName) {
        if (isWindows()) {
            return "echo shell:%" + variableName + "%";
        }
        return "printf 'shell:%s\\n' \"$" + variableName + "\"";
    }

    static void assertWindowsShellIgnoresPoisonedJvmWorkingDirectory(Path workingDirectory, boolean cleanEnvironment)
            throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Files.copy(javaExecutable, workingDirectory.resolve("cmd.exe"));

        ProcessBuilder builder = new ProcessBuilder(
                        javaExecutable.toString(),
                        "-cp",
                        absoluteClasspath(),
                        WindowsShellPoisonProbe.class.getName(),
                        Boolean.toString(cleanEnvironment))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        String originalPath = builder.environment().getOrDefault("PATH", "");
        builder.environment().put("PATH", workingDirectory + File.pathSeparator + originalPath);

        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished, "nested Windows shell probe did not finish");
        assertEquals(0, process.exitValue(), () -> "nested Windows shell probe failed:\n" + output);
    }

    static String absoluteClasspath() {
        return Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(System.getProperty("java.class.path"))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }
}

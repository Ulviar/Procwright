package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ulviar.icli.command.CapturePolicy;
import com.github.ulviar.icli.command.CommandExecutionException;
import com.github.ulviar.icli.command.CommandInvocation;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.command.CommandSpec;
import com.github.ulviar.icli.command.OutputMode;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.command.ShutdownPolicy;
import com.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import com.github.ulviar.icli.internal.ProcessKernel;
import com.github.ulviar.icli.preset.ScenarioPresets;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSessionOptions;
import com.github.ulviar.icli.session.SessionOptions;
import com.github.ulviar.icli.session.StreamOptions;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OneShotExecutionIntegrationTest {

    @Test
    void successfulCommandCapturesStdoutAndExitCode() {
        CommandResult result = fixtureService().run(call -> call.args("stdout", "ready\n"));

        assertTrue(result.succeeded());
        assertEquals(0, result.exitCode().orElseThrow());
        assertStdoutEquals("ready\n", result);
        assertStderrEquals("", result);
    }

    @Test
    void nonZeroCommandCapturesStdoutAndStderr() {
        CommandResult result = fixtureService().run(call -> call.args("stderr-exit", "7", "out\n", "err\n"));

        assertFalse(result.succeeded());
        assertEquals(7, result.exitCode().orElseThrow());
        assertStdoutEquals("out\n", result);
        assertStderrEquals("err\n", result);
    }

    @Test
    void directArgvPreservesArgumentsWithoutShellExpansion() {
        CommandResult result = fixtureService().run(call -> call.args("args", "hello world", "$ICLI_NOT_EXPANDED"));

        assertStdoutEquals("hello world|$ICLI_NOT_EXPANDED\n", result);
    }

    @Test
    void commandReceivesWorkingDirectoryAndEnvironmentOverride(@TempDir Path workingDirectory) throws IOException {
        CommandResult result = fixtureService().run(call -> call.args("cwd-env", "ICLI_TEST_VALUE")
                .workingDirectory(workingDirectory)
                .putEnvironment("ICLI_TEST_VALUE", "configured"));

        List<String> stdoutLines = normalizeLineEndings(result.stdout()).lines().toList();
        assertEquals(2, stdoutLines.size());
        assertEquals(
                workingDirectory.toRealPath(), Path.of(stdoutLines.getFirst()).toRealPath());
        assertEquals("configured", stdoutLines.get(1));
    }

    @Test
    void cleanEnvironmentDoesNotExposeInheritedValues() {
        String inheritedName = System.getenv().keySet().stream()
                .filter(name -> !"ICLI_TEST_VALUE".equals(name))
                .filter(name -> !"SystemRoot".equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("test requires one inherited environment variable"));

        CommandResult result = fixtureService().run(call -> {
            call.args("env-present", inheritedName).cleanEnvironment().putEnvironment("ICLI_TEST_VALUE", "configured");
            putWindowsSystemRootIfNeeded(call);
        });

        assertTrue(result.succeeded());
        assertStdoutEquals("false\n", result);
    }

    @Test
    void largeStdoutIsBoundedAndMarkedAsTruncated() {
        CommandResult result = fixtureService()
                .run(call -> call.args("large-stdout", "64", "x").capture(CapturePolicy.bounded(16)));

        assertStdoutEquals("x".repeat(16), result);
        assertStderrEquals("", result);
        assertTrue(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void largeStderrIsBoundedIndependentlyFromStdout() {
        CommandResult result = fixtureService()
                .run(call -> call.args("large-stderr", "64", "e").capture(CapturePolicy.bounded(16)));

        assertStdoutEquals("done\n", result);
        assertStderrEquals("e".repeat(16), result);
        assertFalse(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void stderrCanBeMergedIntoStdout() {
        CommandResult result = fixtureService()
                .run(call -> call.args("stdout-stderr", "out\n", "err\n").output(OutputMode.MERGED));

        String stdout = normalizeLineEndings(result.stdout());
        assertTrue(stdout.contains("out\n"));
        assertTrue(stdout.contains("err\n"));
        assertTrue(new String(result.stdoutBytes(), StandardCharsets.UTF_8).contains("out\n"));
        assertStderrEquals("", result);
        assertEquals(0, result.stderrBytes().length);
    }

    @Test
    void capturedOutputBytesAreAvailableForBinaryWorkflows() {
        CommandResult result = fixtureService().run(call -> {
            call.args("stdout-bytes");
            ScenarioPresets.binaryOutputCapture(Duration.ofSeconds(1), 16).accept(call);
        });

        assertTrue(result.succeeded());
        assertEquals(java.util.List.of((byte) 0x00, (byte) 0xFF, (byte) 0x41), boxed(result.stdoutBytes()));
        assertEquals(0, result.stderrBytes().length);
    }

    @Test
    void launchFailureDoesNotExposeRawArguments() {
        CommandService service = CommandService.forCommand("icli-missing-executable-" + System.nanoTime());

        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class, () -> service.run(call -> call.args("--token", "secret-argument")));

        assertTrue(exception.getMessage().contains("argumentCount=2"));
        assertFalse(exception.getMessage().contains("--token"));
        assertFalse(exception.getMessage().contains("secret-argument"));
    }

    @Test
    void invalidEnvironmentValueDoesNotExposeRawValue() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> fixtureService()
                .run(call -> call.putEnvironment("SECRET_VALUE", "hidden\0value")));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void hugeTimeoutIsSaturatedInsteadOfOverflowing() {
        CommandResult result =
                fixtureService().run(call -> call.args("stdout", "ok\n").timeout(Duration.ofSeconds(Long.MAX_VALUE)));

        assertTrue(result.succeeded());
        assertStdoutEquals("ok\n", result);
    }

    @Test
    void outputIsDecodedWithConfiguredCharset() {
        CommandResult result =
                fixtureService().run(call -> call.args("stdout", "Привет\n").charset(StandardCharsets.UTF_8));

        assertStdoutEquals("Привет\n", result);
    }

    @Test
    void runScenarioClosesStdinByDefault() {
        CommandResult result = fixtureService().run(call -> call.args("stdin-length"));

        assertTrue(result.succeeded());
        assertStdoutEquals("0\n", result);
    }

    @Test
    void inputOverrideWritesStdinBeforeClosingIt() {
        CommandResult result =
                fixtureService().run(call -> call.args("stdin-echo").input("payload\n"));

        assertTrue(result.succeeded());
        assertStdoutEquals("payload\n", result);
    }

    @Test
    void inputCharsetIsIndependentFromOutputCharset() {
        CommandResult result = fixtureService().run(call -> call.args("stdin-hex")
                .input("é", Charset.forName("ISO-8859-1"))
                .charset(StandardCharsets.US_ASCII));

        assertTrue(result.succeeded());
        assertStdoutEquals("e9\n", result);
    }

    @Test
    void timeoutStopsProcessAndReturnsDiagnosticResult() {
        CommandResult result = fixtureService().run(call -> call.args("sleep", "5000")
                .timeout(Duration.ofMillis(100))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("started\n", result);
    }

    @Test
    void timeoutIsEnforcedWhileWritingInput() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService().run(call -> call.args("ignore-stdin-sleep", "5000")
                .input("x".repeat(8 * 1024 * 1024))
                .timeout(Duration.ofMillis(100))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("started\n", result);
        assertTrue(result.elapsed().compareTo(Duration.ofSeconds(3)) < 0);
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void timeoutCleanupIsBoundedWhileStdoutAndStderrPumpsAreActive() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService().run(call -> call.args("paired-streams-sleep", "20")
                .timeout(Duration.ofMillis(120))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250))));
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertTrue(normalizeLineEndings(result.stdout()).contains("out-pulse"));
        assertTrue(normalizeLineEndings(result.stderr()).contains("err-pulse"));
        assertTrue(result.elapsed().compareTo(Duration.ofSeconds(3)) < 0);
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void timeoutStopsDescendantProcesses() {
        CommandResult result = fixtureService().run(call -> call.args("spawn-child-sleep", "5000")
                .timeout(Duration.ofMillis(120))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(500))));
        long childPid = result.stdout()
                .lines()
                .filter(line -> line.startsWith("child:"))
                .map(line -> line.substring("child:".length()))
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElseThrow();

        assertTrue(result.timedOut());
        assertFalse(isAliveEventually(childPid));
    }

    @Test
    void postStartFailureStopsStartedProcessAndPreservesPrimaryException() throws Exception {
        AtomicLong childPid = new AtomicLong(-1);
        IllegalStateException failure = new IllegalStateException("synthetic post-start failure");

        java.time.Instant started = java.time.Instant.now();
        IllegalStateException exception;
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            childPid.set(process.pid());
            throw failure;
        }));
        exception = assertThrows(
                IllegalStateException.class,
                () -> service.run(call -> call.args("sleep", "5000")
                        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))));
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertEquals(failure, exception);
        assertTrue(childPid.get() > 0);
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void processWritingLargeStderrDoesNotBlockStdoutCompletion() {
        CommandResult result = fixtureService().run(call -> call.args("flood-stderr", Integer.toString(2 * 1024 * 1024))
                .capture(CapturePolicy.bounded(1024))
                .timeout(Duration.ofSeconds(5)));

        assertTrue(result.succeeded());
        assertStdoutEquals("done\n", result);
        assertTrue(result.stderrTruncated());
    }

    @Test
    void shellModeIsExplicitAndReceivesEnvironmentOverride() {
        CommandService shell = CommandService.forShellCommand(shellEchoEnvironmentCommand("ICLI_SHELL_VALUE"));

        CommandResult result = shell.run(call -> call.putEnvironment("ICLI_SHELL_VALUE", "configured"));

        assertTrue(result.succeeded());
        assertStdoutEquals("shell:configured\n", result);
    }

    private static CommandService fixtureService() {
        return fixtureService(ProcessKernel.standard());
    }

    private static CommandService fixtureService(ProcessKernel processKernel) {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), ProcessFixtureProgram.class.getName())
                .build();
        return new CommandService(
                command,
                RunOptions.defaults(),
                SessionOptions.defaults(),
                LineSessionOptions.defaults(),
                StreamOptions.defaults(),
                PooledLineSessionOptions.defaults(),
                DiagnosticsOptions.defaults(),
                processKernel);
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static String shellEchoEnvironmentCommand(String variableName) {
        if (isWindows()) {
            return "echo shell:%" + variableName + "%";
        }
        return "printf 'shell:%s\\n' \"$" + variableName + "\"";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void putWindowsSystemRootIfNeeded(CommandInvocation.Builder call) {
        if (!isWindows()) {
            return;
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            call.putEnvironment("SystemRoot", systemRoot);
        }
    }

    private static void assertStdoutEquals(String expected, CommandResult result) {
        assertEquals(expected, normalizeLineEndings(result.stdout()));
    }

    private static void assertStderrEquals(String expected, CommandResult result) {
        assertEquals(expected, normalizeLineEndings(result.stderr()));
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    private static boolean isAliveEventually(long pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted", exception);
                }
                continue;
            }
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static java.util.List<Byte> boxed(byte[] bytes) {
        java.util.ArrayList<Byte> result = new java.util.ArrayList<>();
        for (byte value : bytes) {
            result.add(value);
        }
        return result;
    }
}

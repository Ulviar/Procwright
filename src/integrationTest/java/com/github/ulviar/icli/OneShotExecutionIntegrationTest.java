package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OneShotExecutionIntegrationTest {

    @Test
    void successfulCommandCapturesStdoutAndExitCode() {
        CommandResult result = fixtureService().run(call -> call.args("stdout", "ready\n"));

        assertTrue(result.succeeded());
        assertEquals(0, result.exitCode().orElseThrow());
        assertEquals("ready\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void nonZeroCommandCapturesStdoutAndStderr() {
        CommandResult result = fixtureService().run(call -> call.args("stderr-exit", "7", "out\n", "err\n"));

        assertFalse(result.succeeded());
        assertEquals(7, result.exitCode().orElseThrow());
        assertEquals("out\n", result.stdout());
        assertEquals("err\n", result.stderr());
    }

    @Test
    void directArgvPreservesArgumentsWithoutShellExpansion() {
        CommandResult result = fixtureService().run(call -> call.args("args", "hello world", "$ICLI_NOT_EXPANDED"));

        assertEquals("hello world|$ICLI_NOT_EXPANDED\n", result.stdout());
    }

    @Test
    void commandReceivesWorkingDirectoryAndEnvironmentOverride(@TempDir Path workingDirectory) throws IOException {
        CommandResult result = fixtureService().run(call -> call.args("cwd-env", "ICLI_TEST_VALUE")
                .workingDirectory(workingDirectory)
                .putEnvironment("ICLI_TEST_VALUE", "configured"));

        assertEquals(workingDirectory.toRealPath() + "\nconfigured\n", result.stdout());
    }

    @Test
    void largeStdoutIsBoundedAndMarkedAsTruncated() {
        CommandResult result = fixtureService()
                .run(call -> call.args("large-stdout", "64", "x").capture(CapturePolicy.bounded(16)));

        assertEquals("x".repeat(16), result.stdout());
        assertEquals("", result.stderr());
        assertTrue(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void largeStderrIsBoundedIndependentlyFromStdout() {
        CommandResult result = fixtureService()
                .run(call -> call.args("large-stderr", "64", "e").capture(CapturePolicy.bounded(16)));

        assertEquals("done\n", result.stdout());
        assertEquals("e".repeat(16), result.stderr());
        assertFalse(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void stderrCanBeMergedIntoStdout() {
        CommandResult result = fixtureService()
                .run(call -> call.args("stdout-stderr", "out\n", "err\n").output(OutputMode.MERGED));

        assertTrue(result.stdout().contains("out\n"));
        assertTrue(result.stdout().contains("err\n"));
        assertTrue(new String(result.stdoutBytes(), StandardCharsets.UTF_8).contains("out\n"));
        assertEquals("", result.stderr());
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
        assertEquals("ok\n", result.stdout());
    }

    @Test
    void outputIsDecodedWithConfiguredCharset() {
        CommandResult result =
                fixtureService().run(call -> call.args("stdout", "Привет\n").charset(StandardCharsets.UTF_8));

        assertEquals("Привет\n", result.stdout());
    }

    @Test
    void runScenarioClosesStdinByDefault() {
        CommandResult result = fixtureService().run(call -> call.args("stdin-length"));

        assertTrue(result.succeeded());
        assertEquals("0\n", result.stdout());
    }

    @Test
    void inputOverrideWritesStdinBeforeClosingIt() {
        CommandResult result =
                fixtureService().run(call -> call.args("stdin-echo").input("payload\n"));

        assertTrue(result.succeeded());
        assertEquals("payload\n", result.stdout());
    }

    @Test
    void inputCharsetIsIndependentFromOutputCharset() {
        CommandResult result = fixtureService().run(call -> call.args("stdin-hex")
                .input("é", Charset.forName("ISO-8859-1"))
                .charset(StandardCharsets.US_ASCII));

        assertTrue(result.succeeded());
        assertEquals("e9\n", result.stdout());
    }

    @Test
    void timeoutStopsProcessAndReturnsDiagnosticResult() {
        CommandResult result = fixtureService().run(call -> call.args("sleep", "5000")
                .timeout(Duration.ofMillis(100))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertEquals("started\n", result.stdout());
    }

    @Test
    void timeoutIsEnforcedWhileWritingInput() {
        CommandResult result = fixtureService().run(call -> call.args("ignore-stdin-sleep", "5000")
                .input("x".repeat(8 * 1024 * 1024))
                .timeout(Duration.ofMillis(100))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertEquals("started\n", result.stdout());
    }

    @Test
    void processWritingLargeStderrDoesNotBlockStdoutCompletion() {
        CommandResult result = fixtureService().run(call -> call.args("flood-stderr", Integer.toString(2 * 1024 * 1024))
                .capture(CapturePolicy.bounded(1024))
                .timeout(Duration.ofSeconds(5)));

        assertTrue(result.succeeded());
        assertEquals("done\n", result.stdout());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void shellModeIsExplicitAndReceivesEnvironmentOverride() {
        CommandService shell = CommandService.forShellCommand(shellEchoEnvironmentCommand("ICLI_SHELL_VALUE"));

        CommandResult result = shell.run(call -> call.putEnvironment("ICLI_SHELL_VALUE", "configured"));

        assertTrue(result.succeeded());
        assertEquals("shell:configured\n", result.stdout());
    }

    private static CommandService fixtureService() {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), ProcessFixtureProgram.class.getName())
                .build();
        return new CommandService(command, RunOptions.defaults());
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

    private static java.util.List<Byte> boxed(byte[] bytes) {
        java.util.ArrayList<Byte> result = new java.util.ArrayList<>();
        for (byte value : bytes) {
            result.add(value);
        }
        return result;
    }
}

/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.OutputMode;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExecutionAndCaptureIntegrationTest extends OneShotExecutionIntegrationSupport {

    @Test
    void successfulCommandCapturesStdoutAndExitCode() {
        CommandResult result =
                fixtureService().run().withArgs("exit", "--stdout=ready\n").execute();

        assertTrue(result.succeeded());
        assertEquals(0, result.exitCode().orElseThrow());
        assertStdoutEquals("ready\n", result);
        assertStderrEquals("", result);
    }

    @Test
    void nonZeroCommandCapturesStdoutAndStderr() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("exit", "--exit-code=7", "--stdout=out\n", "--stderr=err\n")
                .execute();

        assertFalse(result.succeeded());
        assertEquals(7, result.exitCode().orElseThrow());
        assertStdoutEquals("out\n", result);
        assertStderrEquals("err\n", result);
    }

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
    void largeStdoutIsBoundedAndMarkedAsTruncated() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("burst", "--stdout-bytes=64", "--stdout-byte=x")
                .withCapture(CapturePolicy.bounded(16))
                .execute();

        assertStdoutEquals("x".repeat(16), result);
        assertStderrEquals("", result);
        assertTrue(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void largeStderrIsBoundedIndependentlyFromStdout() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("burst", "--stdout-bytes=5", "--stdout-byte=d", "--stderr-bytes=64", "--stderr-byte=e")
                .withCapture(CapturePolicy.bounded(16))
                .execute();

        assertStdoutEquals("ddddd", result);
        assertStderrEquals("e".repeat(16), result);
        assertFalse(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void stderrCanBeMergedIntoStdout() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("exit", "--stdout=out\n", "--stderr=err\n")
                .withOutput(OutputMode.MERGED)
                .execute();

        String stdout = normalizeLineEndings(result.stdout());
        assertTrue(stdout.contains("out\n"));
        assertTrue(stdout.contains("err\n"));
        assertTrue(new String(result.stdoutBytes(), StandardCharsets.UTF_8).contains("out\n"));
        assertStderrEquals("", result);
        assertEquals(0, result.stderrBytes().length);
    }

    @Test
    void capturedOutputBytesAreAvailableForBinaryWorkflows() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("binary", "--pattern=nul-ff-ascii")
                .withTimeout(Duration.ofSeconds(1))
                .withCapture(CapturePolicy.bounded(16))
                .withOutput(OutputMode.SEPARATE)
                .withCharsetPolicy(CharsetPolicy.replace(StandardCharsets.UTF_8))
                .execute();

        assertTrue(result.succeeded());
        assertEquals(java.util.List.of((byte) 0x00, (byte) 0xFF, (byte) 0x41), boxed(result.stdoutBytes()));
        assertEquals(0, result.stderrBytes().length);
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
    void hugeTimeoutIsSaturatedInsteadOfOverflowing() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("exit", "--stdout=ok\n")
                .withTimeout(Duration.ofSeconds(Long.MAX_VALUE))
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("ok\n", result);
    }

    @Test
    void zeroTimeoutDisablesRunTimeoutAndAwaitsCompletion() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=300", "--finished=true")
                .withTimeout(Duration.ZERO)
                .execute();

        assertTrue(result.succeeded());
        assertFalse(result.timedOut());
        assertTrue(normalizeLineEndings(result.stdout()).contains("finished\n"));
    }

    @Test
    void negativeTimeoutIsRejectedBeforeLaunch() {
        assertThrows(IllegalArgumentException.class, () -> fixtureService()
                .run()
                .withArgs("exit")
                .withTimeout(Duration.ofMillis(-1))
                .execute());
    }

    @Test
    void fileCaptureWritesLargeOutputWithEmptyResultStreams(@TempDir Path directory) throws IOException {
        Path stdoutFile = directory.resolve("stdout.log");
        Path stderrFile = directory.resolve("stderr.log");

        CommandResult result = fixtureService()
                .run()
                .withArgs("burst", "--stdout-bytes=2m", "--stdout-byte=x", "--stderr-bytes=64", "--stderr-byte=e")
                .withCapture(CapturePolicy.toPath(stdoutFile, stderrFile))
                .withTimeout(Duration.ofSeconds(30))
                .execute();

        assertTrue(result.succeeded());
        assertEquals(2 * 1024 * 1024, java.nio.file.Files.size(stdoutFile));
        assertEquals(64, java.nio.file.Files.size(stderrFile));
        assertStdoutEquals("", result);
        assertStderrEquals("", result);
        assertEquals(0, result.stdoutBytes().length);
        assertEquals(0, result.stderrBytes().length);
        assertFalse(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void discardCaptureDropsOutputWithoutFailing() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("burst", "--stdout-bytes=2m", "--stdout-byte=x")
                .withCapture(CapturePolicy.discard())
                .withTimeout(Duration.ofSeconds(30))
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("", result);
        assertStderrEquals("", result);
        assertFalse(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void mergedSingleFileCaptureReceivesBothStreams(@TempDir Path directory) throws IOException {
        Path mergedFile = directory.resolve("merged.log");

        CommandResult result = fixtureService()
                .run()
                .withArgs("exit", "--stdout=out\n", "--stderr=err\n")
                .withOutput(OutputMode.MERGED)
                .withCapture(CapturePolicy.toPath(mergedFile))
                .execute();

        assertTrue(result.succeeded());
        String merged = normalizeLineEndings(java.nio.file.Files.readString(mergedFile));
        assertTrue(merged.contains("out\n"));
        assertTrue(merged.contains("err\n"));
        assertStdoutEquals("", result);
    }

    @Test
    void fileCaptureWithSeparateOutputRejectsMergedSinglePathEarly(@TempDir Path directory) {
        Path mergedFile = directory.resolve("merged.log");

        assertThrows(IllegalArgumentException.class, () -> fixtureService()
                .run()
                .withArgs("exit")
                .withCapture(CapturePolicy.toPath(mergedFile))
                .execute());
        assertFalse(java.nio.file.Files.exists(mergedFile));
    }

    @Test
    void stdinFromPathStreamsLargeFileWithoutBufferingInMemory(@TempDir Path directory) throws IOException {
        Path stdinFile = directory.resolve("stdin.bin");
        byte[] payload = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');
        java.nio.file.Files.write(stdinFile, payload);

        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo", "--mode=bytes-count")
                .withInput(io.github.ulviar.procwright.command.CommandInput.fromPath(stdinFile))
                .withTimeout(Duration.ofSeconds(30))
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("bytes:" + payload.length + "\n", result);
    }

    @Test
    void stdinFromMissingFileFailsWithTypedLaunchFailure(@TempDir Path directory) {
        Path missing = directory.resolve("missing-input.bin");

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("stdin-echo")
                .withInput(io.github.ulviar.procwright.command.CommandInput.fromPath(missing))
                .execute());

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, exception.reason());
    }

    @Test
    void runScenarioClosesStdinByDefault() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo", "--mode=bytes-count")
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("bytes:0\n", result);
    }

    @Test
    void inputOverrideWritesStdinBeforeClosingIt() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo")
                .withInput("payload\n")
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("payload\n", result);
    }

    @Test
    void inputCharsetIsIndependentFromOutputCharset() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo", "--mode=hex")
                .withInput("é", Charset.forName("ISO-8859-1"))
                .withCharset(StandardCharsets.US_ASCII)
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("e9\n", result);
    }

    @Test
    void processWritingLargeStderrDoesNotBlockStdoutCompletion() {
        CommandResult result = fixtureService()
                .run()
                .withArgs(
                        "burst",
                        "--stdout-first=false",
                        "--stdout-bytes=5",
                        "--stdout-byte=d",
                        "--stderr-bytes=2m",
                        "--stderr-byte=e")
                .withCapture(CapturePolicy.bounded(1024))
                .withTimeout(Duration.ofSeconds(5))
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("ddddd", result);
        assertTrue(result.stderrTruncated());
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
}

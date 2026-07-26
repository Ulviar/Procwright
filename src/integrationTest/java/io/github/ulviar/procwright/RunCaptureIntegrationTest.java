/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.assertStdoutEquals;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.boxed;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.normalizeLineEndings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunCaptureIntegrationTest {

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

    private static void assertStderrEquals(String expected, CommandResult result) {
        assertEquals(expected, normalizeLineEndings(result.stderr()));
    }
}

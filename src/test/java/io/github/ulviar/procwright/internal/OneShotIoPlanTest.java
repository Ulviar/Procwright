/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.OutputMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OneShotIoPlanTest extends ProcessKernelTestSupport {

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundedSeparateCaptureWithMemoryInputNeedsTaskExecutor() {
        CommandInput input = CommandInput.utf8("input");
        OneShotIoPlan ioPlan = resolve(CapturePolicy.bounded(16), Optional.of(input), OutputMode.SEPARATE);

        assertEquals(ProcessBuilder.Redirect.PIPE, ioPlan.stdio().stdin());
        assertEquals(ProcessBuilder.Redirect.PIPE, ioPlan.stdio().stdout());
        assertEquals(ProcessBuilder.Redirect.PIPE, ioPlan.stdio().stderr());
        assertTrue(ioPlan.capturesStdout());
        assertTrue(ioPlan.capturesStderr());
        assertEquals(OneShotIoPlan.StdinAction.WRITE, ioPlan.stdinOperation().action());
        assertEquals(input, ioPlan.stdinOperation().writeInput());
        assertTrue(ioPlan.requiresTaskExecutor());
    }

    @Test
    void boundedMergedCaptureWithClosedInputNeedsTaskExecutor() {
        OneShotIoPlan ioPlan = resolve(CapturePolicy.bounded(16), Optional.empty(), OutputMode.MERGED);

        assertTrue(ioPlan.capturesStdout());
        assertFalse(ioPlan.capturesStderr());
        assertEquals(OneShotIoPlan.StdinAction.CLOSE, ioPlan.stdinOperation().action());
        assertTrue(ioPlan.requiresTaskExecutor());
    }

    @Test
    void discardedOutputNeedsNoPumpTasks() {
        OneShotIoPlan ioPlan = resolve(CapturePolicy.discard(), Optional.empty(), OutputMode.SEPARATE);

        assertEquals(ProcessBuilder.Redirect.DISCARD, ioPlan.stdio().stdout());
        assertEquals(ProcessBuilder.Redirect.DISCARD, ioPlan.stdio().stderr());
        assertFalse(ioPlan.capturesStdout());
        assertFalse(ioPlan.capturesStderr());
        assertFalse(ioPlan.requiresTaskExecutor());
    }

    @Test
    void fileRedirectsNeedNoPumpTasks() throws Exception {
        Path input = Files.writeString(temporaryDirectory.resolve("input.txt"), "input");
        Path stdout = temporaryDirectory.resolve("stdout.txt");
        Path stderr = temporaryDirectory.resolve("stderr.txt");

        OneShotIoPlan ioPlan = resolve(
                CapturePolicy.toPath(stdout, stderr), Optional.of(CommandInput.fromPath(input)), OutputMode.SEPARATE);

        assertEquals(ProcessBuilder.Redirect.Type.READ, ioPlan.stdio().stdin().type());
        assertEquals(input.toFile(), ioPlan.stdio().stdin().file());
        assertEquals(ProcessBuilder.Redirect.Type.WRITE, ioPlan.stdio().stdout().type());
        assertEquals(stdout.toFile(), ioPlan.stdio().stdout().file());
        assertEquals(ProcessBuilder.Redirect.Type.WRITE, ioPlan.stdio().stderr().type());
        assertEquals(stderr.toFile(), ioPlan.stdio().stderr().file());
        assertEquals(OneShotIoPlan.StdinAction.REDIRECT, ioPlan.stdinOperation().action());
        assertFalse(ioPlan.requiresTaskExecutor());
    }

    @Test
    void missingStdinFileIsRejectedAsLaunchFailure() {
        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> resolve(
                        CapturePolicy.discard(),
                        Optional.of(CommandInput.fromPath(temporaryDirectory.resolve("missing.txt"))),
                        OutputMode.SEPARATE));

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
    }

    private static OneShotIoPlan resolve(
            CapturePolicy capturePolicy, Optional<CommandInput> input, OutputMode outputMode) {
        return OneShotIoPlan.resolve(
                executionPlan(capturePolicy, DiagnosticsSettings.disabled(), input, outputMode, Duration.ofSeconds(1)));
    }
}

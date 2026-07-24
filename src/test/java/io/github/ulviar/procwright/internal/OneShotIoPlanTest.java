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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OneShotIoPlanTest extends ProcessKernelTestSupport {

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundedSeparateCaptureWithMemoryInputNeedsThreePipeTasks() {
        CommandInput input = CommandInput.utf8("input");
        OneShotIoPlan ioPlan = resolve(CapturePolicy.bounded(16), StdinPolicy.input(input), OutputMode.SEPARATE);

        assertEquals(ProcessBuilder.Redirect.PIPE, ioPlan.stdio().stdin());
        assertEquals(ProcessBuilder.Redirect.PIPE, ioPlan.stdio().stdout());
        assertEquals(ProcessBuilder.Redirect.PIPE, ioPlan.stdio().stderr());
        assertTrue(ioPlan.capturesStdout());
        assertTrue(ioPlan.capturesStderr());
        assertEquals(OneShotIoPlan.StdinAction.WRITE, ioPlan.stdinOperation().action());
        assertEquals(input, ioPlan.stdinOperation().writeInput());
        assertEquals(3, ioPlan.taskCount());
    }

    @Test
    void boundedMergedCaptureWithClosedInputNeedsOnlyTheMergedOutputTask() {
        OneShotIoPlan ioPlan = resolve(CapturePolicy.bounded(16), StdinPolicy.closed(), OutputMode.MERGED);

        assertTrue(ioPlan.capturesStdout());
        assertFalse(ioPlan.capturesStderr());
        assertEquals(OneShotIoPlan.StdinAction.CLOSE, ioPlan.stdinOperation().action());
        assertEquals(1, ioPlan.taskCount());
    }

    @Test
    void discardedOutputNeedsNoPumpTasks() {
        OneShotIoPlan ioPlan = resolve(CapturePolicy.discard(), StdinPolicy.closed(), OutputMode.SEPARATE);

        assertEquals(ProcessBuilder.Redirect.DISCARD, ioPlan.stdio().stdout());
        assertEquals(ProcessBuilder.Redirect.DISCARD, ioPlan.stdio().stderr());
        assertFalse(ioPlan.capturesStdout());
        assertFalse(ioPlan.capturesStderr());
        assertEquals(0, ioPlan.taskCount());
    }

    @Test
    void fileRedirectsNeedNoPumpTasks() throws Exception {
        Path input = Files.writeString(temporaryDirectory.resolve("input.txt"), "input");
        Path stdout = temporaryDirectory.resolve("stdout.txt");
        Path stderr = temporaryDirectory.resolve("stderr.txt");

        OneShotIoPlan ioPlan = resolve(
                CapturePolicy.toPath(stdout, stderr),
                StdinPolicy.input(CommandInput.fromPath(input)),
                OutputMode.SEPARATE);

        assertEquals(ProcessBuilder.Redirect.Type.READ, ioPlan.stdio().stdin().type());
        assertEquals(input.toFile(), ioPlan.stdio().stdin().file());
        assertEquals(ProcessBuilder.Redirect.Type.WRITE, ioPlan.stdio().stdout().type());
        assertEquals(stdout.toFile(), ioPlan.stdio().stdout().file());
        assertEquals(ProcessBuilder.Redirect.Type.WRITE, ioPlan.stdio().stderr().type());
        assertEquals(stderr.toFile(), ioPlan.stdio().stderr().file());
        assertEquals(OneShotIoPlan.StdinAction.REDIRECT, ioPlan.stdinOperation().action());
        assertEquals(0, ioPlan.taskCount());
    }

    @Test
    void missingStdinFileIsRejectedAsLaunchFailure() {
        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> resolve(
                        CapturePolicy.discard(),
                        StdinPolicy.input(CommandInput.fromPath(temporaryDirectory.resolve("missing.txt"))),
                        OutputMode.SEPARATE));

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
    }

    @Test
    void openStdinIsRejectedByTheOneShotPlan() {
        assertThrows(
                CommandExecutionException.class,
                () -> resolve(CapturePolicy.discard(), StdinPolicy.open(), OutputMode.SEPARATE));
    }

    private static OneShotIoPlan resolve(CapturePolicy capturePolicy, StdinPolicy stdin, OutputMode outputMode) {
        return OneShotIoPlan.resolve(
                executionPlan(capturePolicy, DiagnosticsSettings.disabled(), stdin, outputMode, Duration.ofSeconds(1)));
    }
}

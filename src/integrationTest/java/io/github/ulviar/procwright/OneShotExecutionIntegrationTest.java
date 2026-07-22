/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ProcessKernel;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OneShotExecutionIntegrationTest {

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
    void timeoutWithFileCaptureStillStopsProcessAndReportsTimedOut(@TempDir Path directory) {
        Path stdoutFile = directory.resolve("stdout.log");
        Path stderrFile = directory.resolve("stderr.log");

        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withCapture(CapturePolicy.toPath(stdoutFile, stderrFile))
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200)))
                .execute();

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("", result);
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
    void outputIsDecodedWithConfiguredCharset() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=d09fd180d0b8d0b2d0b5d1820a")
                .withCharset(StandardCharsets.UTF_8)
                .execute();

        assertStdoutEquals("Привет\n", result);
    }

    @Test
    void strictCharsetPolicyReportsDecodeErrorAsTypedFailure() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=ff", "--stream=both")
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .execute());

        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, exception.reason());
        CommandResult snapshot = exception.result().orElseThrow();
        assertEquals(0, snapshot.exitCode().orElseThrow());
        assertEquals((byte) 0xFF, snapshot.stdoutBytes()[0]);
        assertEquals((byte) 0xFF, snapshot.stderrBytes()[0]);
        assertTrue(snapshot.stdout().contains("\uFFFD"));
        assertTrue(snapshot.stderr().contains("\uFFFD"));
    }

    @Test
    void replacementDecodingPreservesExactCapturedBytes() {
        byte[] expected = {0x00, (byte) 0xFF, 0x41};
        RunScenario.Draft strict = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=00ff41", "--stream=both")
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        CommandResult result = strict.withTimeout(Duration.ofSeconds(2))
                .withCapture(CapturePolicy.bounded(1024))
                .withOutput(OutputMode.SEPARATE)
                .withCharsetPolicy(CharsetPolicy.replace(StandardCharsets.UTF_8))
                .execute();

        assertTrue(result.succeeded());
        assertArrayEquals(expected, result.stdoutBytes());
        assertArrayEquals(expected, result.stderrBytes());
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.stdout());
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.stderr());
    }

    @Test
    void unrelatedRunPoliciesPreserveStrictDecodingPolicy() {
        RunScenario.Draft strict = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=ff")
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> strict.withTimeout(Duration.ofSeconds(2))
                        .withCapture(CapturePolicy.bounded(1024))
                        .withOutput(OutputMode.SEPARATE)
                        .execute());

        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, failure.reason());
    }

    @Test
    void strictCharsetPolicyAcceptsValidOutputTruncatedInsideFinalCodePoint() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=e282ac")
                .withCapture(CapturePolicy.bounded(2))
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .execute();

        assertTrue(result.stdoutTruncated());
        assertEquals(2, result.stdoutBytes().length);
        assertEquals("", result.stdout());
    }

    @Test
    void truncatedDecodeRejectsOverflowWithoutInputOrOutputProgress() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=4142")
                .withCapture(CapturePolicy.bounded(1))
                .withCharsetPolicy(CharsetPolicy.report(new OverflowProbeCharset(false)))
                .execute());

        assertDecodeFailureRetainsCapturedPrefix(exception);
    }

    @Test
    void truncatedDecodeBoundsOutputProducedWithoutConsumingInput() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=4142")
                .withCapture(CapturePolicy.bounded(1))
                .withCharsetPolicy(CharsetPolicy.report(new OverflowProbeCharset(true)))
                .execute());

        assertDecodeFailureRetainsCapturedPrefix(exception);
    }

    @Test
    void decoderCreationRuntimeFailureIsTypedAndRetainsResult() {
        IllegalStateException decoderFailure = new IllegalStateException("decoder creation failed");

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(
                        CharsetPolicy.report(new FailingCharset(DecoderFailureStage.NEW_DECODER, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderConfigurationRuntimeFailureIsTypedAndRetainsResult() {
        IllegalStateException decoderFailure = new IllegalStateException("decoder configuration failed");

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(
                        CharsetPolicy.report(new FailingCharset(DecoderFailureStage.CONFIGURE, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderMalfunctionDuringDecodeIsTypedAndRetainsResult() {
        CoderMalfunctionError decoderFailure = new CoderMalfunctionError(new IllegalStateException("decode failed"));

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(CharsetPolicy.report(new FailingCharset(DecoderFailureStage.DECODE, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderMalfunctionDuringFlushIsTypedAndRetainsResult() {
        CoderMalfunctionError decoderFailure = new CoderMalfunctionError(new IllegalStateException("flush failed"));

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(CharsetPolicy.report(new FailingCharset(DecoderFailureStage.FLUSH, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderErrorOutsideTypedBoundaryPreservesIdentity() {
        AssertionError decoderFailure = new AssertionError("decoder failed irrecoverably");

        AssertionError thrown = assertThrows(AssertionError.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(
                        CharsetPolicy.report(new FailingCharset(DecoderFailureStage.NEW_DECODER, decoderFailure)))
                .execute());

        assertSame(decoderFailure, thrown);
    }

    @Test
    void capturedOutputPreservesEmittedNewlineStyleWithoutNormalization() {
        CommandResult lf = fixtureService()
                .run()
                .withArgs("platform-newlines", "--style=lf")
                .execute();
        CommandResult crlf = fixtureService()
                .run()
                .withArgs("platform-newlines", "--style=crlf")
                .execute();
        CommandResult crOnly = fixtureService()
                .run()
                .withArgs("platform-newlines", "--style=cr")
                .execute();

        assertEquals("out:0\nout:1\n", lf.stdout());
        assertEquals("out:0\r\nout:1\r\n", crlf.stdout());
        assertEquals("out:0\rout:1\r", crOnly.stdout());
    }

    @Test
    void callerInterruptDuringRunIsTypedFailureAndRestoresInterruptStatus() throws Exception {
        java.util.concurrent.CountDownLatch processStarted = new java.util.concurrent.CountDownLatch(1);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            childPid.set(process.pid());
            processStarted.countDown();
        }));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedAfterCatch =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                service.run().withArgs("never-exit").withTimeout(Duration.ZERO).execute();
            } catch (Throwable throwable) {
                thrown.set(throwable);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        assertTrue(processStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(10));

        assertFalse(caller.isAlive(), "interrupted caller must not stay blocked in run()");
        assertTrue(
                thrown.get() instanceof CommandExecutionException,
                () -> "expected typed execution failure, got " + thrown.get());
        assertTrue(interruptedAfterCatch.get(), "caller interrupt status must be restored after the typed failure");
        assertProcessEventuallyStops(childPid.get());
    }

    @Test
    void earlyBrokenPipeBeatsLongTimeoutAndStopsRootAndDescendant(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("broken-pipe-child.pid");
        AtomicLong rootPid = new AtomicLong(-1);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            rootPid.set(process.pid());
            childPid.set(waitForPositivePidUnchecked(childPidFile, Duration.ofSeconds(5)));
            waitForDescendantUnchecked(process, childPid.get(), Duration.ofSeconds(5));
        }));
        java.time.Instant started = java.time.Instant.now();

        CommandExecutionException failure = assertThrows(CommandExecutionException.class, () -> service.run()
                .withArgs(
                        "spawn-child",
                        "--close-stdin=true",
                        "--child-scenario=never-exit",
                        "--pid-file=" + childPidFile,
                        "--wait=true")
                .withInput(CommandInput.bytes(new byte[8 * 1024 * 1024]))
                .withTimeout(Duration.ofSeconds(30))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofSeconds(2)))
                .execute());

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
        assertTrue(failure.getCause() instanceof IOException, () -> "unexpected writer cause: " + failure.getCause());
        assertTrue(
                Duration.between(started, java.time.Instant.now()).compareTo(Duration.ofSeconds(10)) < 0,
                "writer failure waited for the run deadline");
        assertProcessEventuallyStops(rootPid.get());
        assertProcessEventuallyStops(childPid.get());
    }

    @Test
    void callerInterruptDuringBlockedStdinWriteStopsRootAndDescendant(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("interrupted-stdin-child.pid");
        java.util.concurrent.CountDownLatch processReady = new java.util.concurrent.CountDownLatch(1);
        AtomicLong rootPid = new AtomicLong(-1);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            rootPid.set(process.pid());
            childPid.set(waitForPositivePidUnchecked(childPidFile, Duration.ofSeconds(5)));
            waitForDescendantUnchecked(process, childPid.get(), Duration.ofSeconds(5));
            processReady.countDown();
        }));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptRestored = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(
                () -> {
                    try {
                        service.run()
                                .withArgs(
                                        "spawn-child",
                                        "--child-scenario=never-exit",
                                        "--pid-file=" + childPidFile,
                                        "--wait=true")
                                .withInput(CommandInput.bytes(new byte[8 * 1024 * 1024]))
                                .withTimeout(Duration.ZERO)
                                .withShutdown(
                                        ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofSeconds(2)))
                                .execute();
                    } catch (Throwable failure) {
                        thrown.set(failure);
                        interruptRestored.set(Thread.currentThread().isInterrupted());
                    }
                },
                "procwright-interrupted-stdin-integration");

        try {
            caller.start();
            assertTrue(processReady.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(caller.isAlive(), "interrupted run remained blocked in stdin writer cleanup");
            assertTrue(thrown.get() instanceof CommandExecutionException, () -> "unexpected failure: " + thrown.get());
            assertTrue(interruptRestored.get());
            assertProcessEventuallyStops(rootPid.get());
            assertProcessEventuallyStops(childPid.get());
        } finally {
            bestEffortDestroyPid(rootPid.get());
            bestEffortDestroyPid(childPid.get());
        }
    }

    @Test
    void callerInterruptUsesConfiguredGracefulShutdown(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "POSIX signal shutdown semantics are not available on Windows");
        Path hookFile = directory.resolve("interrupt-shutdown-hook.txt");
        Path readyFile = directory.resolve("interrupt-ready.txt");
        java.util.concurrent.CountDownLatch processStarted = new java.util.concurrent.CountDownLatch(1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> processStarted.countDown()));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedAfterCatch =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                service.run()
                        .withArgs(
                                "shutdown-hook",
                                "--hook-delay-millis=50",
                                "--hook-file=" + hookFile,
                                "--ready-file=" + readyFile)
                        .withTimeout(Duration.ZERO)
                        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(1), Duration.ofSeconds(1)))
                        .execute();
            } catch (Throwable failure) {
                thrown.set(failure);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        assertTrue(processStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
        long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!java.nio.file.Files.exists(readyFile) && System.nanoTime() < readyDeadline) {
            Thread.sleep(10);
        }
        assertTrue(java.nio.file.Files.exists(readyFile));
        caller.interrupt();
        caller.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive());
        assertTrue(thrown.get() instanceof CommandExecutionException);
        assertTrue(interruptedAfterCatch.get());
        String hookMarkers = java.nio.file.Files.readString(hookFile);
        assertTrue(hookMarkers.contains("shutdown-hook:start"));
        assertTrue(hookMarkers.contains("shutdown-hook:end"));
    }

    @Test
    void shutdownEscalationForceKillsProcessThatSurvivesInterruptSignal(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "Windows destroy() does not expose a SIGTERM-then-KILL escalation");
        Path hookFile = directory.resolve("shutdown-hook.txt");
        Duration interruptGrace = Duration.ofSeconds(2);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service =
                fixtureService(ProcessKernel.withPostStartHook(process -> childPid.set(process.pid())));

        // The shutdown hook blocks for 60 s, so the interrupt signal alone cannot end the process;
        // only the force-kill escalation after the interrupt grace can explain a bounded, dead
        // process. The hook records its progress in a file because output emitted during shutdown is inherently racy
        // with pipe draining. The 2 s run timeout leaves the fixture JVM time to register its hook before the signal.
        java.time.Instant stopStarted = java.time.Instant.now();
        CommandResult result = service.run()
                .withArgs("shutdown-hook", "--hook-delay-millis=60000", "--hook-file=" + hookFile)
                .withTimeout(Duration.ofSeconds(2))
                .withShutdown(ShutdownPolicy.interruptThenKill(interruptGrace, Duration.ofSeconds(5)))
                .execute();
        Duration wallClockElapsed = Duration.between(stopStarted, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertTrue(normalizeLineEndings(result.stdout()).startsWith("started\n"));
        String hookMarkers = java.nio.file.Files.exists(hookFile) ? java.nio.file.Files.readString(hookFile) : "";
        assertTrue(
                hookMarkers.contains("shutdown-hook:start"),
                () -> "interrupt signal must reach the shutdown hook before escalation, hook file: " + hookMarkers);
        assertFalse(
                hookMarkers.contains("shutdown-hook:end"),
                () -> "force kill must preempt the blocking shutdown hook, hook file: " + hookMarkers);
        assertTrue(
                wallClockElapsed.compareTo(interruptGrace) >= 0,
                () -> "process must survive the full interrupt grace before the kill, took " + wallClockElapsed);
        assertTrue(
                wallClockElapsed.compareTo(Duration.ofSeconds(30)) < 0,
                () -> "escalation must stay bounded, took " + wallClockElapsed);
        assertProcessEventuallyStops(childPid.get());
    }

    @Test
    void shutdownEscalationIsNotBlockedByAFullStdinPipe(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "POSIX signal shutdown semantics are not available on Windows");
        Path hookFile = directory.resolve("full-stdin-shutdown-hook.txt");
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service =
                fixtureService(ProcessKernel.withPostStartHook(process -> childPid.set(process.pid())));

        java.time.Instant started = java.time.Instant.now();
        CommandResult result = service.run()
                .withArgs("shutdown-hook", "--hook-delay-millis=60000", "--hook-file=" + hookFile)
                .withInput(CommandInput.bytes(new byte[8 * 1024 * 1024]))
                .withTimeout(Duration.ofSeconds(2))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(200), Duration.ofSeconds(5)))
                .execute();
        Duration elapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertTrue(elapsed.compareTo(Duration.ofSeconds(15)) < 0, () -> "shutdown took " + elapsed);
        assertProcessEventuallyStops(childPid.get());
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
    void timeoutStopsProcessAndReturnsDiagnosticResult() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200)))
                .execute();

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("started\n", result);
    }

    @Test
    void timeoutIsEnforcedWhileWritingInput() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService()
                .run()
                .withArgs("ignore-stdin", "--millis=5000")
                .withInput("x".repeat(8 * 1024 * 1024))
                .withTimeout(Duration.ofMillis(100))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200)))
                .execute();
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        String stdout = normalizeLineEndings(result.stdout());
        assertTrue(stdout.isEmpty() || "started\n".equals(stdout));
        assertTrue(result.elapsed().compareTo(Duration.ofSeconds(3)) < 0);
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void timeoutCleanupIsBoundedWhileStdoutAndStderrPumpsAreActive() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService()
                .run()
                .withArgs("long-run", "--ticks=100000", "--interval-millis=20", "--stderr-every=1")
                .withTimeout(timeoutAfterFixtureStartup())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))
                .execute();
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertTrue(normalizeLineEndings(result.stdout()).contains("tick:"));
        assertTrue(normalizeLineEndings(result.stderr()).contains("err-tick:"));
        assertTrue(result.elapsed().compareTo(boundedCleanupLimit()) < 0);
        assertTrue(wallClockElapsed.compareTo(boundedCleanupLimit()) < 0);
    }

    @Test
    void timeoutStopsDescendantProcesses() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("spawn-child", "--child-scenario=sleep", "--child-millis=10000", "--wait=true")
                .withTimeout(descendantStartupTimeout())
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(500)))
                .execute();
        long childPid = result.stdout()
                .lines()
                .filter(line -> line.startsWith("child:"))
                .map(line -> line.substring("child:".length()))
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "fixture did not report child pid before timeout: " + normalizeLineEndings(result.stdout())));

        assertTrue(result.timedOut());
        assertFalse(isAliveEventually(childPid));
    }

    @Test
    void timeoutStopsDescendantCreatedDuringGracefulShutdown(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "POSIX shutdown hooks require a graceful termination signal");
        Path readyFile = directory.resolve("late-descendant-ready.txt");
        Path childPidFile = directory.resolve("late-descendant.pid");
        CommandService service = fixtureService(
                ProcessKernel.withPostStartHook(process -> waitForFileUnchecked(readyFile, Duration.ofSeconds(5))));
        long childPid = -1;
        try {
            CommandResult result = service.run()
                    .withArgs(
                            "shutdown-hook",
                            "--ready-file=" + readyFile,
                            "--hook-child-pid-file=" + childPidFile,
                            "--hook-delay-millis=1000")
                    .withTimeout(Duration.ofSeconds(2))
                    .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                    .execute();

            childPid = waitForPositivePid(childPidFile, Duration.ofSeconds(2));
            assertTrue(result.timedOut());
            assertTrue(
                    normalizeLineEndings(result.stdout()).contains("shutdown-hook:start"),
                    () -> "graceful shutdown hook did not retain stdout: stdout='"
                            + normalizeLineEndings(result.stdout())
                            + "', stderr='"
                            + normalizeLineEndings(result.stderr())
                            + "'");
            assertFalse(
                    ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                    "cleanup left a descendant created by the shutdown hook alive");
        } finally {
            bestEffortDestroyCapturedPid(childPid, childPidFile);
        }
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
        exception = assertThrows(IllegalStateException.class, () -> service.run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))
                .execute());
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertEquals(failure, exception);
        assertTrue(childPid.get() > 0);
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void postStartErrorStopsStartedProcessAndPreservesPrimaryError() throws Exception {
        AtomicLong childPid = new AtomicLong(-1);
        AssertionError failure = new AssertionError("synthetic post-start error");
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            childPid.set(process.pid());
            throw failure;
        }));

        AssertionError thrown = assertThrows(AssertionError.class, () -> service.run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))
                .execute());

        assertEquals(failure, thrown);
        assertTrue(childPid.get() > 0);
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void cyclicPostStartFailureTerminatesAndStopsRootAndDescendant(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("cyclic-failure-child.pid");
        AtomicLong rootPid = new AtomicLong(-1);
        AtomicLong childPid = new AtomicLong(-1);
        IllegalStateException primary = new IllegalStateException("cyclic post-start failure");
        IllegalArgumentException cycle = new IllegalArgumentException("cycle");
        primary.initCause(cycle);
        cycle.initCause(primary);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> {
            rootPid.set(process.pid());
            childPid.set(waitForPositivePidUnchecked(childPidFile, Duration.ofSeconds(5)));
            waitForDescendantUnchecked(process, childPid.get(), Duration.ofSeconds(5));
            throw primary;
        }));

        FutureTask<Throwable> invocation = new FutureTask<>(() -> {
            try {
                service.run()
                        .withArgs(
                                "spawn-child",
                                "--child-scenario=never-exit",
                                "--pid-file=" + childPidFile,
                                "--wait=true")
                        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofSeconds(2)))
                        .execute();
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
        Thread worker = new Thread(invocation, "cyclic-post-start-failure-test");
        worker.setDaemon(true);
        worker.start();

        try {
            Throwable thrown;
            try {
                thrown = invocation.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                String diagnostic = cyclicFailureDiagnostic(worker, rootPid.get(), childPid.get(), childPidFile);
                worker.interrupt();
                bestEffortDestroyPid(rootPid.get());
                bestEffortDestroyCapturedPid(childPid.get(), childPidFile);
                worker.join(TimeUnit.SECONDS.toMillis(5));
                throw new AssertionError(
                        "cyclic post-start cleanup exceeded its composed budget: " + diagnostic, timeout);
            }

            assertSame(primary, thrown);
            assertTrue(rootPid.get() > 0, "root pid was not captured");
            assertTrue(childPid.get() > 0, "descendant pid was not captured");
            assertProcessEventuallyStops(rootPid.get());
            assertProcessEventuallyStops(childPid.get());
        } finally {
            bestEffortDestroyPid(rootPid.get());
            bestEffortDestroyCapturedPid(childPid.get(), childPidFile);
        }
    }

    @Test
    void callerInterruptDuringOrphanedOutputDrainWaitsForDescendantCleanup(@TempDir Path directory) throws Exception {
        Path childPidFile = directory.resolve("drain-child.pid");
        AtomicLong rootPid = new AtomicLong(-1);
        CommandService service = fixtureService(ProcessKernel.withPostStartHook(process -> rootPid.set(process.pid())));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedAfterCatch =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                service.run()
                        .withArgs(
                                "spawn-child",
                                "--child-scenario=never-exit",
                                "--inherit-output=true",
                                "--pid-file=" + childPidFile,
                                "--linger-millis=500")
                        .withTimeout(Duration.ofSeconds(10))
                        .execute();
            } catch (Throwable failure) {
                thrown.set(failure);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        long childPid = waitForPositivePid(childPidFile, Duration.ofSeconds(10));
        waitForProcessExit(rootPid.get(), Duration.ofSeconds(10));
        try {
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(caller.isAlive());
            assertTrue(thrown.get() instanceof CommandExecutionException, () -> "unexpected failure: " + thrown.get());
            assertTrue(interruptedAfterCatch.get());
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(java.util.Arrays.stream(thrown.get().getSuppressed())
                    .anyMatch(failure -> failure.getMessage() != null
                            && failure.getMessage().contains("Interrupted while waiting for command cleanup")));
        } finally {
            ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
        }
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
    void orphanedDescendantHoldingOutputPipeIsKilledAndFailureExplainsCause(@TempDir Path directory) throws Exception {
        assumeFalse(isWindows(), "The orphaned POSIX pipe fixture is not portable to Windows");
        Path pidFile = directory.resolve("grandchild.pid");

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs(
                        "spawn-child",
                        "--child-scenario=never-exit",
                        "--inherit-output=true",
                        "--pid-file=" + pidFile,
                        "--linger-millis=500")
                .withTimeout(Duration.ofSeconds(10))
                .execute());

        assertTrue(
                exception.getMessage().contains("a descendant process that inherited stdout or stderr"),
                () -> "failure message must explain the orphaned pipe holder: " + exception.getMessage());
        long orphanPid = waitForPositivePid(pidFile, Duration.ofSeconds(5));
        assertProcessEventuallyStops(orphanPid);
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

    private static void assertDecodeFailureRetainsCapturedPrefix(CommandExecutionException exception) {
        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, exception.reason());
        CommandResult result = exception.result().orElseThrow();
        assertEquals(List.of((byte) 'A'), boxed(result.stdoutBytes()));
        assertEquals("A", result.stdout());
        assertTrue(result.stdoutTruncated());
    }

    private static void assertTypedDecodeFailure(
            CommandExecutionException exception, Throwable decoderFailure, boolean truncated) {
        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, exception.reason());
        assertSame(decoderFailure, exception.getCause());
        CommandResult result = exception.result().orElseThrow();
        assertEquals(List.of((byte) 'A'), boxed(result.stdoutBytes()));
        assertEquals("A", result.stdout());
        assertEquals(truncated, result.stdoutTruncated());
    }

    private abstract static class TestCharset extends Charset {

        private TestCharset(String canonicalName) {
            super(canonicalName, null);
        }

        @Override
        public boolean contains(Charset charset) {
            return charset == this;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class OverflowProbeCharset extends TestCharset {

        private final boolean produceOutput;
        private int decoderCreations;

        private OverflowProbeCharset(boolean produceOutput) {
            super(produceOutput ? "x-procwright-output-overflow" : "x-procwright-no-progress-overflow");
            this.produceOutput = produceOutput;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (decoderCreations++ > 0) {
                return StandardCharsets.US_ASCII.newDecoder();
            }
            return new CharsetDecoder(this, 1, 1) {
                private int calls;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (input.hasRemaining() && calls++ < 8) {
                        if (produceOutput) {
                            output.put('x');
                        }
                        return CoderResult.OVERFLOW;
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }
    }

    private enum DecoderFailureStage {
        NEW_DECODER,
        CONFIGURE,
        DECODE,
        FLUSH
    }

    private static final class FailingCharset extends TestCharset {

        private final DecoderFailureStage stage;
        private final Throwable failure;

        private FailingCharset(DecoderFailureStage stage, Throwable failure) {
            super("x-procwright-failing-" + stage.name().toLowerCase(java.util.Locale.ROOT));
            this.stage = stage;
            this.failure = failure;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (stage == DecoderFailureStage.NEW_DECODER) {
                throwUnchecked(failure);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected void implOnMalformedInput(CodingErrorAction newAction) {
                    if (stage == DecoderFailureStage.CONFIGURE) {
                        throwUnchecked(failure);
                    }
                }

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (stage == DecoderFailureStage.DECODE) {
                        throwUnchecked(failure);
                    }
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    if (stage == DecoderFailureStage.FLUSH) {
                        throwUnchecked(failure);
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("test failure must be unchecked", failure);
    }

    private static CommandService fixtureService() {
        return fixtureService(ProcessKernel.standard());
    }

    private static CommandService fixtureService(ProcessKernel processKernel) {
        return new CommandService(TestCliSupport.command(), processKernel);
    }

    private static String shellEchoEnvironmentCommand(String variableName) {
        if (isWindows()) {
            return "echo shell:%" + variableName + "%";
        }
        return "printf 'shell:%s\\n' \"$" + variableName + "\"";
    }

    private static void assertWindowsShellIgnoresPoisonedJvmWorkingDirectory(
            Path workingDirectory, boolean cleanEnvironment) throws Exception {
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

    private static String absoluteClasspath() {
        return Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(System.getProperty("java.class.path"))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void assertProcessEventuallyStops(long pid) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return;
            }
            Thread.sleep(25);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        throw new AssertionError("orphaned descendant process " + pid + " is still alive");
    }

    private static void bestEffortDestroyCapturedPid(long capturedPid, Path pidFile) {
        long pid = capturedPid > 0 ? capturedPid : readPositivePidBestEffort(pidFile, Duration.ofSeconds(2));
        if (pid <= 0) {
            return;
        }
        try {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            waitForProcessStopBestEffort(pid, Duration.ofSeconds(2));
        } catch (RuntimeException ignored) {
            // Test cleanup is best-effort and must not replace the behavioral assertion failure.
        }
    }

    private static void bestEffortDestroyPid(long pid) {
        if (pid <= 0) {
            return;
        }
        try {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            waitForProcessStopBestEffort(pid, Duration.ofSeconds(2));
        } catch (RuntimeException ignored) {
            // Test cleanup is best-effort and must not replace the behavioral assertion failure.
        }
    }

    private static String cyclicFailureDiagnostic(Thread worker, long rootPid, long childPid, Path childPidFile) {
        String pidFileState;
        try {
            pidFileState = java.nio.file.Files.exists(childPidFile)
                    ? java.nio.file.Files.readString(childPidFile).trim()
                    : "<missing>";
        } catch (IOException failure) {
            pidFileState = "<unreadable: " + failure.getClass().getSimpleName() + ">";
        }
        return "workerState=" + worker.getState()
                + ", rootPid=" + rootPid
                + ", rootAlive=" + processAliveBestEffort(rootPid)
                + ", childPid=" + childPid
                + ", childAlive=" + processAliveBestEffort(childPid)
                + ", pidFile='" + pidFileState + "'";
    }

    private static boolean processAliveBestEffort(long pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long waitForPositivePid(Path path, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String lastContent = "";
        IOException lastReadFailure = null;
        while (System.nanoTime() < deadlineNanos) {
            if (java.nio.file.Files.isRegularFile(path)) {
                try {
                    lastContent = java.nio.file.Files.readString(path).trim();
                    long pid = Long.parseLong(lastContent);
                    if (pid > 0) {
                        return pid;
                    }
                } catch (IOException exception) {
                    lastReadFailure = exception;
                } catch (NumberFormatException ignored) {
                    // The producer may still be replacing a partial PID marker.
                }
            }
            Thread.sleep(10);
        }
        AssertionError failure = new AssertionError(
                "PID file did not contain a positive process id: " + path + " (last content: '" + lastContent + "')");
        if (lastReadFailure != null) {
            failure.initCause(lastReadFailure);
        }
        throw failure;
    }

    private static long readPositivePidBestEffort(Path path, Duration timeout) {
        try {
            return waitForPositivePid(path, timeout);
        } catch (AssertionError ignored) {
            return -1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static long waitForPositivePidUnchecked(Path path, Duration timeout) {
        try {
            return waitForPositivePid(path, timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a positive PID in " + path, exception);
        }
    }

    private static void waitForProcessStopBestEffort(long pid, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos
                    && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(10);
            }
        } catch (RuntimeException ignored) {
            // Cleanup remains best-effort when process liveness cannot be observed.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitForFile(Path path, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!java.nio.file.Files.exists(path) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertTrue(java.nio.file.Files.exists(path), () -> "timed out waiting for " + path);
    }

    private static void waitForFileUnchecked(Path path, Duration timeout) {
        try {
            waitForFile(path, timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + path, exception);
        } catch (Exception exception) {
            throw new AssertionError("could not wait for " + path, exception);
        }
    }

    private static void waitForProcessExit(long pid, Duration timeout) throws Exception {
        assertTrue(pid > 0, "process pid was not captured");
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process did not exit");
    }

    private static void waitForDescendantUnchecked(Process process, long descendantPid, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos) {
                if (process.descendants().anyMatch(handle -> handle.pid() == descendantPid)) {
                    return;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for descendant " + descendantPid, exception);
        }
        throw new AssertionError("process " + process.pid() + " did not expose descendant " + descendantPid);
    }

    private static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static Duration boundedCleanupLimit() {
        return isWindows() ? Duration.ofSeconds(6) : Duration.ofSeconds(3);
    }

    private static Duration descendantStartupTimeout() {
        // The spawned child JVM sleeps for 10 s, so a generous startup window keeps the assertion
        // semantics (timeout fires after the child pid is reported) while absorbing cold JVM starts
        // on loaded machines.
        return Duration.ofSeconds(2);
    }

    private static RunScenario.Draft putWindowsSystemRootIfNeeded(RunScenario.Draft draft) {
        if (!isWindows()) {
            return draft;
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            return draft.withEnvironment("SystemRoot", systemRoot);
        }
        return draft;
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

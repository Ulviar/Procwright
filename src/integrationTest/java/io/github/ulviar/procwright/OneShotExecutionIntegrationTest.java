/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInvocation;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticsOptions;
import io.github.ulviar.procwright.internal.ProcessKernel;
import io.github.ulviar.procwright.preset.ScenarioPresets;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.PooledLineSessionOptions;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.SessionOptions;
import io.github.ulviar.procwright.session.StreamOptions;
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
        CommandResult result = fixtureService().run(call -> call.args("exit", "--stdout=ready\n"));

        assertTrue(result.succeeded());
        assertEquals(0, result.exitCode().orElseThrow());
        assertStdoutEquals("ready\n", result);
        assertStderrEquals("", result);
    }

    @Test
    void nonZeroCommandCapturesStdoutAndStderr() {
        CommandResult result =
                fixtureService().run(call -> call.args("exit", "--exit-code=7", "--stdout=out\n", "--stderr=err\n"));

        assertFalse(result.succeeded());
        assertEquals(7, result.exitCode().orElseThrow());
        assertStdoutEquals("out\n", result);
        assertStderrEquals("err\n", result);
    }

    @Test
    void directArgvPreservesArgumentsWithoutShellExpansion() {
        CommandResult result = fixtureService()
                .run(call -> call.args("argv-env-cwd", "--", "hello world", "$PROCWRIGHT_NOT_EXPANDED"));

        assertTrue(normalizeLineEndings(result.stdout()).contains("argv:hello world|$PROCWRIGHT_NOT_EXPANDED\n"));
    }

    @Test
    void commandReceivesWorkingDirectoryAndEnvironmentOverride(@TempDir Path workingDirectory) throws IOException {
        CommandResult result = fixtureService().run(call -> call.args("argv-env-cwd", "--env=PROCWRIGHT_TEST_VALUE")
                .workingDirectory(workingDirectory)
                .putEnvironment("PROCWRIGHT_TEST_VALUE", "configured"));

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

        CommandResult result = fixtureService().run(call -> {
            call.args("argv-env-cwd", "--env=" + inheritedName)
                    .cleanEnvironment()
                    .putEnvironment("PROCWRIGHT_TEST_VALUE", "configured");
            putWindowsSystemRootIfNeeded(call);
        });

        assertTrue(result.succeeded());
        assertTrue(normalizeLineEndings(result.stdout()).contains("env:" + inheritedName + "=<missing>\n"));
    }

    @Test
    void largeStdoutIsBoundedAndMarkedAsTruncated() {
        CommandResult result = fixtureService().run(call -> call.args("burst", "--stdout-bytes=64", "--stdout-byte=x")
                .capture(CapturePolicy.bounded(16)));

        assertStdoutEquals("x".repeat(16), result);
        assertStderrEquals("", result);
        assertTrue(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void largeStderrIsBoundedIndependentlyFromStdout() {
        CommandResult result = fixtureService().run(call -> call.args(
                        "burst", "--stdout-bytes=5", "--stdout-byte=d", "--stderr-bytes=64", "--stderr-byte=e")
                .capture(CapturePolicy.bounded(16)));

        assertStdoutEquals("ddddd", result);
        assertStderrEquals("e".repeat(16), result);
        assertFalse(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void stderrCanBeMergedIntoStdout() {
        CommandResult result = fixtureService().run(call -> call.args("exit", "--stdout=out\n", "--stderr=err\n")
                .output(OutputMode.MERGED));

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
            call.args("binary", "--pattern=nul-ff-ascii");
            ScenarioPresets.binaryOutputCapture(Duration.ofSeconds(1), 16).accept(call);
        });

        assertTrue(result.succeeded());
        assertEquals(java.util.List.of((byte) 0x00, (byte) 0xFF, (byte) 0x41), boxed(result.stdoutBytes()));
        assertEquals(0, result.stderrBytes().length);
    }

    @Test
    void launchFailureDoesNotExposeRawArguments() {
        CommandService service = CommandService.forCommand("procwright-missing-executable-" + System.nanoTime());

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
        CommandResult result = fixtureService()
                .run(call -> call.args("exit", "--stdout=ok\n").timeout(Duration.ofSeconds(Long.MAX_VALUE)));

        assertTrue(result.succeeded());
        assertStdoutEquals("ok\n", result);
    }

    @Test
    void zeroTimeoutDisablesRunTimeoutAndAwaitsCompletion() {
        CommandResult result = fixtureService().run(call -> call.args("sleep", "--millis=300", "--finished=true")
                .timeout(Duration.ZERO));

        assertTrue(result.succeeded());
        assertFalse(result.timedOut());
        assertTrue(normalizeLineEndings(result.stdout()).contains("finished\n"));
    }

    @Test
    void negativeTimeoutIsRejectedBeforeLaunch() {
        assertThrows(IllegalArgumentException.class, () -> fixtureService()
                .run(call -> call.args("exit").timeout(Duration.ofMillis(-1))));
    }

    @Test
    void fileCaptureWritesLargeOutputWithEmptyResultStreams(@TempDir Path directory) throws IOException {
        Path stdoutFile = directory.resolve("stdout.log");
        Path stderrFile = directory.resolve("stderr.log");

        CommandResult result = fixtureService().run(call -> call.args(
                        "burst", "--stdout-bytes=2m", "--stdout-byte=x", "--stderr-bytes=64", "--stderr-byte=e")
                .capture(CapturePolicy.toPath(stdoutFile, stderrFile))
                .timeout(Duration.ofSeconds(30)));

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
        CommandResult result = fixtureService().run(call -> call.args("burst", "--stdout-bytes=2m", "--stdout-byte=x")
                .capture(CapturePolicy.discard())
                .timeout(Duration.ofSeconds(30)));

        assertTrue(result.succeeded());
        assertStdoutEquals("", result);
        assertStderrEquals("", result);
        assertFalse(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void mergedSingleFileCaptureReceivesBothStreams(@TempDir Path directory) throws IOException {
        Path mergedFile = directory.resolve("merged.log");

        CommandResult result = fixtureService().run(call -> call.args("exit", "--stdout=out\n", "--stderr=err\n")
                .output(OutputMode.MERGED)
                .capture(CapturePolicy.toPath(mergedFile)));

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
                .run(call -> call.args("exit").capture(CapturePolicy.toPath(mergedFile))));
        assertFalse(java.nio.file.Files.exists(mergedFile));
    }

    @Test
    void timeoutWithFileCaptureStillStopsProcessAndReportsTimedOut(@TempDir Path directory) {
        Path stdoutFile = directory.resolve("stdout.log");
        Path stderrFile = directory.resolve("stderr.log");

        CommandResult result = fixtureService().run(call -> call.args("sleep", "--millis=5000", "--finished=false")
                .capture(CapturePolicy.toPath(stdoutFile, stderrFile))
                .timeout(timeoutAfterFixtureStartup())
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

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

        CommandResult result = fixtureService().run(call -> call.args("stdin-echo", "--mode=bytes-count")
                .input(io.github.ulviar.procwright.command.CommandInput.fromPath(stdinFile))
                .timeout(Duration.ofSeconds(30)));

        assertTrue(result.succeeded());
        assertStdoutEquals("bytes:" + payload.length + "\n", result);
    }

    @Test
    void stdinFromMissingFileFailsWithTypedLaunchFailure(@TempDir Path directory) {
        Path missing = directory.resolve("missing-input.bin");

        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class, () -> fixtureService().run(call -> call.args("stdin-echo")
                        .input(io.github.ulviar.procwright.command.CommandInput.fromPath(missing))));

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, exception.reason());
    }

    @Test
    void outputIsDecodedWithConfiguredCharset() {
        CommandResult result = fixtureService()
                .run(call -> call.args("binary", "--pattern=hex", "--hex=d09fd180d0b8d0b2d0b5d1820a")
                        .charset(StandardCharsets.UTF_8));

        assertStdoutEquals("Привет\n", result);
    }

    @Test
    void strictCharsetPolicyReportsDecodeErrorAsTypedFailure() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run(call -> call.args("binary", "--pattern=hex", "--hex=ff")
                        .charsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))));

        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, exception.reason());
    }

    @Test
    void capturedOutputPreservesEmittedNewlineStyleWithoutNormalization() {
        CommandResult lf = fixtureService().run(call -> call.args("platform-newlines", "--style=lf"));
        CommandResult crlf = fixtureService().run(call -> call.args("platform-newlines", "--style=crlf"));
        CommandResult crOnly = fixtureService().run(call -> call.args("platform-newlines", "--style=cr"));

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
                service.run(call -> call.args("never-exit").timeout(Duration.ZERO));
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
    void shutdownEscalationForceKillsProcessThatSurvivesInterruptSignal(@TempDir Path directory) throws Exception {
        if (isWindows()) {
            // destroy() is already forceful on Windows, so there is no SIGTERM-then-KILL escalation to prove.
            return;
        }
        Path hookFile = directory.resolve("shutdown-hook.txt");
        Duration interruptGrace = Duration.ofSeconds(2);
        AtomicLong childPid = new AtomicLong(-1);
        CommandService service =
                fixtureService(ProcessKernel.withPostStartHook(process -> childPid.set(process.pid())));

        // The shutdown hook blocks for 60 s, so the interrupt signal alone cannot end the process;
        // only the force-kill escalation after the interrupt grace can explain a bounded, dead
        // process. The hook records its progress in a file because Process.destroy() closes the
        // parent-side stdout pipe, so post-signal stdout never reaches the captured result. The
        // 2 s run timeout leaves the fixture JVM time to register its hook before the signal.
        java.time.Instant stopStarted = java.time.Instant.now();
        CommandResult result =
                service.run(call -> call.args("shutdown-hook", "--hook-delay-millis=60000", "--hook-file=" + hookFile)
                        .timeout(Duration.ofSeconds(2))
                        .shutdown(ShutdownPolicy.interruptThenKill(interruptGrace, Duration.ofSeconds(5))));
        Duration wallClockElapsed = Duration.between(stopStarted, java.time.Instant.now());

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("started\n", result);
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
    void runScenarioClosesStdinByDefault() {
        CommandResult result = fixtureService().run(call -> call.args("stdin-echo", "--mode=bytes-count"));

        assertTrue(result.succeeded());
        assertStdoutEquals("bytes:0\n", result);
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
        CommandResult result = fixtureService().run(call -> call.args("stdin-echo", "--mode=hex")
                .input("é", Charset.forName("ISO-8859-1"))
                .charset(StandardCharsets.US_ASCII));

        assertTrue(result.succeeded());
        assertStdoutEquals("e9\n", result);
    }

    @Test
    void timeoutStopsProcessAndReturnsDiagnosticResult() {
        CommandResult result = fixtureService().run(call -> call.args("sleep", "--millis=5000", "--finished=false")
                .timeout(timeoutAfterFixtureStartup())
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertStdoutEquals("started\n", result);
    }

    @Test
    void timeoutIsEnforcedWhileWritingInput() {
        java.time.Instant started = java.time.Instant.now();
        CommandResult result = fixtureService().run(call -> call.args("ignore-stdin", "--millis=5000")
                .input("x".repeat(8 * 1024 * 1024))
                .timeout(Duration.ofMillis(100))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));
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
                .run(call -> call.args("long-run", "--ticks=100000", "--interval-millis=20", "--stderr-every=1")
                        .timeout(timeoutAfterFixtureStartup())
                        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250))));
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
                .run(call -> call.args("spawn-child", "--child-scenario=sleep", "--child-millis=10000", "--wait=true")
                        .timeout(descendantStartupTimeout())
                        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(500))));
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
                () -> service.run(call -> call.args("sleep", "--millis=5000", "--finished=false")
                        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250)))));
        Duration wallClockElapsed = Duration.between(started, java.time.Instant.now());

        assertEquals(failure, exception);
        assertTrue(childPid.get() > 0);
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(wallClockElapsed.compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void processWritingLargeStderrDoesNotBlockStdoutCompletion() {
        CommandResult result = fixtureService().run(call -> call.args(
                        "burst",
                        "--stdout-first=false",
                        "--stdout-bytes=5",
                        "--stdout-byte=d",
                        "--stderr-bytes=2m",
                        "--stderr-byte=e")
                .capture(CapturePolicy.bounded(1024))
                .timeout(Duration.ofSeconds(5)));

        assertTrue(result.succeeded());
        assertStdoutEquals("ddddd", result);
        assertTrue(result.stderrTruncated());
    }

    @Test
    void orphanedDescendantHoldingOutputPipeIsKilledAndFailureExplainsCause(@TempDir Path directory) throws Exception {
        if (isWindows()) {
            return;
        }
        Path pidFile = directory.resolve("grandchild.pid");

        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class, () -> fixtureService().run(call -> call.args(
                                "spawn-child",
                                "--child-scenario=never-exit",
                                "--inherit-output=true",
                                "--pid-file=" + pidFile,
                                "--linger-millis=500")
                        .timeout(Duration.ofSeconds(10))));

        assertTrue(
                exception.getMessage().contains("a descendant process that inherited stdout or stderr"),
                () -> "failure message must explain the orphaned pipe holder: " + exception.getMessage());
        long orphanPid = Long.parseLong(java.nio.file.Files.readString(pidFile).trim());
        assertProcessEventuallyStops(orphanPid);
    }

    @Test
    void shellModeIsExplicitAndReceivesEnvironmentOverride() {
        CommandService shell = CommandService.forShellCommand(shellEchoEnvironmentCommand("PROCWRIGHT_SHELL_VALUE"));

        CommandResult result = shell.run(call -> call.putEnvironment("PROCWRIGHT_SHELL_VALUE", "configured"));

        assertTrue(result.succeeded());
        assertStdoutEquals("shell:configured\n", result);
    }

    private static CommandService fixtureService() {
        return fixtureService(ProcessKernel.standard());
    }

    private static CommandService fixtureService(ProcessKernel processKernel) {
        return new CommandService(
                TestCliSupport.command(),
                RunOptions.defaults(),
                SessionOptions.defaults(),
                LineSessionOptions.defaults(),
                StreamOptions.defaults(),
                PooledLineSessionOptions.defaults(),
                ProtocolSessionOptions.defaults(),
                PooledProtocolSessionOptions.defaults(),
                DiagnosticsOptions.defaults(),
                processKernel);
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

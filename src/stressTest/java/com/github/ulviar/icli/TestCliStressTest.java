package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ulviar.icli.command.CapturePolicy;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.command.CommandSpec;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.command.ShutdownPolicy;
import com.github.ulviar.icli.session.LineSessionException;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledLineSessionMetrics;
import com.github.ulviar.icli.session.SessionOptions;
import com.github.ulviar.icli.session.StreamSession;
import com.github.ulviar.icli.testcli.TestCli;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 45, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
final class TestCliStressTest {
    @Test
    void parallelBurstProcessesKeepBothStreamsBounded() throws Exception {
        CommandService service = testCliService();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> service.run(call -> call.args(
                                "burst", "--stdout-bytes=2m", "--stderr-bytes=2m", "--stdout-byte=O", "--stderr-byte=E")
                        .capture(CapturePolicy.bounded(16 * 1024))
                        .timeout(Duration.ofSeconds(8)))));
            }

            for (Future<CommandResult> future : futures) {
                CommandResult result = future.get(12, TimeUnit.SECONDS);
                assertTrue(result.succeeded());
                assertEquals(16 * 1024, result.stdoutBytes().length);
                assertEquals(16 * 1024, result.stderrBytes().length);
                assertTrue(result.stdoutTruncated());
                assertTrue(result.stderrTruncated());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void seededFlakyProcessesProduceMixedOutcomesWithoutTimeouts() throws Exception {
        CommandService service = testCliService();
        ExecutorService executor = Executors.newCachedThreadPool();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int seed = 1; seed <= 40; seed++) {
                int currentSeed = seed;
                futures.add(executor.submit(() -> service.run(call -> call.args(
                                "flaky",
                                "--seed=" + currentSeed,
                                "--fail-percent=50",
                                "--hang-percent=0",
                                "--max-delay-millis=5",
                                "--failure-exit-code=75")
                        .capture(CapturePolicy.bounded(4 * 1024))
                        .timeout(Duration.ofSeconds(8)))));
            }

            for (Future<CommandResult> future : futures) {
                CommandResult result = future.get(12, TimeUnit.SECONDS);
                assertFalse(result.timedOut());
                OptionalInt exitCode = result.exitCode();
                assertTrue(exitCode.isPresent());
                if (exitCode.orElseThrow() == 0) {
                    succeeded.incrementAndGet();
                    assertTrue(result.stdout().startsWith("flaky:ok:"));
                } else {
                    failed.incrementAndGet();
                    assertEquals(75, exitCode.orElseThrow());
                    assertTrue(result.stderr().startsWith("flaky:failed:"));
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertTrue(succeeded.get() > 0, "expected at least one deterministic success");
        assertTrue(failed.get() > 0, "expected at least one deterministic failure");
    }

    @Test
    void hangingFlakyProcessesTimeoutAndCleanUpUnderChurn() throws Exception {
        CommandService service = testCliService();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int index = 0; index < hangingFlakyParallelism(); index++) {
                futures.add(executor.submit(() -> service.run(call -> call.args(
                                "flaky",
                                "--seed=1",
                                "--hang-percent=100",
                                "--fail-percent=0",
                                "--started-text=flaky-hang")
                        .capture(CapturePolicy.bounded(1024))
                        .timeout(hangingFlakyTimeout())
                        .shutdown(hangingFlakyShutdown()))));
            }

            for (Future<CommandResult> future : futures) {
                CommandResult result = future.get(hangingFlakyWaitSeconds(), TimeUnit.SECONDS);
                assertTrue(result.timedOut());
                assertFalse(result.succeeded());
                String stdout = normalizeLineEndings(result.stdout());
                assertTrue(stdout.isEmpty() || stdout.startsWith("flaky-hang\n"), () -> "unexpected stdout: " + stdout);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutStopsWaitingParentAndSpawnedChildProcess() throws Exception {
        CommandResult result = testCliService()
                .run(call -> call.args("spawn-child", "--child-scenario=never-exit", "--wait=true")
                        .capture(CapturePolicy.bounded(4 * 1024))
                        .timeout(Duration.ofSeconds(1))
                        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(20), Duration.ofMillis(500))));

        assertTrue(result.timedOut());
        long childPid = parseChildPid(result.stdout());
        assertProcessEventuallyStops(childPid);
    }

    @Test
    void timeoutStopsWaitingTreeWithGrandchildProcess() throws Exception {
        CommandResult result = testCliService()
                .run(call -> call.args("spawn-tree", "--leaf-scenario=never-exit", "--wait=true")
                        .capture(CapturePolicy.bounded(4 * 1024))
                        .timeout(Duration.ofSeconds(1))
                        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(20), Duration.ofMillis(500))));

        assertTrue(result.timedOut());
        long childPid = parsePid(result.stdout(), "child:");
        long grandchildPid = parsePid(result.stdout(), "grandchild:");
        assertProcessEventuallyStops(childPid);
        assertProcessEventuallyStops(grandchildPid);
    }

    @Test
    void longRunningStreamCompletesWithSlowListenerBackpressure() throws Exception {
        AtomicInteger chunks = new AtomicInteger();

        try (StreamSession stream = testCliService()
                .listen(call -> call.args("long-run", "--ticks=8", "--interval-millis=1", "--stderr-every=2")
                        .timeout(Duration.ofSeconds(5))
                        .onOutput(chunk -> {
                            chunks.incrementAndGet();
                            try {
                                TimeUnit.MILLISECONDS.sleep(5);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError("listener interrupted", exception);
                            }
                        }))) {
            assertEquals(0, stream.onExit().get(10, TimeUnit.SECONDS).exitCode().orElseThrow());
        }

        assertTrue(chunks.get() > 0);
    }

    @Test
    void mixedLoadProcessesCompleteUnderParallelChurn() throws Exception {
        CommandService service = testCliService();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> service.run(call -> call.args(
                                "mixed-load", "--ticks=3", "--cpu-millis=5", "--interval-millis=0", "--memory-bytes=1m")
                        .capture(CapturePolicy.bounded(8 * 1024))
                        .timeout(Duration.ofSeconds(5)))));
            }

            for (Future<CommandResult> future : futures) {
                CommandResult result = future.get(8, TimeUnit.SECONDS);
                assertTrue(result.succeeded());
                assertTrue(result.stdout().contains("load:0:ops:"));
                assertTrue(result.stdout().contains(":memory:1048576"));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void repeatedSpawnLoopCompletesWithoutSupervisorTimeout() {
        CommandResult result = testCliService().run(call -> call.args(
                        "repeat-spawn", "--count=8", "--child-scenario=exit", "--child-arg=--exit-code=0")
                .capture(CapturePolicy.bounded(4 * 1024))
                .timeout(Duration.ofSeconds(5)));

        assertTrue(result.succeeded());
        assertTrue(result.stdout().contains("iteration:7:exit:0"));
    }

    @Test
    void pooledLineSessionRetiresTimedOutWorkersAndKeepsServingRequests() throws Exception {
        try (PooledLineSession pool = testCliLineService()
                .pooled(call ->
                        call.args("line-repl").maxSize(2).warmupSize(1).acquireTimeout(Duration.ofSeconds(2)))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch start = new CountDownLatch(1);
            try {
                ArrayList<Future<String>> futures = new ArrayList<>();
                for (int index = 0; index < 10; index++) {
                    int requestIndex = index;
                    futures.add(executor.submit(() -> {
                        start.await();
                        if (requestIndex % 2 == 0) {
                            try {
                                pool.request(":sleep 250", Duration.ofMillis(40));
                                throw new AssertionError("expected pooled request timeout");
                            } catch (LineSessionException exception) {
                                assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
                                return "timeout";
                            }
                        }
                        return pool.request("ok-" + requestIndex, Duration.ofSeconds(2))
                                .text();
                    }));
                }

                start.countDown();
                int timeouts = 0;
                int successes = 0;
                for (Future<String> future : futures) {
                    String outcome = future.get(10, TimeUnit.SECONDS);
                    if ("timeout".equals(outcome)) {
                        timeouts++;
                    } else {
                        successes++;
                        assertTrue(outcome.startsWith("response:ok-"));
                    }
                }

                PooledLineSessionMetrics metrics = pool.metrics();
                assertEquals(5, timeouts);
                assertEquals(5, successes);
                assertEquals(5, metrics.completedRequests());
                assertEquals(5, metrics.failedRequests());
                assertTrue(metrics.created() >= metrics.retired());
                assertTrue(metrics.size() <= 2);
                assertEquals(0, metrics.leased());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static CommandService testCliService() {
        return new CommandService(testCliCommand(), RunOptions.defaults());
    }

    private static CommandService testCliLineService() {
        return new CommandService(
                testCliCommand(),
                RunOptions.defaults(),
                SessionOptions.defaults(),
                LineSessionOptions.defaults().withRequestTimeout(Duration.ofSeconds(2)));
    }

    private static CommandSpec testCliCommand() {
        return CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), TestCli.class.getName())
                .build();
    }

    private static long parseChildPid(String stdout) {
        return parsePid(stdout, "child:");
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    private static long parsePid(String stdout, String prefix) {
        for (String line : stdout.lines().toList()) {
            if (line.startsWith(prefix)) {
                return Long.parseLong(line.substring(prefix.length()));
            }
        }
        throw new AssertionError("missing pid with prefix " + prefix + " in stdout: " + stdout);
    }

    private static void assertProcessEventuallyStops(long pid) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        fail("child process remained alive: " + pid);
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static int hangingFlakyParallelism() {
        return isWindows() ? 8 : 16;
    }

    private static Duration hangingFlakyTimeout() {
        return isWindows() ? Duration.ofMillis(300) : Duration.ofMillis(80);
    }

    private static ShutdownPolicy hangingFlakyShutdown() {
        Duration interruptGrace = isWindows() ? Duration.ofMillis(50) : Duration.ofMillis(10);
        return ShutdownPolicy.interruptThenKill(interruptGrace, Duration.ofSeconds(2));
    }

    private static long hangingFlakyWaitSeconds() {
        return isWindows() ? 20 : 12;
    }
}

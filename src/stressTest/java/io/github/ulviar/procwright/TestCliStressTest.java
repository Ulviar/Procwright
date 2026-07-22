/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.testcli.TestCli;
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
                futures.add(executor.submit(() -> service.run()
                        .withArgs(
                                "burst", "--stdout-bytes=2m", "--stderr-bytes=2m", "--stdout-byte=O", "--stderr-byte=E")
                        .withCapture(CapturePolicy.bounded(16 * 1024))
                        .withTimeout(Duration.ofSeconds(8))
                        .execute()));
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
                futures.add(executor.submit(() -> service.run()
                        .withArgs(
                                "flaky",
                                "--seed=" + currentSeed,
                                "--fail-percent=50",
                                "--hang-percent=0",
                                "--max-delay-millis=5",
                                "--failure-exit-code=75")
                        .withCapture(CapturePolicy.bounded(4 * 1024))
                        .withTimeout(Duration.ofSeconds(8))
                        .execute()));
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
                futures.add(executor.submit(() -> service.run()
                        .withArgs(
                                "flaky",
                                "--seed=1",
                                "--hang-percent=100",
                                "--fail-percent=0",
                                "--started-text=flaky-hang")
                        .withCapture(CapturePolicy.bounded(1024))
                        .withTimeout(hangingFlakyTimeout())
                        .withShutdown(hangingFlakyShutdown())
                        .execute()));
            }

            for (Future<CommandResult> future : futures) {
                CommandResult result = future.get(hangingFlakyWaitSeconds(), TimeUnit.SECONDS);
                assertTrue(result.timedOut());
                assertFalse(result.succeeded());
                assertTrue(result.stdoutBytes().length <= 1024);
                assertTrue(result.stderrBytes().length <= 1024);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutStopsWaitingParentAndSpawnedChildProcess() throws Exception {
        CommandResult result = testCliService()
                .run()
                .withArgs("spawn-child", "--child-scenario=never-exit", "--wait=true")
                .withCapture(CapturePolicy.bounded(4 * 1024))
                .withTimeout(processTreeTimeout())
                .withShutdown(processTreeShutdown())
                .execute();

        assertTrue(result.timedOut());
        long childPid = parseChildPid(result.stdout());
        assertProcessEventuallyStops(childPid);
    }

    @Test
    void timeoutStopsWaitingTreeWithGrandchildProcess() throws Exception {
        CommandResult result = testCliService()
                .run()
                .withArgs("spawn-tree", "--leaf-scenario=never-exit", "--wait=true")
                .withCapture(CapturePolicy.bounded(4 * 1024))
                .withTimeout(processTreeTimeout())
                .withShutdown(processTreeShutdown())
                .execute();

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
                .listen()
                .withArgs("long-run", "--ticks=8", "--interval-millis=1", "--stderr-every=2")
                .withTimeout(Duration.ofSeconds(5))
                .onOutput(chunk -> {
                    chunks.incrementAndGet();
                    try {
                        TimeUnit.MILLISECONDS.sleep(5);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("listener interrupted", exception);
                    }
                })
                .open()) {
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
                futures.add(executor.submit(() -> service.run()
                        .withArgs(
                                "mixed-load", "--ticks=3", "--cpu-millis=5", "--interval-millis=0", "--memory-bytes=1m")
                        .withCapture(CapturePolicy.bounded(8 * 1024))
                        .withTimeout(Duration.ofSeconds(5))
                        .execute()));
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
        CommandResult result = testCliService()
                .run()
                .withArgs("repeat-spawn", "--count=8", "--child-scenario=exit", "--child-arg=--exit-code=0")
                .withCapture(CapturePolicy.bounded(4 * 1024))
                .withTimeout(Duration.ofSeconds(5))
                .execute();

        assertTrue(result.succeeded());
        assertTrue(result.stdout().contains("iteration:7:exit:0"));
    }

    @Test
    void pooledLineSessionRetiresTimedOutWorkersAndKeepsServingRequests() throws Exception {
        PooledLineSession pool = testCliService()
                .lineSession()
                .withArgs("line-repl")
                .withRequestTimeout(Duration.ofSeconds(2))
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withAcquireTimeout(Duration.ofSeconds(2))
                .open();
        try {
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
                assertEquals(
                        metrics.size(), metrics.idle() + metrics.leased() + metrics.starting() + metrics.retiring());
                assertTrue(metrics.size() <= 2);
                assertEquals(0, metrics.leased());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            pool.close();
            PooledLineSessionMetrics drained = pool.metrics();
            assertEquals(0, drained.size());
            assertEquals(0, drained.idle());
            assertEquals(0, drained.leased());
            assertEquals(0, drained.starting());
            assertEquals(0, drained.retiring());
        } finally {
            pool.close();
        }
    }

    @Test
    void pooledProtocolSessionRetiresTimedOutWorkersAndKeepsServingRequests() throws Exception {
        PooledProtocolSession<String, String> pool = testCliService()
                .protocolSession(TextLineAdapter::new)
                .withArgs("line-repl")
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withAcquireTimeout(Duration.ofSeconds(2))
                .open();
        try {
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
                                throw new AssertionError("expected pooled protocol request timeout");
                            } catch (ProtocolSessionException exception) {
                                assertEquals(ProtocolSessionException.Reason.TIMEOUT, exception.reason());
                                return "timeout";
                            }
                        }
                        return pool.request("ok-" + requestIndex, Duration.ofSeconds(2));
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

                PooledProtocolSessionMetrics metrics = pool.metrics();
                assertEquals(5, timeouts);
                assertEquals(5, successes);
                assertEquals(5, metrics.completedRequests());
                assertEquals(5, metrics.failedRequests());
                assertTrue(metrics.created() >= metrics.retired());
                assertEquals(
                        metrics.size(), metrics.idle() + metrics.leased() + metrics.starting() + metrics.retiring());
                assertTrue(metrics.size() <= 2);
                assertEquals(0, metrics.leased());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            pool.close();
            PooledProtocolSessionMetrics drained = pool.metrics();
            assertEquals(0, drained.size());
            assertEquals(0, drained.idle());
            assertEquals(0, drained.leased());
            assertEquals(0, drained.starting());
            assertEquals(0, drained.retiring());
        } finally {
            pool.close();
        }
    }

    private static CommandService testCliService() {
        return Procwright.command(testCliCommand());
    }

    private static final class TextLineAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(64);
        }
    }

    private static CommandSpec testCliCommand() {
        return CommandSpec.of(javaExecutable())
                .withArgs("-cp", System.getProperty("java.class.path"), TestCli.class.getName());
    }

    private static long parseChildPid(String stdout) {
        return parsePid(stdout, "child:");
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

    private static Duration processTreeTimeout() {
        return isWindows() ? Duration.ofSeconds(5) : Duration.ofSeconds(1);
    }

    private static ShutdownPolicy processTreeShutdown() {
        Duration interruptGrace = isWindows() ? Duration.ofMillis(100) : Duration.ofMillis(20);
        Duration killGrace = isWindows() ? Duration.ofSeconds(2) : Duration.ofMillis(500);
        return ShutdownPolicy.interruptThenKill(interruptGrace, killGrace);
    }

    private static ShutdownPolicy hangingFlakyShutdown() {
        Duration interruptGrace = isWindows() ? Duration.ofMillis(50) : Duration.ofMillis(10);
        return ShutdownPolicy.interruptThenKill(interruptGrace, Duration.ofSeconds(2));
    }

    private static long hangingFlakyWaitSeconds() {
        return isWindows() ? 20 : 12;
    }
}

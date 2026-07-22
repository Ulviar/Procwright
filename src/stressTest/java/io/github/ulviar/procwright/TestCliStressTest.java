/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.testcli.TestCli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

// The longest composed internal watchdog and cleanup path is below 90 seconds.
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
final class TestCliStressTest {
    private static final int COMMAND_PARALLELISM = 3;
    private static final int POOL_REQUEST_PARALLELISM = 4;
    private static final Duration SUCCESSFUL_COMMAND_TIMEOUT = Duration.ofSeconds(35);
    private static final Duration SUCCESSFUL_COMMAND_WATCHDOG = Duration.ofSeconds(30);
    private static final Duration EXPECTED_TIMEOUT_WATCHDOG = Duration.ofSeconds(20);
    private static final Duration POOL_ACQUIRE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POOL_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POOL_CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POOL_BATCH_WATCHDOG = Duration.ofSeconds(30);
    private static final Duration RESOURCE_CLEANUP_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void parallelBurstProcessesKeepBothStreamsBounded() throws Exception {
        CommandService service = testCliService();
        try (BoundedExecutor executor = new BoundedExecutor(COMMAND_PARALLELISM)) {
            CountDownLatch ready = new CountDownLatch(COMMAND_PARALLELISM);
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.run()
                            .withArgs(
                                    "burst",
                                    "--stdout-bytes=2m",
                                    "--stderr-bytes=2m",
                                    "--stdout-byte=O",
                                    "--stderr-byte=E")
                            .withCapture(CapturePolicy.bounded(16 * 1024))
                            .withTimeout(SUCCESSFUL_COMMAND_TIMEOUT)
                            .execute();
                }));
            }
            startBatch(ready, start);

            for (CommandResult result : awaitAll(futures, SUCCESSFUL_COMMAND_WATCHDOG)) {
                String diagnostics = resultDiagnostics(result);
                assertTrue(result.succeeded(), diagnostics);
                assertEquals(16 * 1024, result.stdoutBytes().length, diagnostics);
                assertEquals(16 * 1024, result.stderrBytes().length, diagnostics);
                assertTrue(result.stdoutTruncated(), diagnostics);
                assertTrue(result.stderrTruncated(), diagnostics);
            }
        }
    }

    @Test
    void seededFlakyProcessesProduceMixedOutcomesWithoutTimeouts() throws Exception {
        CommandService service = testCliService();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (BoundedExecutor executor = new BoundedExecutor(COMMAND_PARALLELISM)) {
            CountDownLatch ready = new CountDownLatch(COMMAND_PARALLELISM);
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int seed = 1; seed <= 40; seed++) {
                int currentSeed = seed;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.run()
                            .withArgs(
                                    "flaky",
                                    "--seed=" + currentSeed,
                                    "--fail-percent=50",
                                    "--hang-percent=0",
                                    "--max-delay-millis=5",
                                    "--failure-exit-code=75")
                            .withCapture(CapturePolicy.bounded(4 * 1024))
                            .withTimeout(SUCCESSFUL_COMMAND_TIMEOUT)
                            .execute();
                }));
            }
            startBatch(ready, start);

            for (CommandResult result : awaitAll(futures, SUCCESSFUL_COMMAND_WATCHDOG)) {
                String diagnostics = resultDiagnostics(result);
                assertFalse(result.timedOut(), diagnostics);
                OptionalInt exitCode = result.exitCode();
                assertTrue(exitCode.isPresent(), diagnostics);
                if (exitCode.orElseThrow() == 0) {
                    succeeded.incrementAndGet();
                    assertTrue(result.stdout().startsWith("flaky:ok:"), diagnostics);
                } else {
                    failed.incrementAndGet();
                    assertEquals(75, exitCode.orElseThrow(), diagnostics);
                    assertTrue(result.stderr().startsWith("flaky:failed:"), diagnostics);
                }
            }
        }

        assertTrue(succeeded.get() > 0, "expected at least one deterministic success");
        assertTrue(failed.get() > 0, "expected at least one deterministic failure");
    }

    @Test
    void hangingFlakyProcessesTimeoutAndCleanUpUnderChurn() throws Exception {
        CommandService service = testCliService();
        try (BoundedExecutor executor = new BoundedExecutor(COMMAND_PARALLELISM)) {
            CountDownLatch ready = new CountDownLatch(COMMAND_PARALLELISM);
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int index = 0; index < hangingFlakyIterations(); index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.run()
                            .withArgs(
                                    "flaky",
                                    "--seed=1",
                                    "--hang-percent=100",
                                    "--fail-percent=0",
                                    "--started-text=flaky-hang")
                            .withCapture(CapturePolicy.bounded(1024))
                            .withTimeout(hangingFlakyTimeout())
                            .withShutdown(hangingFlakyShutdown())
                            .execute();
                }));
            }
            startBatch(ready, start);

            for (CommandResult result : awaitAll(futures, hangingFlakyWatchdog())) {
                String diagnostics = resultDiagnostics(result);
                assertTrue(result.timedOut(), diagnostics);
                assertFalse(result.succeeded(), diagnostics);
                assertTrue(result.stdoutBytes().length <= 1024, diagnostics);
                assertTrue(result.stderrBytes().length <= 1024, diagnostics);
            }
        }
    }

    @Test
    void timeoutStopsWaitingParentAndSpawnedChildProcess() throws Exception {
        Path childIdentityFile = temporaryDirectory.resolve("spawn-child.identity");
        try (TrackedProcesses processes = new TrackedProcesses()) {
            CommandResult result = executeWithTrackedProcesses(
                    () -> testCliService()
                            .run()
                            .withArgs(
                                    "spawn-child",
                                    "--child-scenario=never-exit",
                                    "--wait=true",
                                    "--identity-file=" + childIdentityFile)
                            .withCapture(CapturePolicy.bounded(4 * 1024))
                            .withTimeout(processTreeTimeout())
                            .withShutdown(processTreeShutdown())
                            .execute(),
                    EXPECTED_TIMEOUT_WATCHDOG,
                    processes,
                    childIdentityFile);
            TrackedProcess child = processes.process(0);
            long childPid = child.pid();
            assertTrue(result.timedOut(), resultDiagnostics(result));
            assertTrue(result.stdout().contains("child:" + childPid), resultDiagnostics(result));
            assertProcessEventuallyStops(child);
        }
    }

    @Test
    void timeoutStopsWaitingTreeWithGrandchildProcess() throws Exception {
        Path childIdentityFile = temporaryDirectory.resolve("spawn-tree-child.identity");
        Path grandchildIdentityFile = temporaryDirectory.resolve("spawn-tree-grandchild.identity");
        try (TrackedProcesses processes = new TrackedProcesses()) {
            CommandResult result = executeWithTrackedProcesses(
                    () -> testCliService()
                            .run()
                            .withArgs(
                                    "spawn-tree",
                                    "--leaf-scenario=never-exit",
                                    "--wait=true",
                                    "--wait-millis=60000",
                                    "--child-identity-file=" + childIdentityFile,
                                    "--grandchild-identity-file=" + grandchildIdentityFile)
                            .withCapture(CapturePolicy.bounded(4 * 1024))
                            .withTimeout(processTreeTimeout())
                            .withShutdown(processTreeShutdown())
                            .execute(),
                    EXPECTED_TIMEOUT_WATCHDOG,
                    processes,
                    childIdentityFile,
                    grandchildIdentityFile);
            TrackedProcess child = processes.process(0);
            TrackedProcess grandchild = processes.process(1);
            long childPid = child.pid();
            long grandchildPid = grandchild.pid();
            assertTrue(result.timedOut(), resultDiagnostics(result));
            assertTrue(result.stdout().contains("child:" + childPid), resultDiagnostics(result));
            assertTrue(result.stdout().contains("grandchild:" + grandchildPid), resultDiagnostics(result));
            assertProcessEventuallyStops(child);
            assertProcessEventuallyStops(grandchild);
        }
    }

    @Test
    void longRunningStreamCompletesWithSlowListenerBackpressure() throws Exception {
        AtomicInteger chunks = new AtomicInteger();

        try (StreamSession stream = testCliService()
                .listen()
                .withArgs("long-run", "--ticks=8", "--interval-millis=1", "--stderr-every=2")
                .withTimeout(SUCCESSFUL_COMMAND_TIMEOUT)
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
            assertEquals(
                    0,
                    stream.onExit()
                            .get(SUCCESSFUL_COMMAND_WATCHDOG.toNanos(), TimeUnit.NANOSECONDS)
                            .exitCode()
                            .orElseThrow());
        }

        assertTrue(chunks.get() > 0);
    }

    @Test
    void mixedLoadProcessesCompleteUnderParallelChurn() throws Exception {
        CommandService service = testCliService();
        try (BoundedExecutor executor = new BoundedExecutor(COMMAND_PARALLELISM)) {
            CountDownLatch ready = new CountDownLatch(COMMAND_PARALLELISM);
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.run()
                            .withArgs(
                                    "mixed-load",
                                    "--ticks=3",
                                    "--cpu-millis=5",
                                    "--interval-millis=0",
                                    "--memory-bytes=1m")
                            .withCapture(CapturePolicy.bounded(8 * 1024))
                            .withTimeout(SUCCESSFUL_COMMAND_TIMEOUT)
                            .execute();
                }));
            }
            startBatch(ready, start);

            for (CommandResult result : awaitAll(futures, SUCCESSFUL_COMMAND_WATCHDOG)) {
                String diagnostics = resultDiagnostics(result);
                assertTrue(result.succeeded(), diagnostics);
                assertTrue(result.stdout().contains("load:0:ops:"), diagnostics);
                assertTrue(result.stdout().contains(":memory:1048576"), diagnostics);
            }
        }
    }

    @Test
    void repeatedSpawnLoopCompletesWithoutSupervisorTimeout() throws Exception {
        CommandResult result = executeWithWatchdog(
                () -> testCliService()
                        .run()
                        .withArgs("repeat-spawn", "--count=8", "--child-scenario=exit", "--child-arg=--exit-code=0")
                        .withCapture(CapturePolicy.bounded(4 * 1024))
                        .withTimeout(SUCCESSFUL_COMMAND_TIMEOUT)
                        .execute(),
                SUCCESSFUL_COMMAND_WATCHDOG);

        String diagnostics = resultDiagnostics(result);
        assertTrue(result.succeeded(), diagnostics);
        assertTrue(result.stdout().contains("iteration:7:exit:0"), diagnostics);
    }

    @Test
    void pooledLineSessionRetiresTimedOutWorkersAndKeepsServingRequests() throws Exception {
        PooledLineSession pool = testCliService()
                .lineSession()
                .withArgs("line-repl")
                .withRequestTimeout(POOL_REQUEST_TIMEOUT)
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withAcquireTimeout(POOL_ACQUIRE_TIMEOUT)
                .withCloseTimeout(POOL_CLOSE_TIMEOUT)
                .open();
        try (CloseAction poolCleanup = new CloseAction(pool::close);
                BoundedExecutor executor = new BoundedExecutor(POOL_REQUEST_PARALLELISM)) {
            LineSessionException timeout =
                    assertThrows(LineSessionException.class, () -> pool.request(":sleep 250", Duration.ofMillis(40)));
            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            awaitLineRetirement(pool);
            long createdAfterRetirement = pool.metrics().created();

            assertEquals(
                    "response:replacement",
                    pool.request("replacement", POOL_REQUEST_TIMEOUT).text());
            assertTrue(pool.metrics().created() > createdAfterRetirement);

            CountDownLatch ready = new CountDownLatch(POOL_REQUEST_PARALLELISM);
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                int requestIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return pool.request("ok-" + requestIndex, POOL_REQUEST_TIMEOUT)
                            .text();
                }));
            }
            startBatch(ready, start);

            assertEquals(
                    expectedPoolResponses(),
                    awaitAll(futures, POOL_BATCH_WATCHDOG).stream().sorted().toList());

            PooledLineSessionMetrics metrics = pool.metrics();
            assertEquals(9, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertTrue(metrics.created() >= 2);
            assertTrue(metrics.retired() >= 1);
            assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.TIMEOUT));
            assertEquals(metrics.size(), metrics.idle() + metrics.leased() + metrics.starting() + metrics.retiring());
            assertTrue(metrics.size() <= 2);
            assertEquals(0, metrics.leased());
            poolCleanup.runNow();
            PooledLineSessionMetrics drained = pool.metrics();
            assertEquals(0, drained.size());
            assertEquals(0, drained.idle());
            assertEquals(0, drained.leased());
            assertEquals(0, drained.starting());
            assertEquals(0, drained.retiring());
        }
    }

    @Test
    void pooledProtocolSessionRetiresTimedOutWorkersAndKeepsServingRequests() throws Exception {
        PooledProtocolSession<String, String> pool = testCliService()
                .protocolSession(TextLineAdapter::new)
                .withArgs("line-repl")
                .withRequestTimeout(POOL_REQUEST_TIMEOUT)
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withAcquireTimeout(POOL_ACQUIRE_TIMEOUT)
                .withCloseTimeout(POOL_CLOSE_TIMEOUT)
                .open();
        try (CloseAction poolCleanup = new CloseAction(pool::close);
                BoundedExecutor executor = new BoundedExecutor(POOL_REQUEST_PARALLELISM)) {
            ProtocolSessionException timeout = assertThrows(
                    ProtocolSessionException.class, () -> pool.request(":sleep 250", Duration.ofMillis(40)));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            awaitProtocolRetirement(pool);
            long createdAfterRetirement = pool.metrics().created();

            assertEquals("response:replacement", pool.request("replacement", POOL_REQUEST_TIMEOUT));
            assertTrue(pool.metrics().created() > createdAfterRetirement);

            CountDownLatch ready = new CountDownLatch(POOL_REQUEST_PARALLELISM);
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                int requestIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return pool.request("ok-" + requestIndex, POOL_REQUEST_TIMEOUT);
                }));
            }
            startBatch(ready, start);

            assertEquals(
                    expectedPoolResponses(),
                    awaitAll(futures, POOL_BATCH_WATCHDOG).stream().sorted().toList());

            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(9, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertTrue(metrics.created() >= 2);
            assertTrue(metrics.retired() >= 1);
            assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.TIMEOUT));
            assertEquals(metrics.size(), metrics.idle() + metrics.leased() + metrics.starting() + metrics.retiring());
            assertTrue(metrics.size() <= 2);
            assertEquals(0, metrics.leased());
            poolCleanup.runNow();
            PooledProtocolSessionMetrics drained = pool.metrics();
            assertEquals(0, drained.size());
            assertEquals(0, drained.idle());
            assertEquals(0, drained.leased());
            assertEquals(0, drained.starting());
            assertEquals(0, drained.retiring());
        }
    }

    private static <T> T executeWithWatchdog(Callable<T> operation, Duration timeout) throws Exception {
        try (BoundedExecutor executor = new BoundedExecutor(1)) {
            return await(executor.submit(operation), deadlineAfter(timeout));
        }
    }

    private static CommandResult executeWithTrackedProcesses(
            Callable<CommandResult> operation, Duration timeout, TrackedProcesses processes, Path... pidFiles)
            throws Exception {
        try (BoundedExecutor executor = new BoundedExecutor(1)) {
            long deadlineNanos = deadlineAfter(timeout);
            Future<CommandResult> result = executor.submit(operation);
            for (Path pidFile : pidFiles) {
                processes.trackPublished(pidFile, deadlineNanos, result);
            }
            return await(result, deadlineNanos);
        }
    }

    private static <T> ArrayList<T> awaitAll(ArrayList<? extends Future<T>> futures, Duration timeout)
            throws Exception {
        long deadlineNanos = deadlineAfter(timeout);
        ArrayList<T> results = new ArrayList<>(futures.size());
        Throwable primaryFailure = null;
        for (int index = 0; index < futures.size(); index++) {
            Future<T> future = futures.get(index);
            try {
                results.add(await(future, deadlineNanos));
            } catch (Throwable failure) {
                failure.addSuppressed(new AssertionError("stress task " + index + " of " + futures.size()
                        + " failed; completed results=" + results.size()));
                if (primaryFailure == null) {
                    primaryFailure = failure;
                } else if (failure != primaryFailure) {
                    primaryFailure.addSuppressed(failure);
                }
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (primaryFailure != null) {
            throwFailure(primaryFailure);
        }
        return results;
    }

    private static <T> T await(Future<T> future, long deadlineNanos) throws Exception {
        try {
            if (future.isDone()) {
                return future.get();
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                if (future.isDone()) {
                    return future.get();
                }
                throw new TimeoutException("external stress-test watchdog elapsed");
            }
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof InterruptedException interruptedTask) {
                throw new StressTaskInterruptedException(interruptedTask);
            }
            if (cause instanceof Exception failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new AssertionError("stress task failed", cause);
        }
    }

    private static long deadlineAfter(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static void startBatch(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        try {
            assertTrue(
                    ready.await(RESOURCE_CLEANUP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                    "stress tasks did not reach the shared start barrier");
        } finally {
            start.countDown();
        }
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("stress task failed", failure);
    }

    private static String resultDiagnostics(CommandResult result) {
        String exitCode = result.exitCode().isPresent()
                ? Integer.toString(result.exitCode().orElseThrow())
                : "unknown";
        return "exitCode=" + exitCode + ", timedOut=" + result.timedOut() + ", stdout=" + abbreviate(result.stdout())
                + ", stderr=" + abbreviate(result.stderr());
    }

    private static List<String> expectedPoolResponses() {
        return List.of(
                "response:ok-0",
                "response:ok-1",
                "response:ok-2",
                "response:ok-3",
                "response:ok-4",
                "response:ok-5",
                "response:ok-6",
                "response:ok-7");
    }

    private static String abbreviate(String text) {
        int limit = 512;
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    private static CommandService testCliService() {
        return Procwright.command(testCliCommand());
    }

    private static void awaitLineRetirement(PooledLineSession pool) throws InterruptedException {
        long deadlineNanos = deadlineAfter(RESOURCE_CLEANUP_TIMEOUT);
        while (System.nanoTime() < deadlineNanos) {
            PooledLineSessionMetrics metrics = pool.metrics();
            if (metrics.retired() >= 1 && metrics.retireReasons().get(PooledWorkerRetireReason.TIMEOUT) >= 1) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        fail("line pool did not publish timeout retirement: " + pool.metrics());
    }

    private static void awaitProtocolRetirement(PooledProtocolSession<String, String> pool)
            throws InterruptedException {
        long deadlineNanos = deadlineAfter(RESOURCE_CLEANUP_TIMEOUT);
        while (System.nanoTime() < deadlineNanos) {
            PooledProtocolSessionMetrics metrics = pool.metrics();
            if (metrics.retired() >= 1 && metrics.retireReasons().get(PooledWorkerRetireReason.TIMEOUT) >= 1) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        fail("protocol pool did not publish timeout retirement: " + pool.metrics());
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
                .withArgs("-Xms16m", "-Xmx128m", "-cp", System.getProperty("java.class.path"), TestCli.class.getName());
    }

    private static void assertProcessEventuallyStops(TrackedProcess process) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            if (!process.isAlive()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        fail("child process remained alive: " + process.pid());
    }

    private static void forceProcessToStop(TrackedProcess process) {
        if (!process.isAlive()) {
            return;
        }
        ProcessHandle handle = process.handle();
        handle.destroyForcibly();
        long deadlineNanos = deadlineAfter(RESOURCE_CLEANUP_TIMEOUT);
        boolean interrupted = false;
        try {
            while (handle.isAlive()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new AssertionError("failed to clean up child process: " + process.pid());
                }
                try {
                    handle.onExit().get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (ExecutionException exception) {
                    throw new AssertionError(
                            "failed to observe child process cleanup: " + process.pid(), exception.getCause());
                } catch (TimeoutException exception) {
                    throw new AssertionError("failed to clean up child process: " + process.pid(), exception);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (interrupted) {
            throw new AssertionError("interrupted while cleaning up child process: " + process.pid());
        }
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static int hangingFlakyIterations() {
        return isWindows() ? 8 : 16;
    }

    private static Duration hangingFlakyTimeout() {
        return isWindows() ? Duration.ofMillis(300) : Duration.ofMillis(80);
    }

    private static Duration processTreeTimeout() {
        return Duration.ofSeconds(5);
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

    private static Duration hangingFlakyWatchdog() {
        return isWindows() ? Duration.ofSeconds(20) : Duration.ofSeconds(12);
    }

    private static final class BoundedExecutor implements AutoCloseable {
        private final ExecutorService delegate;
        private final ArrayList<Future<?>> tasks = new ArrayList<>();

        private BoundedExecutor(int parallelism) {
            delegate = Executors.newFixedThreadPool(parallelism);
        }

        private <T> Future<T> submit(Callable<T> task) {
            Future<T> future = delegate.submit(task);
            tasks.add(future);
            return future;
        }

        @Override
        public void close() {
            for (Future<?> task : tasks) {
                if (!task.isDone()) {
                    task.cancel(true);
                }
            }
            delegate.shutdownNow();
            long deadlineNanos = deadlineAfter(RESOURCE_CLEANUP_TIMEOUT);
            boolean interrupted = Thread.interrupted();
            try {
                while (!delegate.isTerminated()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new AssertionError("stress executor did not terminate after cancellation");
                    }
                    try {
                        delegate.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (interrupted) {
                throw new AssertionError("interrupted while cleaning up stress executor");
            }
        }
    }

    private static final class StressTaskInterruptedException extends Exception {

        private static final long serialVersionUID = 1L;

        private StressTaskInterruptedException(InterruptedException cause) {
            super("stress worker task was interrupted", cause);
        }
    }

    private static final class CloseAction implements AutoCloseable {
        private final Runnable action;
        private boolean completed;

        private CloseAction(Runnable action) {
            this.action = action;
        }

        private void runNow() {
            if (completed) {
                return;
            }
            boolean interrupted = Thread.interrupted();
            try {
                action.run();
                completed = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() {
            runNow();
        }
    }

    private static final class TrackedProcesses implements AutoCloseable {
        private final ArrayList<TrackedProcess> processes = new ArrayList<>();

        private void trackPublished(Path pidFile, long deadlineNanos, Future<CommandResult> operation)
                throws Exception {
            while (System.nanoTime() < deadlineNanos) {
                if (operation.isDone()) {
                    CommandResult completed = await(operation, deadlineNanos);
                    throw new AssertionError("operation completed before PID side-channel publication: " + pidFile
                            + "; " + resultDiagnostics(completed));
                }
                if (Files.isRegularFile(pidFile)) {
                    List<String> identity = Files.readAllLines(pidFile);
                    if (identity.size() == 2) {
                        long pid = Long.parseLong(identity.get(0));
                        Instant publishedStart = Instant.parse(identity.get(1));
                        if (operation.isDone()) {
                            CommandResult completed = await(operation, deadlineNanos);
                            throw new AssertionError("operation completed before PID identity was bound: " + pidFile
                                    + "; " + resultDiagnostics(completed));
                        }
                        ProcessHandle handle = ProcessHandle.of(pid)
                                .orElseThrow(() -> new AssertionError("published process is not observable: " + pid));
                        Instant observedStart = handle.info()
                                .startInstant()
                                .orElseThrow(
                                        () -> new AssertionError("published process has no start identity: " + pid));
                        if (!observedStart.equals(publishedStart)) {
                            throw new AssertionError("published process identity no longer matches PID: " + pid);
                        }
                        processes.add(new TrackedProcess(handle, publishedStart));
                        return;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(25);
            }
            throw new TimeoutException("PID side channel was not published: " + pidFile);
        }

        private TrackedProcess process(int index) {
            return processes.get(index);
        }

        @Override
        public void close() {
            Throwable cleanupFailure = null;
            for (TrackedProcess process : processes) {
                try {
                    forceProcessToStop(process);
                } catch (RuntimeException | AssertionError exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                }
            }
            if (cleanupFailure instanceof RuntimeException exception) {
                throw exception;
            }
            if (cleanupFailure instanceof Error error) {
                throw error;
            }
        }
    }

    private record TrackedProcess(ProcessHandle handle, Instant startInstant) {

        private long pid() {
            return handle.pid();
        }

        private boolean isAlive() {
            if (!handle.isAlive()) {
                return false;
            }
            Optional<Instant> observedStart = handle.info().startInstant();
            if (observedStart.isPresent() && observedStart.orElseThrow().equals(startInstant)) {
                return true;
            }
            if (!handle.isAlive()) {
                return false;
            }
            throw new AssertionError("process identity changed before cleanup: " + pid());
        }
    }
}

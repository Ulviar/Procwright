package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionOptions;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
final class ProcessStressTest {

    private static final int ONE_MIB = 1024 * 1024;

    @Test
    void largeStdoutStressKeepsCaptureBounded() {
        CommandResult result = fixtureService()
                .run(call -> call.args("large-stdout", Integer.toString(16 * ONE_MIB), "x")
                        .capture(CapturePolicy.bounded(64 * 1024))
                        .timeout(Duration.ofSeconds(10)));

        assertTrue(result.succeeded());
        assertEquals(64 * 1024, result.stdoutBytes().length);
        assertTrue(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void largeStderrStressDoesNotBlockCompletionAndKeepsCaptureBounded() {
        CommandResult result = fixtureService()
                .run(call -> call.args("large-stderr", Integer.toString(8 * ONE_MIB), "e")
                        .capture(CapturePolicy.bounded(32 * 1024))
                        .timeout(Duration.ofSeconds(10)));

        assertTrue(result.succeeded());
        assertEquals("done\n", result.stdout());
        assertEquals(32 * 1024, result.stderrBytes().length);
        assertTrue(result.stderrTruncated());
    }

    @Test
    void timeoutChurnCompletesWithoutDeadlock() throws Exception {
        CommandService service = fixtureService();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            ArrayList<Future<CommandResult>> futures = new ArrayList<>();
            for (int index = 0; index < timeoutChurnParallelism(); index++) {
                futures.add(executor.submit(() -> service.run(call -> call.args("sleep", "5000")
                        .timeout(timeoutChurnTimeout())
                        .shutdown(timeoutChurnShutdown()))));
            }

            for (Future<CommandResult> future : futures) {
                CommandResult result = future.get(timeoutChurnWaitSeconds(), TimeUnit.SECONDS);
                assertTrue(result.timedOut());
                assertFalse(result.succeeded());
                String stdout = normalizeLineEndings(result.stdout());
                assertTrue(stdout.isEmpty() || stdout.startsWith("started\n"), () -> "unexpected stdout: " + stdout);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rapidSessionOpenCloseCompletesWithoutLeakingLifecycle() throws Exception {
        CommandService service = fixtureService();

        for (int index = 0; index < 24; index++) {
            try (Session session = service.interactive(call -> call.args("sleep", "5000")
                    .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(250))))) {
                session.close();
                assertFalse(session.onExit().get(2, TimeUnit.SECONDS).timedOut());
            }
        }
    }

    @Test
    void pooledContentionCompletesAllRequestsWithinMaxSize() throws Exception {
        try (PooledLineSession pool = fixtureService()
                .pooled(call -> call.args("line-repl").maxSize(3).warmupSize(1))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch start = new CountDownLatch(1);
            try {
                ArrayList<Future<String>> futures = new ArrayList<>();
                for (int index = 0; index < 12; index++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return pool.request("hold", Duration.ofSeconds(2)).text();
                    }));
                }

                start.countDown();
                for (Future<String> future : futures) {
                    assertEquals("response:hold", future.get(8, TimeUnit.SECONDS));
                }

                PooledLineSessionMetrics metrics = pool.metrics();
                assertTrue(metrics.created() <= 3);
                assertTrue(metrics.size() <= 3);
                assertTrue(metrics.idle() <= 3);
                assertEquals(0, metrics.leased());
                assertEquals(12, metrics.completedRequests());
                assertEquals(0, metrics.failedRequests());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void requiredPtyRepeatedSessionsStayUsableWhenAvailable() throws Exception {
        assumeTrue(PtyProvider.system().available(), "system PTY provider is unavailable");
        CommandService service = new CommandService(
                CommandSpec.of("sh"),
                RunOptions.defaults(),
                SessionOptions.defaults()
                        .withPtyProvider(PtyProvider.system())
                        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(50), Duration.ofMillis(500))));

        for (int index = 0; index < 5; index++) {
            try (Session session = service.interactive(
                            call -> call.terminal(TerminalPolicy.REQUIRED).args("-c", "echo pty:ok"));
                    BufferedReader stdout =
                            new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
                assertEquals("pty:ok", readUntil(session, stdout, "pty:"));
                assertEquals(
                        0, session.onExit().get(3, TimeUnit.SECONDS).exitCode().orElseThrow());
            }
        }
    }

    private static CommandService fixtureService() {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), StressFixtureProgram.class.getName())
                .build();
        return new CommandService(
                command, RunOptions.defaults(), SessionOptions.defaults(), LineSessionOptions.defaults());
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static int timeoutChurnParallelism() {
        return isWindows() ? 8 : 16;
    }

    private static Duration timeoutChurnTimeout() {
        return isWindows() ? Duration.ofMillis(250) : Duration.ofMillis(60);
    }

    private static ShutdownPolicy timeoutChurnShutdown() {
        Duration interruptGrace = isWindows() ? Duration.ofMillis(50) : Duration.ofMillis(10);
        Duration killGrace = isWindows() ? Duration.ofSeconds(1) : Duration.ofMillis(250);
        return ShutdownPolicy.interruptThenKill(interruptGrace, killGrace);
    }

    private static long timeoutChurnWaitSeconds() {
        return isWindows() ? 20 : 8;
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    private static String readUntil(Session session, BufferedReader reader, String prefix) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "procwright-stress-pty-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> match = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(prefix)) {
                        return line;
                    }
                }
                throw new AssertionError("missing line with prefix " + prefix);
            });
            try {
                return match.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                session.close();
                match.cancel(true);
                throw new AssertionError("timed out waiting for line with prefix " + prefix, exception);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }
}

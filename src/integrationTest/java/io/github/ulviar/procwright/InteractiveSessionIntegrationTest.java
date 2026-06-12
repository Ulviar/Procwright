/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.session.SessionOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class InteractiveSessionIntegrationTest {

    @Test
    void sendLineFlushesInputAndStdoutCanBeRead() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("line-repl", "--response-prefix=echo:"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            session.sendLine("hello");

            assertEquals("echo:hello", stdout.readLine());

            session.closeStdin();
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void rawStdinCanBeWrittenDirectly() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("line-repl", "--response-prefix=echo:"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            session.stdin().write("raw\n".getBytes(StandardCharsets.UTF_8));
            session.stdin().flush();

            assertEquals("echo:raw", stdout.readLine());

            session.closeStdin();
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void closeStdinSignalsEof() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("stdin-echo", "--mode=bytes-count"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            session.send("payload");
            session.closeStdin();

            assertEquals("bytes:7", stdout.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void closeStdinIsIdempotent() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("stdin-echo", "--mode=bytes-count"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            session.closeStdin();
            session.closeStdin();

            assertEquals("bytes:0", stdout.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void stderrIsAvailableAsRawStream() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("exit", "--stderr=error-line\n"));
                BufferedReader stderr =
                        new BufferedReader(new InputStreamReader(session.stderr(), StandardCharsets.UTF_8))) {
            assertEquals("error-line", stderr.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void onExitFutureViewDoesNotOwnLifecycle() {
        Session session = fixtureService().interactive(call -> call.args("sleep", "--millis=5000", "--finished=false"));

        try {
            session.onExit().complete(new SessionExit(OptionalInt.empty(), true));

            assertFalse(session.onExit().isDone());

            session.close();
            assertTrue(session.onExit().isDone());
        } finally {
            session.close();
        }
    }

    @Test
    void closeIsIdempotentAndStopsProcess() throws Exception {
        Session session = fixtureService().interactive(call -> call.args("sleep", "--millis=5000", "--finished=false"));

        session.close();
        session.close();

        assertTrue(session.onExit().get(2, TimeUnit.SECONDS).exitCode().isPresent());
    }

    @Test
    void sendAfterCloseFails() {
        Session session = fixtureService().interactive(call -> call.args("sleep", "--millis=5000", "--finished=false"));

        session.close();

        assertThrows(IllegalStateException.class, () -> session.sendLine("late"));
    }

    @Test
    void sendAfterCloseStdinFails() {
        try (Session session = fixtureService().interactive(call -> call.args("ignore-stdin", "--millis=5000"))) {
            session.closeStdin();

            assertThrows(IllegalStateException.class, () -> session.sendLine("late"));
        }
    }

    @Test
    void closeStdinReturnsPromptlyWhileWriterIsBlockedOnFullPipe() throws Exception {
        Session session =
                fixtureService().interactive(call -> call.args("ignore-stdin", "--millis=30000", "--started=false"));
        CompletableFuture<Throwable> writerOutcome = new CompletableFuture<>();
        CountDownLatch writerStarted = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                writerStarted.countDown();
                // Far beyond any OS pipe buffer, so the writer blocks until the pipe breaks.
                session.send("x".repeat(8 * 1024 * 1024));
                writerOutcome.complete(null);
            } catch (Throwable throwable) {
                writerOutcome.complete(throwable);
            }
        });
        writer.start();
        try {
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(300);

            assertTimeoutPreemptively(Duration.ofSeconds(2), session::closeStdin);

            session.close();
            Throwable outcome = writerOutcome.get(10, TimeUnit.SECONDS);
            assertTrue(
                    outcome instanceof CommandExecutionException,
                    () -> "expected typed writer failure, got " + outcome);
            assertTrue(session.onExit().get(5, TimeUnit.SECONDS).exitCode().isPresent());
        } finally {
            session.close();
            writer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void successfulWriteResetsIdleTimeout() throws Exception {
        Duration idleTimeout = idleResetWindow();
        CommandService service = fixtureService(SessionOptions.defaults()
                .withIdleTimeout(idleTimeout)
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        try (Session session = service.interactive(call -> call.args("sleep", "--millis=60000", "--finished=false"))) {
            // Keep writing for well over the idle window with a small activity interval; each
            // successful write must reset the window, so the session outlives the idle timeout.
            long activityDeadline =
                    System.nanoTime() + idleTimeout.multipliedBy(5).dividedBy(2).toNanos();
            while (System.nanoTime() < activityDeadline) {
                session.send("x");
                Thread.sleep(idleTimeout.toMillis() / 10);
            }

            assertFalse(session.onExit().isDone(), "activity must keep resetting the idle window");

            SessionExit exit = session.onExit().get(idleTimeout.multipliedBy(4).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(exit.timedOut());
        }
    }

    @Test
    void successfulReadResetsIdleTimeout() throws Exception {
        Duration idleTimeout = idleResetWindow();
        CommandService service = fixtureService(SessionOptions.defaults()
                .withIdleTimeout(idleTimeout)
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        // The heartbeat phase emits ticks for well over the idle window; each successful read must
        // reset the window. The silent hold tail then lets the idle timeout fire.
        int ticks = 25;
        long tickIntervalMillis = idleTimeout.toMillis() / 10;
        try (Session session = service.interactive(call -> call.args(
                        "long-run",
                        "--ticks=" + ticks,
                        "--interval-millis=" + tickIntervalMillis,
                        "--hold-millis=60000"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            for (int tick = 0; tick < ticks; tick++) {
                assertEquals("tick:" + tick, stdout.readLine());
            }

            assertFalse(session.onExit().isDone(), "reads must keep resetting the idle window");

            SessionExit exit = session.onExit().get(idleTimeout.multipliedBy(4).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(exit.timedOut());
        }
    }

    @Test
    void idleTimeoutClosesHungSession() throws Exception {
        CommandService service = fixtureService(SessionOptions.defaults()
                .withIdleTimeout(Duration.ofMillis(100))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        try (Session session = service.interactive(call -> call.args("sleep", "--millis=5000", "--finished=false"))) {
            SessionExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.timedOut());
        }
    }

    private static CommandService fixtureService() {
        return fixtureService(SessionOptions.defaults());
    }

    private static Duration idleResetWindow() {
        // The idle window is an order of magnitude larger than the activity interval derived from
        // it, so scheduler hiccups on loaded CI machines cannot eat the whole window between
        // activity samples.
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static CommandService fixtureService(SessionOptions sessionOptions) {
        return new CommandService(TestCliSupport.command(), RunOptions.defaults(), sessionOptions);
    }
}

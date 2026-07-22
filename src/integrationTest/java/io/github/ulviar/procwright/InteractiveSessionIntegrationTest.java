/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InteractiveSessionIntegrationTest {

    @Test
    void sendLineFlushesInputAndStdoutCanBeRead() throws Exception {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--response-prefix=echo:")
                        .open();
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--response-prefix=echo:")
                        .open();
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("stdin-echo", "--mode=bytes-count")
                        .open();
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("stdin-echo", "--mode=bytes-count")
                        .open();
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("exit", "--stderr=error-line\n")
                        .open();
                BufferedReader stderr =
                        new BufferedReader(new InputStreamReader(session.stderr(), StandardCharsets.UTF_8))) {
            assertEquals("error-line", stderr.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void onExitFutureViewDoesNotOwnLifecycle() throws Exception {
        Session session = fixtureService()
                .interactive()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .open();

        try {
            session.onExit().complete(new SessionExit(OptionalInt.empty(), true));

            assertFalse(session.onExit().isDone());

            session.close();
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void closeIsIdempotentAndStopsProcess() throws Exception {
        Session session = fixtureService()
                .interactive()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .open();

        session.close();
        session.close();

        assertTrue(session.onExit().get(2, TimeUnit.SECONDS).exitCode().isPresent());
    }

    @Test
    void closeAfterNaturalRootExitStopsObservedDescendant(@TempDir Path temporaryDirectory) throws Exception {
        Path childPidFile = temporaryDirectory.resolve("child.pid");
        Session session = fixtureService()
                .interactive()
                .withArgs(
                        "spawn-child",
                        "--child-scenario=never-exit",
                        "--linger-millis=500",
                        "--pid-file=" + childPidFile)
                .open();
        long childPid = -1;
        try {
            childPid = TestCliSupport.waitForPid(childPidFile);
            session.onExit().get(3, TimeUnit.SECONDS);
            assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));

            session.close();

            assertTrue(waitForProcessExit(childPid), "session close left an observed descendant alive");
        } finally {
            session.close();
            if (childPid > 0) {
                ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void sendAfterCloseFails() {
        Session session = fixtureService()
                .interactive()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .open();

        session.close();

        assertThrows(IllegalStateException.class, () -> session.sendLine("late"));
    }

    @Test
    void sendAfterCloseStdinFails() {
        try (Session session = fixtureService()
                .interactive()
                .withArgs("ignore-stdin", "--millis=5000")
                .open()) {
            session.closeStdin();

            assertThrows(IllegalStateException.class, () -> session.sendLine("late"));
        }
    }

    @Test
    void successfulWriteResetsIdleTimeout() throws Exception {
        Duration idleTimeout = idleResetWindow();
        InteractiveScenario.Draft scenario =
                fixtureService().interactive().withIdleTimeout(idleTimeout).withShutdown(idleShutdownPolicy());

        try (Session session =
                scenario.withArgs("sleep", "--millis=60000", "--finished=false").open()) {
            // Keep writing for well over the idle window with a small activity interval; each
            // successful write must reset the window, so the session outlives the idle timeout.
            long activityDeadline =
                    System.nanoTime() + idleTimeout.multipliedBy(5).dividedBy(2).toNanos();
            while (System.nanoTime() < activityDeadline) {
                session.send("x");
                Thread.sleep(idleTimeout.toMillis() / 10);
            }

            assertFalse(session.onExit().isDone(), "activity must keep resetting the idle window");

            SessionExit exit = session.onExit()
                    .get(idleTimeout.multipliedBy(4).plusSeconds(6).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(exit.timedOut());
        }
    }

    @Test
    void successfulReadResetsIdleTimeout() throws Exception {
        Duration idleTimeout = idleResetWindow();
        InteractiveScenario.Draft scenario =
                fixtureService().interactive().withIdleTimeout(idleTimeout).withShutdown(idleShutdownPolicy());

        // The heartbeat phase emits ticks for well over the idle window; each successful read must
        // reset the window. The silent hold tail then lets the idle timeout fire.
        int ticks = 25;
        long tickIntervalMillis = idleTimeout.toMillis() / 10;
        try (Session session = scenario.withArgs(
                                "long-run",
                                "--ticks=" + ticks,
                                "--interval-millis=" + tickIntervalMillis,
                                "--hold-millis=60000")
                        .open();
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            for (int tick = 0; tick < ticks; tick++) {
                assertEquals("tick:" + tick, stdout.readLine());
            }

            assertFalse(session.onExit().isDone(), "reads must keep resetting the idle window");

            SessionExit exit = session.onExit()
                    .get(idleTimeout.multipliedBy(4).plusSeconds(6).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(exit.timedOut());
        }
    }

    @Test
    void successfulSkipResetsIdleTimeoutUntilOutputBecomesSilent() throws Exception {
        Duration idleTimeout = idleResetWindow();
        InteractiveScenario.Draft scenario =
                fixtureService().interactive().withIdleTimeout(idleTimeout).withShutdown(idleShutdownPolicy());

        try (Session session = scenario.withArgs(
                        "long-run",
                        "--ticks=25",
                        "--interval-millis=" + idleTimeout.toMillis() / 10,
                        "--hold-millis=60000")
                .open()) {
            long activityDeadline =
                    System.nanoTime() + idleTimeout.multipliedBy(5).dividedBy(2).toNanos();
            int successfulSkips = 0;
            while (System.nanoTime() < activityDeadline) {
                if (session.stdout().skip(1) > 0) {
                    successfulSkips++;
                }
            }

            assertTrue(successfulSkips > 0);
            assertFalse(session.onExit().isDone(), "successful skips must keep resetting the idle window");

            SessionExit exit = session.onExit()
                    .get(idleTimeout.multipliedBy(4).plusSeconds(6).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(exit.timedOut());
        }
    }

    @Test
    void idleTimeoutClosesHungSession() throws Exception {
        InteractiveScenario.Draft scenario = fixtureService()
                .interactive()
                .withIdleTimeout(Duration.ofMillis(100))
                .withShutdown(idleShutdownPolicy());

        try (Session session =
                scenario.withArgs("sleep", "--millis=5000", "--finished=false").open()) {
            SessionExit exit = session.onExit().get(10, TimeUnit.SECONDS);

            assertTrue(exit.timedOut());
        }
    }

    private static CommandService fixtureService() {
        return Procwright.command(TestCliSupport.command());
    }

    private static Duration idleResetWindow() {
        // The idle window is an order of magnitude larger than the activity interval derived from
        // it, so scheduler hiccups on loaded CI machines cannot eat the whole window between
        // activity samples.
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static ShutdownPolicy idleShutdownPolicy() {
        // The grace windows are upper bounds on reap latency, not added test time. A tight kill
        // grace races process-tree reaping on loaded CI machines and fails shutdown spuriously.
        return ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofSeconds(5));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean waitForProcessExit(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(10);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }
}

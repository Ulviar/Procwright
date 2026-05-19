package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ulviar.icli.command.CommandSpec;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.command.ShutdownPolicy;
import com.github.ulviar.icli.session.Session;
import com.github.ulviar.icli.session.SessionExit;
import com.github.ulviar.icli.session.SessionOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class InteractiveSessionIntegrationTest {

    @Test
    void sendLineFlushesInputAndStdoutCanBeRead() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("line-echo"));
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
        try (Session session = fixtureService().interactive(call -> call.args("line-echo"));
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
        try (Session session = fixtureService().interactive(call -> call.args("wait-eof"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            session.send("payload");
            session.closeStdin();

            assertEquals("eof:7", stdout.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void closeStdinIsIdempotent() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("wait-eof"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            session.closeStdin();
            session.closeStdin();

            assertEquals("eof:0", stdout.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void stderrIsAvailableAsRawStream() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("stderr-line"));
                BufferedReader stderr =
                        new BufferedReader(new InputStreamReader(session.stderr(), StandardCharsets.UTF_8))) {
            assertEquals("error-line", stderr.readLine());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        }
    }

    @Test
    void onExitFutureViewDoesNotOwnLifecycle() {
        Session session = fixtureService().interactive(call -> call.args("sleep", "5000"));

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
        Session session = fixtureService().interactive(call -> call.args("sleep", "5000"));

        session.close();
        session.close();

        assertTrue(session.onExit().get(2, TimeUnit.SECONDS).exitCode().isPresent());
    }

    @Test
    void sendAfterCloseFails() {
        Session session = fixtureService().interactive(call -> call.args("sleep", "5000"));

        session.close();

        assertThrows(IllegalStateException.class, () -> session.sendLine("late"));
    }

    @Test
    void successfulWriteResetsIdleTimeout() throws Exception {
        CommandService service = fixtureService(SessionOptions.defaults()
                .withIdleTimeout(Duration.ofSeconds(1))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        try (Session session = service.interactive(call -> call.args("sleep", "5000"))) {
            Thread.sleep(500);
            session.send("x");
            Thread.sleep(500);

            assertFalse(session.onExit().isDone());

            SessionExit exit = session.onExit().get(2, TimeUnit.SECONDS);
            assertTrue(exit.timedOut());
        }
    }

    @Test
    void successfulReadResetsIdleTimeout() throws Exception {
        CommandService service = fixtureService(SessionOptions.defaults()
                .withIdleTimeout(Duration.ofSeconds(1))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        try (Session session = service.interactive(call -> call.args("delayed-stdout-sleep", "300", "pulse", "5000"));
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
            assertEquals("pulse", stdout.readLine());
            Thread.sleep(500);

            assertFalse(session.onExit().isDone());

            SessionExit exit = session.onExit().get(2, TimeUnit.SECONDS);
            assertTrue(exit.timedOut());
        }
    }

    @Test
    void idleTimeoutClosesHungSession() throws Exception {
        CommandService service = fixtureService(SessionOptions.defaults()
                .withIdleTimeout(Duration.ofMillis(100))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        try (Session session = service.interactive(call -> call.args("sleep", "5000"))) {
            SessionExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.timedOut());
        }
    }

    private static CommandService fixtureService() {
        return fixtureService(SessionOptions.defaults());
    }

    private static CommandService fixtureService(SessionOptions sessionOptions) {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), ProcessFixtureProgram.class.getName())
                .build();
        return new CommandService(command, RunOptions.defaults(), sessionOptions);
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}

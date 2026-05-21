package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.command.ShutdownPolicy;
import com.github.ulviar.icli.session.Session;
import com.github.ulviar.icli.session.SessionExit;
import com.github.ulviar.icli.session.SessionOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalInt;
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
    void successfulWriteResetsIdleTimeout() throws Exception {
        CommandService service = fixtureService(SessionOptions.defaults()
                .withIdleTimeout(Duration.ofSeconds(1))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        try (Session session = service.interactive(call -> call.args("sleep", "--millis=5000", "--finished=false"))) {
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

        try (Session session = service.interactive(call -> call.args(
                        "exit", "--startup-delay-millis=300", "--stdout=pulse\n", "--exit-delay-millis=5000"));
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

        try (Session session = service.interactive(call -> call.args("sleep", "--millis=5000", "--finished=false"))) {
            SessionExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.timedOut());
        }
    }

    private static CommandService fixtureService() {
        return fixtureService(SessionOptions.defaults());
    }

    private static CommandService fixtureService(SessionOptions sessionOptions) {
        return new CommandService(TestCliSupport.command(), RunOptions.defaults(), sessionOptions);
    }
}

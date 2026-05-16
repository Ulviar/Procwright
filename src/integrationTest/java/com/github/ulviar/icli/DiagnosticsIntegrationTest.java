package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ulviar.icli.testing.diagnostics.DiagnosticRecorder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DiagnosticsIntegrationTest {

    @Test
    void runEmitsLifecycleAndExitEventsWithRedactionFriendlyCommandEcho() {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service = fixtureService()
                .withDiagnostics(
                        DiagnosticsOptions.defaults().withListener(recorder).withTranscriptSink(recorder));

        CommandResult result = service.run(call -> call.args("cwd-env", "SECRET_VALUE", "--token", "secret-argument")
                .putEnvironment("SECRET_VALUE", "hidden-value"));

        assertTrue(result.succeeded());
        assertTrue(recorder.awaitContains(DiagnosticEventType.COMMAND_PREPARED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));

        DiagnosticEvent prepared = recorder.first(DiagnosticEventType.COMMAND_PREPARED);
        assertTrue(prepared.command().environmentNames().contains("SECRET_VALUE"));
        assertEquals(7, prepared.command().argumentCount());
        assertFalse(prepared.toString().contains("hidden-value"));
        assertFalse(prepared.toString().contains("secret-argument"));
        assertEquals("run", prepared.scenario());
    }

    @Test
    void runEmitsOutputTruncationMetadata() {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        CommandResult result =
                service.run(call -> call.args("large-stdout", "64", "x").capture(CapturePolicy.bounded(16)));

        assertTrue(result.stdoutTruncated());
        DiagnosticEvent event = recorder.first(DiagnosticEventType.OUTPUT_TRUNCATED);
        assertEquals("stdout", event.attributes().get("source"));
        assertEquals("16", event.attributes().get("limitBytes"));
    }

    @Test
    void runEmitsTimeoutAndShutdownEvents() {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        CommandResult result = service.run(call -> call.args("sleep", "5000")
                .timeout(Duration.ofMillis(100))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200))));

        assertTrue(result.timedOut());
        assertTrue(recorder.awaitContains(DiagnosticEventType.TIMEOUT_REACHED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.SHUTDOWN_REQUESTED));
        assertEquals(
                "true",
                recorder.first(DiagnosticEventType.PROCESS_EXITED).attributes().get("timedOut"));
    }

    @Test
    void diagnosticListenerFailureDoesNotChangeRunResult() {
        CommandService service = fixtureService()
                .withDiagnostics(DiagnosticsOptions.defaults().withListener(event -> {
                    throw new AssertionError("diagnostics failed");
                }));

        CommandResult result = service.run(call -> call.args("stdout", "ok\n"));

        assertTrue(result.succeeded());
        assertEquals("ok\n", result.stdout());
    }

    @Test
    void listenEmitsDiagnosticTruncationAndExplicitCloseShutdownEvents() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service = fixtureService(StreamOptions.defaults().withDiagnosticLimit(64))
                .withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        try (StreamSession session =
                service.listen(call -> call.args("large-stdout", "128", "x").onOutput(StreamListener.noop()))) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.diagnostics().truncated());
            DiagnosticEvent event = recorder.first(DiagnosticEventType.OUTPUT_TRUNCATED);
            assertEquals("diagnostics", event.attributes().get("source"));
            assertEquals("64", event.attributes().get("limitChars"));
        }

        DiagnosticRecorder closeRecorder = new DiagnosticRecorder();
        CommandService closeService =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(closeRecorder));
        try (StreamSession session = closeService.listen(call -> call.args("sleep", "5000"))) {
            session.close();
            session.onExit().get(2, TimeUnit.SECONDS);
        }

        assertTrue(closeRecorder.awaitContains(DiagnosticEventType.SHUTDOWN_REQUESTED));
        assertEquals(
                "close",
                closeRecorder
                        .first(DiagnosticEventType.SHUTDOWN_REQUESTED)
                        .attributes()
                        .get("reason"));
    }

    @Test
    void listenEmitsLifecycleTimeoutAndListenerFailureEvents() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        try (StreamSession session =
                service.listen(call -> call.args("sleep", "5000").timeout(Duration.ofMillis(100)))) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.timedOut());
            assertTrue(recorder.awaitContains(DiagnosticEventType.COMMAND_PREPARED));
            assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
            assertTrue(recorder.awaitContains(DiagnosticEventType.TIMEOUT_REACHED));
            assertTrue(recorder.awaitContains(DiagnosticEventType.SHUTDOWN_REQUESTED));
            assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));
        }

        DiagnosticRecorder listenerFailureRecorder = new DiagnosticRecorder();
        CommandService listenerFailureService =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(listenerFailureRecorder));
        try (StreamSession session = listenerFailureService.listen(
                call -> call.args("stdout", "boom").onOutput(chunk -> {
                    throw new IllegalStateException("listener failed");
                }))) {
            try {
                session.onExit().get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                // Expected from the listener failure path.
            }
        }

        assertTrue(listenerFailureRecorder.awaitContains(DiagnosticEventType.LISTENER_FAILED));
    }

    @Test
    void interactiveAndLineSessionEmitLifecycleEvents() throws Exception {
        DiagnosticRecorder interactiveRecorder = new DiagnosticRecorder();
        CommandService interactiveService =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(interactiveRecorder));

        try (Session session = interactiveService.interactive(call -> call.args("exit-now"))) {
            session.onExit().get(2, TimeUnit.SECONDS);
        }

        assertEquals(
                "interactive",
                interactiveRecorder.first(DiagnosticEventType.COMMAND_PREPARED).scenario());
        assertTrue(interactiveRecorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(interactiveRecorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));

        DiagnosticRecorder lineRecorder = new DiagnosticRecorder();
        CommandService lineService =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(lineRecorder));

        try (LineSession session = lineService.lineSession(call -> call.args("line-repl"))) {
            assertEquals("response:hello", session.request("hello").text());
        }

        assertEquals(
                "lineSession",
                lineRecorder.first(DiagnosticEventType.COMMAND_PREPARED).scenario());
        assertTrue(lineRecorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(lineRecorder.awaitContains(DiagnosticEventType.SHUTDOWN_REQUESTED));
        assertTrue(lineRecorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));
    }

    @Test
    void pooledWorkersEmitLifecycleEvents() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        try (PooledLineSession pool =
                service.pooled(call -> call.args("line-repl").maxSize(1).warmupSize(1))) {
            assertEquals("response:hello", pool.request("hello").text());
        }

        assertEquals(
                "pooled", recorder.first(DiagnosticEventType.COMMAND_PREPARED).scenario());
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.SHUTDOWN_REQUESTED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));
    }

    private static CommandService fixtureService() {
        return fixtureService(StreamOptions.defaults());
    }

    private static CommandService fixtureService(StreamOptions streamOptions) {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), ProcessFixtureProgram.class.getName())
                .build();
        return new CommandService(
                command,
                RunOptions.defaults(),
                SessionOptions.defaults(),
                LineSessionOptions.defaults(),
                streamOptions);
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}

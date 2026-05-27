package io.github.ulviar.procwright.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.TestCliSupport;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.DiagnosticAttributeSchema;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.session.StreamOptions;
import io.github.ulviar.procwright.session.StreamSession;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DiagnosticsIntegrationTest {

    @Test
    void runEmitsLifecycleAndExitEventsWithRedactionFriendlyCommandEcho() {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service = fixtureService()
                .withDiagnostics(
                        DiagnosticsOptions.defaults().withListener(recorder).withTranscriptSink(recorder));

        CommandResult result = service.run()
                .configuredBy(
                        call -> call.args("argv-env-cwd", "--env=SECRET_VALUE", "--", "--token", "secret-argument")
                                .putEnvironment("SECRET_VALUE", "hidden-value"))
                .execute();

        assertTrue(result.succeeded());
        assertTrue(recorder.awaitContains(DiagnosticEventType.COMMAND_PREPARED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));

        DiagnosticEvent prepared = recorder.first(DiagnosticEventType.COMMAND_PREPARED);
        assertTrue(prepared.command().environmentNames().contains("SECRET_VALUE"));
        assertEquals(8, prepared.command().argumentCount());
        assertFalse(prepared.toString().contains("hidden-value"));
        assertFalse(prepared.toString().contains("secret-argument"));
        assertEquals("run", prepared.scenario());
    }

    @Test
    void runDiagnosticEventsUseSchemaAndDoNotExposeRawValues() {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        CommandResult result = service.run()
                .configuredBy(call -> call.args("exit", "--stdout=" + "secret-output".repeat(4))
                        .putEnvironment("SECRET_VALUE", "secret-env-value")
                        .capture(CapturePolicy.bounded(8)))
                .execute();

        assertTrue(result.stdoutTruncated());
        assertEventsSafe(
                recorder,
                Set.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        DiagnosticEventType.PROCESS_EXITED),
                "secret-output",
                "secret-env-value");
    }

    @Test
    void timeoutAndLaunchFailureDiagnosticEventsUseSchemaAndDoNotExposeRawValues() {
        DiagnosticRecorder timeoutRecorder = new DiagnosticRecorder();
        CommandService timeoutService =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(timeoutRecorder));

        CommandResult timeout = timeoutService
                .run()
                .configuredBy(
                        call -> call.args("sleep", "--millis=5000", "--finished=false", "--", "secret-timeout-arg")
                                .timeout(Duration.ofMillis(100)))
                .execute();

        assertTrue(timeout.timedOut());
        assertEventsSafe(
                timeoutRecorder,
                Set.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.TIMEOUT_REACHED,
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        DiagnosticEventType.PROCESS_EXITED),
                "secret-timeout-arg");

        DiagnosticRecorder failureRecorder = new DiagnosticRecorder();
        CommandService missingCommand = CommandService.forCommand("__procwright_missing_command_for_diagnostics__")
                .withDiagnostics(DiagnosticsOptions.defaults().withListener(failureRecorder));

        assertThrows(
                CommandExecutionException.class,
                () -> missingCommand.run().withArg("secret-launch-arg").execute());
        assertEventsSafe(
                failureRecorder,
                Set.of(DiagnosticEventType.COMMAND_PREPARED, DiagnosticEventType.PROCESS_FAILED),
                "secret-launch-arg");
    }

    @Test
    void streamFailureDiagnosticEventsUseSchemaAndDoNotExposeRawOutput() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        try (StreamSession session = service.listen()
                .configuredBy(call -> call.args("exit", "--stdout=secret-stream-output")
                        .onOutput(chunk -> {
                            throw new IllegalStateException("listener failed");
                        }))
                .open()) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> session.onExit()
                    .get(2, TimeUnit.SECONDS));
        }

        assertEventsSafe(
                recorder,
                Set.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.LISTENER_FAILED,
                        DiagnosticEventType.PROCESS_FAILED,
                        DiagnosticEventType.SHUTDOWN_REQUESTED),
                "secret-stream-output");
    }

    @Test
    void diagnosticAttributeContractCoversEveryEventType() {
        assertEquals(
                Set.of(DiagnosticEventType.values()),
                DiagnosticAttributeSchema.allowedAttributesByType().keySet());
    }

    @Test
    void runEmitsOutputTruncationMetadata() {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        CommandResult result = service.run()
                .withArgs("burst", "--stdout-bytes=64", "--stdout-byte=x")
                .withCapture(CapturePolicy.bounded(16))
                .execute();

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

        CommandResult result = service.run()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withTimeout(Duration.ofMillis(100))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(10), Duration.ofMillis(200)))
                .execute();

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

        CommandResult result = service.run().withArgs("exit", "--stdout=ok\n").execute();

        assertTrue(result.succeeded());
        assertEquals("ok\n", result.stdout());
    }

    @Test
    void listenEmitsDiagnosticTruncationAndExplicitCloseShutdownEvents() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service = fixtureService(StreamOptions.defaults().withDiagnosticLimit(64))
                .withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        try (StreamSession session = service.listen()
                .withArgs("burst", "--stdout-bytes=128", "--stdout-byte=x")
                .onOutput(StreamListener.noop())
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.diagnostics().truncated());
            DiagnosticEvent event = recorder.first(DiagnosticEventType.OUTPUT_TRUNCATED);
            assertEquals("diagnostics", event.attributes().get("source"));
            assertEquals("64", event.attributes().get("limitChars"));
        }

        DiagnosticRecorder closeRecorder = new DiagnosticRecorder();
        CommandService closeService =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(closeRecorder));
        try (StreamSession session = closeService
                .listen()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .open()) {
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

        try (StreamSession session = service.listen()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withTimeout(Duration.ofMillis(100))
                .open()) {
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
        try (StreamSession session = listenerFailureService
                .listen()
                .configuredBy(call -> call.args("exit", "--stdout=boom").onOutput(chunk -> {
                    throw new IllegalStateException("listener failed");
                }))
                .open()) {
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

        try (Session session = interactiveService.interactive().withArg("exit").open()) {
            session.onExit().get(2, TimeUnit.SECONDS);
        }

        assertEquals(
                "interactive",
                interactiveRecorder.first(DiagnosticEventType.COMMAND_PREPARED).scenario());
        assertTrue(interactiveRecorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(interactiveRecorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));
        assertEventsSafe(
                interactiveRecorder,
                Set.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.PROCESS_EXITED));

        DiagnosticRecorder lineRecorder = new DiagnosticRecorder();
        CommandService lineService =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(lineRecorder));

        try (LineSession session =
                lineService.lineSession().withArg("controlled-line-repl").open()) {
            assertEquals("response:hello", session.request("hello").text());
        }

        assertEquals(
                "lineSession",
                lineRecorder.first(DiagnosticEventType.COMMAND_PREPARED).scenario());
        assertTrue(lineRecorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(lineRecorder.awaitContains(DiagnosticEventType.SHUTDOWN_REQUESTED));
        assertTrue(lineRecorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));
        assertEventsSafe(
                lineRecorder,
                Set.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        DiagnosticEventType.PROCESS_EXITED));
    }

    @Test
    void pooledWorkersEmitLifecycleEvents() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder();
        CommandService service =
                fixtureService().withDiagnostics(DiagnosticsOptions.defaults().withListener(recorder));

        try (PooledLineSession pool = service.lineSession()
                .withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            assertEquals("response:hello", pool.request("hello").text());
        }

        assertEquals(
                "pooled", recorder.first(DiagnosticEventType.COMMAND_PREPARED).scenario());
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_STARTED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.SHUTDOWN_REQUESTED));
        assertTrue(recorder.awaitContains(DiagnosticEventType.PROCESS_EXITED));
        assertEventsSafe(
                recorder,
                Set.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        DiagnosticEventType.PROCESS_EXITED));
    }

    private static void assertEventsSafe(
            DiagnosticRecorder recorder, Set<DiagnosticEventType> expectedTypes, String... forbiddenFragments) {
        for (DiagnosticEventType type : expectedTypes) {
            assertTrue(recorder.awaitContains(type), () -> "missing diagnostic event: " + type);
        }

        List<DiagnosticEvent> events = recorder.events();
        assertFalse(events.isEmpty());
        Set<DiagnosticEventType> observedTypes =
                events.stream().map(DiagnosticEvent::type).collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(expectedTypes, observedTypes);
        for (DiagnosticEvent event : events) {
            DiagnosticAttributeSchema.validate(event.type(), event.attributes());
            String rendered = event.toString();
            for (String forbidden : forbiddenFragments) {
                assertFalse(rendered.contains(forbidden), () -> "diagnostic event leaked " + forbidden + ": " + event);
            }
        }
    }

    private static CommandService fixtureService() {
        return fixtureService(StreamOptions.defaults());
    }

    private static CommandService fixtureService(StreamOptions streamOptions) {
        return Procwright.command(TestCliSupport.command()).withStreamOptions(streamOptions);
    }
}

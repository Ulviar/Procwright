/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.SessionOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class JsonLineSessionTest {

    @Test
    void sendsAndReceivesOneJsonLine() {
        try (LineSession lineSession =
                        fixtureService().lineSession().withArg("echo").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            JsonValue request = JsonValue.object(Map.of("command", JsonValue.string("status")));

            JsonValue response = json.request(request, Duration.ofSeconds(2));

            assertEquals(request, response);
        }
    }

    @Test
    void mapsJsonLineToolSuccess() {
        try (LineSession lineSession =
                        fixtureService().lineSession().withArg("echo").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            CommandBackedTool<String, JsonValue> tool =
                    CommandBackedTool.jsonLine(json, JsonValue::string, value -> value);

            ToolCallResult<JsonValue> result = tool.call("payload");

            assertTrue(result.succeeded());
            assertEquals(JsonValue.string("payload"), result.value().orElseThrow());
        }
    }

    @Test
    void malformedJsonResponseFailsAsProtocolError() {
        try (LineSession lineSession =
                        fixtureService().lineSession().withArg("malformed").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            JsonParseException exception =
                    assertThrows(JsonParseException.class, () -> json.request(JsonValue.string("payload")));

            assertTrue(exception.getMessage().contains("offset"));
        }
    }

    @Test
    void cancellingAsyncJsonRequestClosesUnderlyingSession() throws Exception {
        try (LineSession lineSession =
                        fixtureService().lineSession().withArg("slow").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            CancellableCall<JsonValue> call = json.requestAsync(JsonValue.string("payload"), Duration.ofSeconds(10));

            assertTrue(call.cancel());

            CompletionException exception = assertThrows(
                    CompletionException.class, () -> call.completion().join());
            assertInstanceOf(CancellationException.class, exception.getCause());
            json.onExit().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentAsyncRequestsAreServedBySharedWorker() throws Exception {
        try (LineSession lineSession =
                        fixtureService().lineSession().withArg("echo-loop").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            int callCount = 6;
            ConcurrentHashMap<Integer, CancellableCall<JsonValue>> calls = new ConcurrentHashMap<>();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> callers = new ArrayList<>();
            for (int i = 0; i < callCount; i++) {
                int id = i;
                Thread caller = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    calls.put(id, json.requestAsync(payload(id), Duration.ofSeconds(5)));
                });
                caller.start();
                callers.add(caller);
            }
            start.countDown();
            for (Thread caller : callers) {
                caller.join(TimeUnit.SECONDS.toMillis(5));
            }

            assertEquals(callCount, calls.size());
            for (int i = 0; i < callCount; i++) {
                assertEquals(payload(i), calls.get(i).completion().get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void asyncRequestsReuseOneWorkerThreadAndStopAfterClose() throws Exception {
        awaitNoJsonLineWorkerThreads();
        try (LineSession lineSession =
                        fixtureService().lineSession().withArg("echo-loop").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            for (int i = 0; i < 12; i++) {
                json.requestAsync(JsonValue.number(i), Duration.ofSeconds(2))
                        .completion()
                        .get(5, TimeUnit.SECONDS);
            }

            assertEquals(1, jsonLineWorkerThreadCount());
        }
        awaitNoJsonLineWorkerThreads();
    }

    @Test
    void closeCompletesPendingAsyncCallsExceptionally() {
        try (LineSession lineSession =
                        fixtureService().lineSession().withArg("slow").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            CancellableCall<JsonValue> inFlight = json.requestAsync(JsonValue.string("first"), Duration.ofSeconds(10));
            CancellableCall<JsonValue> queued = json.requestAsync(JsonValue.string("second"), Duration.ofSeconds(10));

            json.close();

            CompletionException inFlightFailure = assertThrows(
                    CompletionException.class, () -> inFlight.completion().join());
            CompletionException queuedFailure = assertThrows(
                    CompletionException.class, () -> queued.completion().join());
            assertInstanceOf(CancellationException.class, inFlightFailure.getCause());
            assertInstanceOf(CancellationException.class, queuedFailure.getCause());
        }
    }

    @Test
    void asyncRequestAfterCloseFailsFastWithoutStartingWorker() throws Exception {
        awaitNoJsonLineWorkerThreads();
        try (LineSession lineSession =
                fixtureService().lineSession().withArg("echo").open()) {
            JsonLineSession json = JsonLineSession.over(lineSession);
            json.close();

            CancellableCall<JsonValue> call = json.requestAsync(JsonValue.string("late"), Duration.ofSeconds(1));

            CompletionException failure = assertThrows(
                    CompletionException.class, () -> call.completion().join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, jsonLineWorkerThreadCount());
        }
    }

    private static JsonValue payload(int id) {
        return JsonValue.object(Map.of("id", JsonValue.number(id)));
    }

    private static long jsonLineWorkerThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("procwright-json-line"))
                .count();
    }

    private static void awaitNoJsonLineWorkerThreads() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (jsonLineWorkerThreadCount() > 0) {
            if (System.nanoTime() > deadline) {
                fail("JSON line session worker threads did not stop");
            }
            Thread.sleep(10);
        }
    }

    private static CommandService fixtureService() {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), JsonLineFixtureProgram.class.getName())
                .build();
        return Procwright.command(command)
                .withSessionOptions(SessionOptions.defaults().withIdleTimeout(Duration.ofSeconds(30)))
                .withLineSessionOptions(LineSessionOptions.defaults().withRequestTimeout(Duration.ofSeconds(2)));
    }

    private static String javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }
}

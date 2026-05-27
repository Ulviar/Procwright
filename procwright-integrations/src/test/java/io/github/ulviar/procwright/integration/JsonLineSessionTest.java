package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.SessionOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
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

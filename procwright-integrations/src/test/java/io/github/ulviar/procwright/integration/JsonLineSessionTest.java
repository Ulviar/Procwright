/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.LineSession;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonLineSessionTest {

    @Test
    void sendsAndReceivesOneJsonLine() {
        try (LineSession lineSession = fixtureDraft().withArg("echo").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            JsonValue request = JsonValue.object(Map.of("command", JsonValue.string("status")));

            JsonValue response = json.request(request, Duration.ofSeconds(2));

            assertEquals(request, response);
        }
    }

    @Test
    void mapsJsonLineToolSuccess() {
        try (LineSession lineSession = fixtureDraft().withArg("echo").open();
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
        try (LineSession lineSession = fixtureDraft().withArg("malformed").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            JsonParseException exception =
                    assertThrows(JsonParseException.class, () -> json.request(JsonValue.string("payload")));

            assertTrue(exception.getMessage().contains("offset"));
        }
    }

    private static LineSessionScenario.Draft fixtureDraft() {
        CommandSpec command = CommandSpec.of(javaExecutable())
                .withArgs("-cp", System.getProperty("java.class.path"), JsonLineFixtureProgram.class.getName());
        return Procwright.command(command)
                .lineSession()
                .withIdleTimeout(Duration.ofSeconds(30))
                .withRequestTimeout(Duration.ofSeconds(2));
    }

    private static String javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }
}

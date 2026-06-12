/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.examples;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.integration.CommandBackedTool;
import io.github.ulviar.procwright.integration.ContentLengthJsonFrames;
import io.github.ulviar.procwright.integration.JsonLineSession;
import io.github.ulviar.procwright.integration.JsonValue;
import io.github.ulviar.procwright.integration.ToolCallResult;
import io.github.ulviar.procwright.session.LineSession;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

final class CommandBackedToolExamples {

    void oneShotCommandBackedTool() {
        CommandService git = Procwright.command("git");

        CommandBackedTool<String, String> status = CommandBackedTool.of(path -> {
            CommandResult result = git.run().withArgs("status", "--short", path).execute();
            if (!result.succeeded()) {
                throw result.toException();
            }
            return result.stdout();
        });

        ToolCallResult<String> result = status.call(".");
        result.error().ifPresent(error -> System.err.println(error.code()));
    }

    void jsonLineCommandBackedTool() {
        CommandService service = Procwright.command("tool");

        try (LineSession lineSession =
                        service.lineSession().withArg("json-worker").open();
                JsonLineSession json = JsonLineSession.over(lineSession)) {
            CommandBackedTool<String, JsonValue> tool = CommandBackedTool.jsonLine(
                    json, input -> JsonValue.object(Map.of("input", JsonValue.string(input))), Function.identity());

            ToolCallResult<JsonValue> result = tool.call("payload");
            result.value().ifPresent(System.out::println);
        }
    }

    void cancellableJsonLineCall(JsonLineSession json) {
        var call = json.requestAsync(JsonValue.string("long-running"), Duration.ofSeconds(30));

        call.cancel();
    }

    void contentLengthFramedJson(InputStream input, OutputStream output) {
        JsonValue request = JsonValue.object(Map.of("method", JsonValue.string("ping")));

        ContentLengthJsonFrames.write(output, request);
        JsonValue response = ContentLengthJsonFrames.read(input, 1024 * 1024);

        if (!request.equals(response)) {
            throw new IllegalStateException("unexpected response");
        }
    }
}

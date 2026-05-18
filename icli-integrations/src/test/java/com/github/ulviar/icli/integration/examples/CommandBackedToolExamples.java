package com.github.ulviar.icli.integration.examples;

import com.github.ulviar.icli.CommandService;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.integration.CommandBackedTool;
import com.github.ulviar.icli.integration.ContentLengthJsonFrames;
import com.github.ulviar.icli.integration.JsonLineSession;
import com.github.ulviar.icli.integration.JsonValue;
import com.github.ulviar.icli.integration.ToolCallResult;
import com.github.ulviar.icli.session.LineSession;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

final class CommandBackedToolExamples {

    void oneShotCommandBackedTool(CommandService git) {
        CommandBackedTool<String, String> status = CommandBackedTool.of(path -> {
            CommandResult result = git.run(call -> call.args("status", "--short", path));
            if (!result.succeeded()) {
                throw result.toException();
            }
            return result.stdout();
        });

        ToolCallResult<String> result = status.call(".");
        result.error().ifPresent(error -> System.err.println(error.code()));
    }

    void jsonLineCommandBackedTool(CommandService service) {
        try (LineSession lineSession = service.lineSession(call -> call.args("json-worker"));
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

# Integrations

The optional `io.github.ulviar:icli-integrations` artifact wraps existing process scenarios as structured integration
boundaries.

It currently covers:

- one-shot command-backed tools;
- JSON Lines sessions over `LineSession`;
- protocol-session adapters for JSON Lines, delimiter-framed bytes, Content-Length JSON, and typed JSON mapping;
- cancellable JSON Lines calls;
- Content-Length framed JSON helpers;
- structured adapter errors;
- command-backed tool result wrappers.

`JsonLineSession.requestAsync` is a narrow cancellable helper in this optional module. It is not the generic/core async
request API that remains outside `0.1.0`.

Complete example sources:

- [`CommandBackedToolExamples.oneShotCommandBackedTool`](https://github.com/Ulviar/iCLI/blob/main/icli-integrations/src/test/java/io/github/ulviar/icli/integration/examples/CommandBackedToolExamples.java)
- [`CommandBackedToolExamples.jsonLineCommandBackedTool`](https://github.com/Ulviar/iCLI/blob/main/icli-integrations/src/test/java/io/github/ulviar/icli/integration/examples/CommandBackedToolExamples.java)
- [`CommandBackedToolExamples.cancellableJsonLineCall`](https://github.com/Ulviar/iCLI/blob/main/icli-integrations/src/test/java/io/github/ulviar/icli/integration/examples/CommandBackedToolExamples.java)
- [`CommandBackedToolExamples.contentLengthFramedJson`](https://github.com/Ulviar/iCLI/blob/main/icli-integrations/src/test/java/io/github/ulviar/icli/integration/examples/CommandBackedToolExamples.java)

## Example

```java
CommandService service = Icli.command("tool");

try (LineSession lineSession = service.lineSession(call -> call.args("json-worker"));
        JsonLineSession json = JsonLineSession.over(lineSession)) {
    CommandBackedTool<String, JsonValue> tool = CommandBackedTool.jsonLine(
            json, input -> JsonValue.object(Map.of("input", JsonValue.string(input))), Function.identity());

    ToolCallResult<JsonValue> result = tool.call("payload");
    result.value().ifPresent(System.out::println);
}
```

CLI output is treated as untrusted data. The integration layer does not turn process output into instructions.

## Boundary

The module is optional. It is a small structured boundary over existing `run`, `lineSession`, and `protocolSession`
scenarios.

Adapter errors are structured and should not expose raw argv, environment values, or unbounded output by default.

# Wrap a CLI Tool

Use `io.github.ulviar:icli-integrations` when application code should call a small typed adapter instead of spreading
argv construction, JSON framing, and CLI error mapping across many call sites.

Add the optional module first. See [optional modules](../release/installation.md#optional-modules).

## Steps

1. Keep process execution in an existing scenario, usually `run` or `lineSession`.
2. Map application input into command arguments or JSON messages.
3. Return a `ToolCallResult`.
4. Treat CLI output as untrusted observation data.

```java
CommandService git = Icli.command("git");

CommandBackedTool<String, String> status = CommandBackedTool.of(path -> {
    CommandResult result = git.run(call -> call.args("status", "--short", path));
    if (!result.succeeded()) {
        throw result.toException();
    }
    return result.stdout();
});

ToolCallResult<String> result = status.call(".");
result.error().ifPresent(error -> System.err.println(error.code()));
```

More examples: [Examples](../examples.md#integration-examples).

## Use this scenario because

The integration module keeps structured adapter concerns outside the core process runtime. It does not convert CLI
output into instructions and does not add backend-specific process dependencies to core.

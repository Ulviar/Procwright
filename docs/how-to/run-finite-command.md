# Run a finite command

Use `run()` for a tool that completes one task and exits. [Try the included demo](../getting-started.md) first, then
substitute the executable and argument array:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#run -->
```java
CommandResult result =
        Procwright.command(executable).run().withArgs(arguments).execute();
```

Read `result.stdout()` and `result.stderr()`. A normal non-zero exit stays a result, so you can inspect the output before
choosing how to handle failure. To throw an exception that preserves that result:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#failure -->
```java
if (!result.succeeded()) {
    throw result.toException();
}
```

Defaults limit execution to 30 seconds and retain at most 1 MiB from each stream. Check the truncation flags when the
complete output matters. `withTimeout(...)`, `withCapture(...)`, and file/discard capture adapt those policies to your task;
see the [run reference](../scenarios/run.md). The original Draft stays unchanged after a `with*` call.

[Complete executable example](../examples/java/io/github/ulviar/procwright/examples/RunExample.java).

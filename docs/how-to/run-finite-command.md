# Run a command and use its result

Choose `run()` for a tool that completes one task and exits. Procwright writes any supplied input, drains both output
streams, and waits for completion. This replaces the stream handling and timeout code around a finite `ProcessBuilder`
call.

[Try the included demo](../getting-started.md), then substitute your executable and argument array:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#run -->
```java
CommandResult result =
        Procwright.command(executable).run().withArgs(arguments).execute();
```

## Check the outcome

Read `result.stdout()` and `result.stderr()`. A non-zero exit returns a result so you can inspect the output before
deciding how to handle it. To turn an unsuccessful result into an exception:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#failure -->
```java
if (!result.succeeded()) {
    throw result.toException();
}
```

The default timeout is 30 seconds. A timed-out result has `timedOut() == true` and `succeeded() == false`.
Launch, I/O, and supervision failures throw `CommandExecutionException` directly;
[results and errors](../reference/results-and-errors.md) explains the distinction.

## Send input

For a small text payload, supply stdin before execution. `input` is encoded as UTF-8, and stdin closes after it is written:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunOptionsExample.java#text-input -->
```java
CommandResult result =
        Procwright.command(command).run().withInput(input).execute();
```

Here, `command` is a `CommandSpec` for your CLI. With no input configured, stdin closes immediately after launch.
For large input, use `CommandInput.fromPath(path)` so Procwright does not load the file into memory.

## Choose where output goes

By default, the result retains up to 1 MiB from each stream. Check `stdoutTruncated()` and `stderrTruncated()` when
complete output matters; Procwright keeps draining output after the retained portion fills up.

- Change the retained size with `withCapture(CapturePolicy.bounded(bytes))`.
- Send output to files with `withCapture(CapturePolicy.toPath(stdout, stderr))`.
- Drop output with `withCapture(CapturePolicy.discard())` when only the exit status matters.

The [run reference](../scenarios/run.md#files) includes a complete file-input/output example and merged-output variant.
Use [live output](follow-logs.md) when your application must receive text before the command finishes.

[Complete basic example](../examples/java/io/github/ulviar/procwright/examples/RunExample.java) ·
[Input and output examples](../examples/java/io/github/ulviar/procwright/examples/RunOptionsExample.java) ·
[Working directory and environment](../reference/command-model.md)

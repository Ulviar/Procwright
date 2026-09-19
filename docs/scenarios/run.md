# Run

`run().execute()` starts one command and returns a `CommandResult`.
[Run a command and use its result](../how-to/run-finite-command.md) covers the basic call, stdin, and error handling.

The Draft is immutable and reusable. `withArg` and `withArgs` append arguments after any base arguments in the
`CommandSpec`. Each `execute()` starts a new process.

## Capture in memory

Procwright drains stdout and stderr concurrently. The default capture retains the first 1 MiB from each stream;
`withCapture(CapturePolicy.bounded(bytes))` changes that per-stream limit. Later bytes are drained and discarded, and
`stdoutTruncated()` or `stderrTruncated()` identifies the affected stream.

The result exposes captured bytes and decoded text. Output is decoded as UTF-8 by default;
`withCharsetPolicy(...)` changes decoding. `withOutput(OutputMode.MERGED)` directs stderr into stdout, leaving
`result.stderr()` empty.

## Files

Stream a file into stdin and send stdout and stderr to separate files:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunOptionsExample.java#files -->
```java
CommandResult result = Procwright.command(command)
        .run()
        .withInput(CommandInput.fromPath(input))
        .withCapture(CapturePolicy.toPath(stdout, stderr))
        .execute();
```

`command` is a `CommandSpec` for your CLI; `input`, `stdout`, and `stderr` are `Path` values. The input file must exist,
and output directories must already exist. Existing output files are overwritten. Keep the input separate from both
output files to avoid destroying it during redirection.

The operating system handles these redirects without retaining their contents in Procwright memory.
`result.stdout()` and `result.stderr()` are empty, and truncation flags are false. Exit status, timeout, and elapsed
time remain available.

For one combined log file, pair the single-path capture with merged output:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunOptionsExample.java#merged-file -->
```java
CommandResult result = Procwright.command(command)
        .run()
        .withOutput(OutputMode.MERGED)
        .withCapture(CapturePolicy.toPath(log))
        .execute();
```

[Complete source and imports](../examples/java/io/github/ulviar/procwright/examples/RunOptionsExample.java).

Separate stdout and stderr targets must refer to distinct files. See
[file capture checks](../reference/security.md#output-and-diagnostics) for aliases and new paths.
`CapturePolicy.discard()` instead drops both streams and also leaves captured result values empty.

## Timeouts and errors

The default 30-second timeout covers stdin writing, process waiting, and output draining. Change it with
`withTimeout(...)`; `Duration.ZERO` disables it. Shutdown then uses its own deadlines, so the timeout is not a bound
on the total duration of `execute()`.

A descendant holding an inherited output pipe open can cause a timeout even after the main process exits. Procwright
then attempts to stop observed descendants. See [cleanup limits](../explanations/process-cleanup-limits.md).

`CommandResult.succeeded()` requires exit code zero and no timeout. A non-zero exit remains a result until you convert
it with `toException()`. Launch, I/O, supervision, and decoding failures throw `CommandExecutionException`.
Only a decoding failure carries a completed result with the captured bytes. The
[launch-failure example](../examples/java/io/github/ulviar/procwright/examples/RunFailureExample.java) shows how to check
`reason()` without assuming `result()` is present.

See [defaults](../reference/defaults.md#run) for all initial settings and
[results and errors](../reference/results-and-errors.md#finite-commands) for the result contract.

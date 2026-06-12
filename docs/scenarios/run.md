# Run

Use `run` when the process has a finite lifecycle and the caller needs a typed command result.

The scenario covers:

- direct argv by default;
- explicit shell mode when requested by the caller;
- bounded stdout/stderr capture, output discarding, or redirection to files (`CapturePolicy`);
- stdin input from memory or streamed from a file (`CommandInput.fromPath`);
- working directory;
- explicit environment inheritance or clean environment policy;
- timeout and shutdown policy;
- process-tree cleanup;
- typed non-zero result conversion through `CommandResult.toException()`.

## Example

```java
CommandService git = Procwright.command("git");

CommandResult result = git.run().execute("status", "--short");

if (!result.succeeded()) {
    throw result.toException();
}
```

By default a run is stopped after 30 seconds, captures up to 1 MiB per stream, and reports truncation and timeout on
the result — see [Policies](../reference/policies.md#default-values). A `Duration.ZERO` timeout disables the deadline:
the run waits until the process exits on its own, while the shutdown policy still applies on close and failure paths.

For large outputs, `CapturePolicy.toPath(stdout, stderr)` (or `toPath(merged)` with `OutputMode.MERGED`) redirects
output to files at the operating-system level, and `CapturePolicy.discard()` drops it entirely; in both cases the
result's stdout/stderr accessors are empty and the truncation flags stay `false`, while exit code, `timedOut()`, and
`elapsed()` are reported as usual. Large stdin payloads can be streamed from a file with
`CommandInput.fromPath(file)` without loading them into memory.

More examples: [Examples](../examples.md#one-shot-command).

## User responsibilities

The caller still owns domain interpretation of the exit code. Procwright reports exit code, timeout, stdout, stderr, elapsed
time, and truncation flags; it does not decide whether a non-zero exit is acceptable for a particular command.

Choose shell mode only when shell syntax is required. Direct argv is the default API shape because it avoids command
line parsing surprises and reduces injection risk.

## Policy composition

Use policies for domain decisions instead of booleans. Capture and shutdown policies are examples of invariants that
belong to value objects, not to ad hoc process code at call sites.

## Failure model

`CommandResult` represents a process that was launched and supervised to a terminal outcome. Non-zero exit codes remain
results. `CommandResult.toException()` provides an exception view when the application wants fail-fast control flow.

`CommandExecutionException` is reserved for launch, supervision, or capture failures where Procwright could not produce a
normal command result.

## Scenario boundary

`run` does not request terminal capability. Use `interactive` or `lineSession` when a CLI requires a terminal or a
long-lived protocol.

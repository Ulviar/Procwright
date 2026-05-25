# Run

Use `run` when the process has a finite lifecycle and the caller needs a typed command result.

The scenario covers:

- direct argv by default;
- explicit shell mode when requested by the caller;
- bounded stdout/stderr capture;
- stdin input;
- working directory;
- explicit environment inheritance or clean environment policy;
- timeout and shutdown policy;
- process-tree cleanup;
- typed non-zero result conversion through `CommandResult.toException()`.

## Example

```java
CommandService git = Icli.command("git");

CommandResult result = git.run().execute("status", "--short");

if (!result.succeeded()) {
    throw result.toException();
}
```

More examples: [Examples](../examples.md#one-shot-command).

## User responsibilities

The caller still owns domain interpretation of the exit code. iCLI reports exit code, timeout, stdout, stderr, elapsed
time, and truncation flags; it does not decide whether a non-zero exit is acceptable for a particular command.

Choose shell mode only when shell syntax is required. Direct argv is the default API shape because it avoids command
line parsing surprises and reduces injection risk.

## Policy composition

Use policies for domain decisions instead of booleans. Capture and shutdown policies are examples of invariants that
belong to value objects, not to ad hoc process code at call sites.

More examples: [Examples](../examples.md#core-examples).

## Failure model

`CommandResult` represents a process that was launched and supervised to a terminal outcome. Non-zero exit codes remain
results. `CommandResult.toException()` provides an exception view when the application wants fail-fast control flow.

`CommandExecutionException` is reserved for launch, supervision, or capture failures where iCLI could not produce a
normal command result.

## Scenario boundary

`run` does not request terminal capability. Use `interactive` or `lineSession` when a CLI requires a terminal or a
long-lived protocol.

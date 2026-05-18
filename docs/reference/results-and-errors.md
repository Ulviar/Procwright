# Results and Errors

iCLI keeps process outcomes and runtime failures separate.

## CommandResult

`CommandResult` is the completed one-shot outcome. It contains:

- optional exit code;
- stdout and stderr bytes;
- decoded stdout and stderr text;
- stdout/stderr truncation flags;
- timeout flag;
- elapsed duration.

`succeeded()` is true only when the command did not time out and exit code is zero.

`toException()` returns a `CommandException` that preserves the full result.

## CommandException

`CommandException` is an exception view of an unsuccessful `CommandResult`. Use it when application control flow should
throw on a non-zero exit or timeout while still preserving the result.

## CommandExecutionException

`CommandExecutionException` signals that iCLI could not start, supervise, or capture the process as a normal
`CommandResult`.

## SessionExit

`SessionExit` reports the exit state of an interactive session process through optional exit code and timeout flag.

## LineSessionException

`LineSessionException` includes a reason and a bounded transcript snapshot. Reasons are:

- `TIMEOUT`;
- `EOF`;
- `CLOSED`;
- `FAILURE`.

## ExpectException

`ExpectException` includes a reason and a bounded transcript snapshot. Reasons are:

- `TIMEOUT`;
- `EOF`;
- `CLOSED`;
- `FAILURE`.

## StreamExit and StreamException

`StreamExit` reports normal streaming completion with optional exit code, timeout flag, closed flag, bounded
diagnostics, and duration.

`StreamException` reports streaming failures and preserves bounded diagnostics.

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

iCLI-produced results keep captured bytes and decoded text aligned through the execution charset. Prefer results returned
by iCLI; if a custom adapter creates a `CommandResult` manually, it must keep byte and text views consistent.

## CommandException

`CommandException` is an exception view of an unsuccessful `CommandResult`. Use it when application control flow should
throw on a non-zero exit or timeout while still preserving the result.

## CommandExecutionException

`CommandExecutionException` signals that iCLI could not start, supervise, or capture the process as a normal
`CommandResult`. It carries a stable reason such as launch failure, decode failure, readiness timeout, readiness failure,
or runtime failure.

## SessionExit

`SessionExit` reports the exit state of an interactive session process through optional exit code and timeout flag.

## LineSessionException

`LineSessionException` includes a reason and a bounded transcript snapshot. Reasons are:

- `TIMEOUT`;
- `EOF`;
- `CLOSED`;
- `BROKEN_PIPE`;
- `DECODE_ERROR`;
- `RESPONSE_TOO_LARGE`;
- `STDOUT_BACKLOG_OVERFLOW`;
- `DECODER_FAILED`;
- `FAILURE`.

## ProtocolSessionException

`ProtocolSessionException` includes a stable reason, bounded transcript snapshot, and optional process exit code.
Reasons distinguish timeout, closed session, EOF, broken pipe, decode error, oversized request, oversized response,
output backlog overflow, adapter decoder failure, process exit, and general failure.

Protocol backlog overflow is reported as `OUTPUT_BACKLOG_OVERFLOW` because either stdout or stderr may be the stream
that overflowed.

The transcript snapshot records whether retained output was truncated, malformed for the selected charset policy, or
redacted.

## Pooled Session Exceptions

`PooledLineSessionException` and `PooledProtocolSessionException` report pool-level failures outside the underlying
request exception. Acquire timeout is distinct from request timeout. Startup failure is distinct from a worker failing
after it has been leased. Hook timeout reports a bounded health or reset hook that did not finish before its deadline.

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

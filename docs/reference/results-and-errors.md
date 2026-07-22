# Results and errors

All Procwright runtime failures extend `ProcwrightException`. Catch the scenario-specific type when code needs a stable
reason, transcript, or process result.

## Finite commands

`CommandResult` reports optional exit code, captured stdout/stderr bytes and text, truncation flags, timeout status, and
elapsed time. `succeeded()` requires exit code zero and no timeout. A normal non-zero exit is a result; `toException()`
creates `CommandException` while preserving it.

`CommandExecutionException` represents launch, supervision, or strict output-decoding failure and exposes a stable
`Reason`, message, and cause. `result()` is present only for `DECODE_ERROR`, where the process completed but captured
bytes could not be decoded under the selected policy. Launch and runtime failures do not promise a result or exit-code
snapshot.

## Sessions

- `LineSessionException` distinguishes request too large, timeout, EOF, closed, broken pipe, decode error, response too
  large, stdout backlog overflow, process exit, decoder failure, and other runtime failure. It preserves a bounded line
  transcript. Validation, request-size, encoding, and wait failures are retryable when the request was not handed off for
  stdin writing and cannot write later. Once handed off, timeout, interruption, write failure, and every response/protocol
  failure are terminal even if no received byte can be confirmed. Retryable failures leave `onExit()` incomplete;
  terminal failures complete it.
- `ProtocolSessionException` distinguishes timeout, closed, EOF, broken pipe, decode error, request or response too large,
  output backlog overflow, adapter decoder failure, process exit, and other runtime failure. It preserves a bounded
  protocol transcript. `exitCode()` is an `OptionalInt` snapshot and can be empty when the failure is selected.
- `ExpectException` distinguishes timeout, EOF, closed, and helper failure, with a bounded transcript.
- `StreamException` distinguishes listener failure, output-read failure, and process failure, with bounded diagnostics.

A framing, decode, EOF, or post-handoff failure closes a direct request session because subsequent protocol state cannot
be trusted.

Worker loss can surface as `EOF` or `PROCESS_EXITED` according to observation order. If output EOF is selected before a
process-exit snapshot is published, the request reports `EOF`; if process exit is selected first, it reports
`PROCESS_EXITED`. For protocol requests, `EOF` has an empty `exitCode()`, while `PROCESS_EXITED` carries the code only
when known. `LineSessionException` has no exit-code accessor; a directly owned line session reports an optional code later
through `onExit()`.

## Pools

Pooled requests keep worker request failures separate from pool orchestration failures. Timeout, EOF or
`PROCESS_EXITED`, broken pipe or write failure, decoding failure, response overflow, and output-backlog overflow are
thrown directly as `LineSessionException` or `ProtocolSessionException`; they are not wrapped in a pooled exception.

`PooledLineSessionException` and `PooledProtocolSessionException` cover acquisition, startup, surfaced hook or lifecycle
failures, and close. Their reason enums define `ACQUIRE_TIMEOUT`, `CLOSED`, `STARTUP_FAILED`, `HOOK_TIMEOUT`,
`INTERRUPTED`, `DRAIN_TIMEOUT`, and `WORKER_FAILED`. A pooled exception cause belongs to that pool phase, such as a
startup callback failure; it is not the wrapper for a normal worker request exception.
`PooledLineSessionMetrics.retireReasons()` and `PooledProtocolSessionMetrics.retireReasons()` return counts keyed by
`PooledWorkerRetireReason`; the same metrics snapshots expose startup, request, acquire, and lifecycle counts.

Use reason enums for program logic. Messages are for diagnostics and may change.

# Results and errors

Procwright's ordinary runtime failures extend `ProcwrightException`. Catch the scenario-specific type when code needs a
stable reason, transcript, or process result. Fatal `Error`s from application callbacks are not wrapped.

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
  failure are terminal even if no received byte can be confirmed. Retryable failures leave `onExit()` incomplete.
  Terminal failures close the process and complete a still-pending `onExit()` exceptionally with the selected session
  failure. A late decoder result does not rewrite an exit already settled by the process and output transport.
- `ProtocolSessionException` distinguishes timeout, closed, EOF, broken pipe, decode error, request or response too large,
  output backlog overflow, adapter decoder failure, process exit, and other runtime failure. It preserves a bounded
  protocol transcript. `exitCode()` is an `OptionalInt` snapshot and can be empty when the failure is selected.
- `ExpectException` distinguishes timeout, EOF, closed, and process I/O, decoding, or input-write failure, with a bounded
  transcript. Timeout while waiting for output or a matcher slot is retryable; abandonment of a regex evaluation is
  terminal, so `TIMEOUT` alone does not establish regex retryability. Output and input failures close the process and complete `onExit()` exceptionally
  with the selected failure when it is still pending. EOF reported before normal output drain also stops a process that
  is still live; EOF materialized after normal drain does not rewrite the process result. A physical stdin-close failure that
  arrives after `closeStdin()` returns can instead surface from `onExit()` as its original cause.
- `StreamException` distinguishes ordinary listener, output-read, and process failures, with bounded diagnostics. A fatal
  `Error` from listener or output processing completes `onExit()` with the same `Error` instance.

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

`PooledSessionException` covers acquisition, pool construction, worker startup,
surfaced hook or lifecycle failures, and close. Its reason enum defines `ACQUIRE_TIMEOUT`, `CLOSED`, `STARTUP_FAILED`,
`HOOK_TIMEOUT`, `INTERRUPTED`, `DRAIN_TIMEOUT`, and `WORKER_FAILED`. A pooled exception cause belongs to that pool phase,
such as a worker factory, readiness, or startup-execution failure during warmup; it is not the wrapper for a normal worker
request exception.
`PooledSessionMetrics.retireReasons()` returns counts keyed by `PooledWorkerRetireReason`; the same snapshot exposes
startup, request, acquire, and lifecycle counts.

Use reason enums for program logic. Messages are for diagnostics and may change.

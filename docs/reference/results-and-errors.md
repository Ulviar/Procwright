# Results and errors

Catch the scenario-specific exception when you need its reason or retained diagnostics. Ordinary runtime failures
extend `ProcwrightException`; fatal `Error`s from application callbacks are not wrapped. Use reason enums in program
logic. Exception messages are for diagnosis and can change.

## Finite commands

`CommandResult` contains an optional exit code, stdout/stderr bytes and text, truncation flags, timeout status, and
elapsed time. `succeeded()` requires exit code zero and no timeout.

| Outcome | What the caller receives |
| --- | --- |
| Exit code zero | A result; check truncation if complete output matters. |
| Non-zero exit | A result with output available for inspection. |
| Execution timeout | A result with `timedOut() == true`. |
| Launch, I/O, or supervision failure | `CommandExecutionException`, without a completed result. |
| Decoding failure | `CommandExecutionException` with reason `DECODE_ERROR` and a result containing captured bytes. |

Call `result.toException()` when your application wants to throw for an unsuccessful result. It creates a
`CommandException` that retains the result. Do not assume `CommandExecutionException.result()` is present: it is available
only for `DECODE_ERROR`. See the [launch-failure example](../examples/java/io/github/ulviar/procwright/examples/RunFailureExample.java).

## Sessions

| Exception | What it describes |
| --- | --- |
| `LineSessionException` | Request/response limits, timeout, EOF, closed session, broken pipe, decoding, worker exit, or decoder failure; includes a bounded line transcript. |
| `ProtocolSessionException` | Request/response limits, timeout, EOF, closed session, broken pipe, decoding, worker exit, or adapter failure; includes a bounded protocol transcript and optional exit code. |
| `ExpectException` | Match timeout, EOF, closed handle, or process I/O/decoding failure; includes a bounded transcript. |
| `StreamException` | Listener, output-read, or process failure; includes bounded diagnostics. A fatal listener/output `Error` instead completes `onExit()` with that same error. |

### Decide whether the session can be reused

For line sessions, validation, request-size, encoding, and waiting failures leave the session usable only when the
request has not been handed off for writing and cannot write later. After handoff, a timeout, interruption, write error,
or response failure closes the session. The child may have received the request even if no reply was observed.

For protocol sessions, a timeout while waiting for the serialized request slot leaves the session usable. Once the slot
is acquired, request or response failure closes it. A retry after an uncertain write can repeat the command's side
effects; retry only when your application protocol allows that.

For Expect, waiting for output or the matcher slot can time out without closing the handle. A regex evaluation that
cannot be stopped makes the handle terminal. `TIMEOUT` alone therefore does not establish whether another regex match
is safe. See the [Expect contract](../scenarios/expect.md).

### Interpret limits and worker exit

`RESPONSE_TOO_LARGE` can mean that a response exceeded its limit or that unread output filled the same bounded buffer.
It can therefore indicate excessive unsolicited worker output. Protocol stderr overflow is reported only if the adapter
reads stderr.

Worker loss can appear as `EOF` or `PROCESS_EXITED`, depending on whether output end or process exit was observed first.
A protocol exception's `exitCode()` may be empty; `EOF` does not carry an exit code. Line exceptions have no exit-code
accessor; the directly owned session reports its process outcome through `onExit()`.

Terminal session failures close the process and complete a still-pending `onExit()` exceptionally. A failure discovered
later does not replace a previously completed exit future. Always close the handle in a resource scope.

## Pools

A pooled request keeps the worker's `LineSessionException` or `ProtocolSessionException`. It is not wrapped in a pool
exception. The failed worker is retired; the pool can serve a later request with another worker.

`PooledSessionException` covers acquisition, startup, hooks, and pool cleanup. Its reasons are `ACQUIRE_TIMEOUT`,
`CLOSED`, `STARTUP_FAILED`, `HOOK_TIMEOUT`, `INTERRUPTED`, `DRAIN_TIMEOUT`, and `WORKER_FAILED`.
Inspect the cause for the underlying failure in that pool phase.

A `DRAIN_TIMEOUT` from `close()` does not cancel cleanup. Observe `closeAsync()` for eventual completion.
[Pooling](../scenarios/pooling.md) explains lifecycle and metrics, including worker retirement reasons.

# Policies

Policies are value objects or enums for decisions that should be explicit at the call site: environment inheritance,
capture limits, shutdown escalation, output mode, terminal capability, and charset error handling.

## EnvironmentPolicy

- `INHERIT` starts from the current process environment and applies configured overrides.
- `CLEAN` starts from an empty environment and applies only configured overrides.

Use `cleanEnvironment()` for less trusted CLIs or for reproducible command environments.

## CapturePolicy

`CapturePolicy.bounded(byteLimit)` retains at most the configured number of bytes per stream for `run` results.
Truncation is reported on `CommandResult` through stdout/stderr truncation flags.

`CapturePolicy.discard()` drops both streams at the operating-system level. No pump threads run and no output is
retained.

`CapturePolicy.toPath(stdout, stderr)` redirects the streams to two distinct files; it requires `OutputMode.SEPARATE`.
`CapturePolicy.toPath(merged)` writes the merged stream to one file and requires `OutputMode.MERGED`. Redirection
happens at the operating-system level, so memory stays constant regardless of output volume and existing file content
is overwritten.

For discarded and redirected streams `CommandResult.stdout()`/`stderr()` and the byte views are empty and the
truncation flags stay `false`; exit code, `timedOut()`, and `elapsed()` are reported as usual, and timeout supervision
with process-tree cleanup is unchanged. Invalid combinations (single-file capture with `SEPARATE`, two-file capture
with `MERGED`, identical or blank paths) are rejected before the process starts.

## ShutdownPolicy

`ShutdownPolicy.interruptThenKill(interruptGrace, killGrace)` defines the shutdown escalation path used by timeout,
failure, explicit close, and idle-timeout paths where applicable.

The current runtime applies shutdown to the process tree through JDK `ProcessHandle` where the platform permits it.

```java
CommandService logs = Procwright.command("tool");

CommandResult result = logs.run()
        .withArgs("logs")
        .withTimeout(Duration.ofSeconds(30))
        .withCapture(CapturePolicy.bounded(128 * 1024))
        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)))
        .execute();
if (result.timedOut()) {
    throw result.toException();
}
```

## OutputMode

- `SEPARATE` captures stdout and stderr independently.
- `MERGED` redirects stderr into stdout and leaves `stderr()` empty.

## TerminalPolicy

- `DISABLED` never requests terminal capability.
- `AUTO` allows terminal transport when available.
- `REQUIRED` fails when terminal transport is unavailable.

Terminal policy is valid only for session-family workflows.

## CharsetPolicy

`CharsetPolicy.replace(charset)` keeps the forgiving default: malformed and unmappable bytes decode with the replacement
character.

`CharsetPolicy.report(charset)` turns malformed or unmappable bytes into typed failures. Use it for Unicode-sensitive
protocols where silent `�` replacement would hide data corruption.

## Default option families

- `RunOptions` defines capture, shutdown, timeout, charset, and output mode defaults for `run`.
- `SessionOptions` defines idle timeout, shutdown, charset, terminal policy, PTY provider, and terminal size defaults.
- `LineSessionOptions` defines request timeout, transcript limit, backlog limit, maximum line length, charset, and
  response decoder.
- `ProtocolSessionOptions` defines request timeout, transcript limit, output backlog limit, request/response size
  limits, and charset policy.
- `ExpectOptions` defines match timeout, transcript limit, match buffer limit, charset, output filter, and transcript
  value policy.
- `StreamOptions` defines stream timeout, shutdown, charset, and diagnostic limit.
- `PooledLineSessionOptions` defines size, warmup, min idle, acquire timeout, hook timeout, worker retirement,
  replenishment, reset, and health policies.
- `PooledProtocolSessionOptions` defines size, warmup, min idle, acquire timeout, hook timeout, worker retirement, and
  replenishment policies for typed protocol workers.

## Default values

`Procwright.command(...)` starts every scenario from the defaults below. Override them per service through
`with*Options(...)` or per call on the scenario builder. Failure shapes are described in
[Results and Errors](results-and-errors.md).

### `RunOptions` (`run`)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `timeout` | 30 seconds (`Duration.ZERO` disables it) | The process tree is stopped through the shutdown policy and `CommandResult.timedOut()` is `true`. With `Duration.ZERO` the run waits until the process exits on its own; the shutdown policy still applies on failure paths. |
| `capturePolicy` | `bounded(1 MiB)` per stream | The first 1 MiB per stream is retained, the rest is drained and discarded, and the matching truncation flag on `CommandResult` is set. Alternatives: `discard()` drops both streams; `toPath(...)` redirects to files (see [CapturePolicy](#capturepolicy)) — both leave the result streams empty and the truncation flags `false`. |
| `shutdownPolicy` | `interruptThenKill(2 s, 5 s)` | Graceful stop with a 2-second grace, then forceful kill with a 5-second grace; a process that survives both raises `CommandExecutionException`. |
| `charsetPolicy` | `CharsetPolicy.replace(UTF-8)` | Malformed or unmappable bytes decode as replacement characters instead of failing. |
| `outputMode` | `SEPARATE` | stdout and stderr are captured independently. |
| stdin | closed | Stdin is closed immediately unless per-call input is configured: in-memory `CommandInput` bytes are written and stdin is closed, while `CommandInput.fromPath(file)` redirects stdin from the file at the operating-system level (the file must exist at launch). |

### `SessionOptions` (`interactive`)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `idleTimeout` | `Duration.ZERO` (disabled) | When enabled, a session with no caller-visible activity is stopped through the shutdown policy and `SessionExit.timedOut()` is `true`. |
| `shutdownPolicy` | `interruptThenKill(2 s, 5 s)` | Used by explicit close and idle timeout. |
| `charset` | UTF-8 | Used by text send helpers. |
| `terminalPolicy` | `DISABLED` | Sessions use ordinary pipes unless a terminal is requested. |
| `ptyProvider` | `PtyProvider.system()` | Used only when a session requests a terminal. |
| `terminalSize` | 80 columns x 24 rows | Requested dimensions for PTY-backed sessions. |

### `LineSessionOptions` (`lineSession`)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `requestTimeout` | 5 seconds | The request fails with `LineSessionException` reason `TIMEOUT`. |
| `transcriptLimit` | 65,536 characters | Older transcript content is discarded and the transcript snapshot is marked truncated. |
| `stdoutBacklogLines` | 1024 pending lines | The session fails with reason `STDOUT_BACKLOG_OVERFLOW`. The limit counts lines, not bytes. |
| `maxLineChars` | 1,048,576 characters | A stdout line whose content exceeds the limit fails with reason `RESPONSE_TOO_LARGE`. The limit counts line content only — the LF or CRLF terminator is excluded, so a line of exactly `maxLineChars` characters plus its terminator is accepted. |
| `charsetPolicy` | `CharsetPolicy.replace(UTF-8)` | Malformed bytes decode as replacement characters; a `report` policy fails with reason `DECODE_ERROR`. |
| `responseDecoder` | `ResponseDecoder.firstLine()` | One response is the first stdout line. |

### `ProtocolSessionOptions` (`protocolSession`)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `requestTimeout` | 5 seconds | The request fails with `ProtocolSessionException` reason `TIMEOUT`. |
| `transcriptLimit` | 65,536 characters | Older transcript content is discarded and the transcript snapshot is marked truncated. |
| `outputBacklogLimit` | 1 MiB pending bytes per stream | Unread stdout over the limit fails the session with reason `OUTPUT_BACKLOG_OVERFLOW`; unread stderr never fails the session — its oldest pending bytes are dropped and stderr stays readable up to the limit. |
| `maxRequestBytes` | 1 MiB | A larger request fails with reason `REQUEST_TOO_LARGE`. |
| `maxRequestChars` | unlimited (`Integer.MAX_VALUE`) | Character limit for text write helpers. |
| `maxResponseBytes` | 1 MiB | A larger response fails with reason `RESPONSE_TOO_LARGE`. |
| `maxResponseChars` | unlimited (`Integer.MAX_VALUE`) | Character limit for text read helpers. |
| `charsetPolicy` | `CharsetPolicy.replace(UTF-8)` | Malformed bytes decode as replacement characters; a `report` policy fails with reason `DECODE_ERROR`. |

### `ExpectOptions` (`interactive` + `Expect`)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `timeout` | 5 seconds | The match fails with `ExpectException` reason `TIMEOUT`. |
| `transcriptLimit` | 65,536 characters | Older transcript content is discarded. |
| `matchBufferLimit` | 65,536 characters | Older unmatched stdout is dropped from the match window. |
| `charset` | UTF-8 | Output decoding charset. |
| `outputFilter` | identity | Output is matched as received. |
| `transcriptValues` | `REDACTED` | Caller-provided values are not recorded in action transcript entries. |

### `StreamOptions` (`listen`)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `timeout` | `Duration.ZERO` (disabled) | When enabled, the stream is stopped through the shutdown policy and `StreamExit.timedOut()` is `true`. |
| `shutdownPolicy` | `interruptThenKill(2 s, 5 s)` | Used by timeout and close. |
| `charset` | UTF-8 | Chunk decoding charset. |
| `diagnosticLimit` | 65,536 characters | Older diagnostic output is discarded from the bounded diagnostics window. |

### `PooledLineSessionOptions` and `PooledProtocolSessionOptions` (`...pooled()`)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `maxSize` | 1 worker | Requests beyond capacity wait for an available worker. |
| `warmupSize` | 0 | No workers are opened when the pool is created. |
| `minIdle` | 0 | No background idle-worker target. |
| `acquireTimeout` | 5 seconds | Acquisition fails with the pooled exception's acquire-timeout reason. |
| `hookTimeout` | 5 seconds | A health or reset hook over its deadline fails with the pooled exception's hook-timeout reason. |
| `maxRequestsPerWorker` | unlimited (`Integer.MAX_VALUE`) | A worker over the limit is retired and replaced. |
| `maxWorkerAge` | `Duration.ZERO` (disabled) | When enabled, a worker over the age limit is retired and replaced. |
| `backgroundReplenishment` | enabled | Retired workers may be replaced in the background. |
| `resetHook` (line pool only) | no-op | Run after a successful request before the worker returns to the pool. |
| `healthCheck` (line pool only) | process alive | Run before a worker is leased. |

### `DiagnosticsOptions` (all scenarios)

| Setting | Default | When it triggers |
| --- | --- | --- |
| `listener` | no-op | Diagnostic events are not delivered. |
| `transcriptSink` | no-op | Transcripts are not recorded. |

### `CommandSpec`

| Setting | Default | When it triggers |
| --- | --- | --- |
| `environmentPolicy` | `INHERIT` | The child starts from the current process environment plus configured overrides. |
| command-line mode | direct argv | Shell mode is used only when requested through `CommandSpec.shell(...)`. |

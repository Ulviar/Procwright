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

## ShutdownPolicy

`ShutdownPolicy.interruptThenKill(interruptGrace, killGrace)` defines the shutdown escalation path used by timeout,
failure, explicit close, and idle-timeout paths where applicable.

The current runtime applies shutdown to the process tree through JDK `ProcessHandle` where the platform permits it.

```java
CommandService logs = Icli.command("tool");

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

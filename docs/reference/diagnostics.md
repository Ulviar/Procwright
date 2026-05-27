# Diagnostics

Diagnostics are observability hooks over command scenarios. They are not a logging framework and they do not change
command behavior.

## Options

Attach diagnostics through `DiagnosticsOptions` on `CommandService`.

```java
CommandService tool = Procwright.command("tool")
        .withDiagnostics(DiagnosticsOptions.defaults().withListener(event -> {
            if (event.attributes().containsKey("exitCode")) {
                System.out.println(
                        event.type() + ":" + event.attributes().get("exitCode"));
            }
        }));

tool.run().execute("--version");
```

## Event shape

Each `DiagnosticEvent` has the same stable outer shape:

- `type`: event type;
- `runId`: process-lifecycle correlation id;
- `timestamp`: event timestamp;
- `scenario`: scenario that emitted the event;
- `command`: redaction-friendly command echo;
- `attributes`: event-specific structured attributes.

Use `runId` to correlate lifecycle events for one process. Diagnostic callbacks are delivered asynchronously, so do not
rely on callback order across threads. If a listener throws, Procwright emits `LISTENER_FAILED` when it can and continues the
command workflow; listener failure does not turn a successful command into a failed command.

## Redaction model

Diagnostic attributes are intentionally small and redaction-friendly. They must not contain raw argv values,
environment values, stdin, stdout, or stderr.

`CommandEcho` exposes executable, argument count, working directory, environment variable names, output mode, and
terminal policy. It does not expose argument values or environment values.

## Event-specific attributes

| Event | Stable attributes |
| --- | --- |
| `COMMAND_PREPARED` | none |
| `PROCESS_STARTED` | `pid` |
| `OUTPUT_TRUNCATED` | `source` plus `limitBytes` or `limitChars` |
| `TIMEOUT_REACHED` | none |
| `SHUTDOWN_REQUESTED` | `reason` |
| `LISTENER_FAILED` | none |
| `PROCESS_EXITED` | `timedOut`, optional `exitCode` |
| `PROCESS_FAILED` | `error` |

Diagnostics are for observation, not control flow. Do not use a diagnostic listener as the only place that enforces
application correctness.

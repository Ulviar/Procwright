# Scenario defaults

Procwright starts every scenario from bounded, non-terminal defaults. These values matter before the first `with*` call:

- `run()` stops a process after 30 seconds;
- one-shot capture retains at most 1 MiB from each output stream;
- text decoding uses UTF-8 and replaces malformed input unless a strict `CharsetPolicy` is selected;
- interactive, line, and protocol sessions have no idle timeout;
- `listen()` has no absolute timeout;
- a pool starts with capacity for one worker and `close()` waits at most 15 seconds for requests and worker cleanup.

The tables below are the authoritative user-facing defaults. A scenario inherits executable, base arguments, working
directory, environment policy, and environment entries from the `CommandSpec` owned by its `CommandService`.

## Run

| Setting | Default |
| --- | --- |
| Capture | 1 MiB per stdout and stderr stream, retained in memory |
| Timeout | 30 seconds |
| Shutdown | interrupt, wait 2 seconds, force kill, wait 5 seconds |
| Charset policy | UTF-8 with malformed and unmappable input replaced |
| Output mode | separate stdout and stderr |
| Input | none; stdin is closed after launch |
| Diagnostics | no listener and no transcript sink |

Set `withTimeout(Duration.ZERO)` to disable the absolute timeout. Capture truncation is reported by `CommandResult`;
redirected or discarded output is not retained in the result.

## Interactive sessions

| Setting | Default |
| --- | --- |
| Idle timeout | disabled (`Duration.ZERO`) |
| Shutdown | interrupt, wait 2 seconds, force kill, wait 5 seconds |
| Charset | UTF-8 |
| Terminal policy | `DISABLED` |
| Terminal provider | built-in system provider, used only after selecting `AUTO` or `REQUIRED` |
| Terminal size | 80 columns by 24 rows |
| Readiness probe | none |
| Readiness timeout | 5 seconds when a probe is configured |
| Diagnostics | no listener and no transcript sink |

## Line sessions

Line sessions inherit the interactive-session defaults and add these request/response defaults:

| Setting | Default |
| --- | --- |
| Request timeout | 5 seconds |
| Retained transcript | 65,536 characters |
| Unread stdout backlog | 1,024 lines and 1,048,576 characters |
| Maximum line | 1,048,576 characters |
| Maximum request | 1 MiB of encoded bytes and 1,048,576 characters |
| Maximum response | 1,024 lines and 1,048,576 characters |
| Charset policy | UTF-8 with malformed and unmappable input replaced |
| Response decoder | first stdout line |

Request and response limits apply to one exchange. The transcript limit only controls retained diagnostics.

## Protocol sessions

Protocol sessions inherit the interactive-session defaults and add these adapter budgets:

| Setting | Default |
| --- | --- |
| Request timeout | 5 seconds |
| Retained transcript | 65,536 characters |
| Unread output backlog | 1 MiB |
| Maximum request | 1 MiB of bytes and `Integer.MAX_VALUE` characters |
| Maximum response | 1 MiB of bytes and `Integer.MAX_VALUE` characters |
| Charset policy | UTF-8 with malformed and unmappable input replaced |

Per-call limits passed to `ProtocolReader` methods apply in addition to these response-global limits.

## Streaming

| Setting | Default |
| --- | --- |
| Absolute timeout | disabled (`Duration.ZERO`) |
| Shutdown | interrupt, wait 2 seconds, force kill, wait 5 seconds |
| Charset | UTF-8 |
| Retained failure diagnostics | 65,536 characters |
| Output listener | no-op |
| Input | none; stdin is closed after launch |
| Diagnostics | no listener and no transcript sink |

## Expect

`session.expect()` starts from these defaults. The session still owns its own shutdown and idle-timeout settings.

| Setting | Default |
| --- | --- |
| Match timeout | 5 seconds |
| Retained transcript | 65,536 characters |
| Match buffer | 65,536 characters |
| Charset | the underlying session charset |
| ANSI CSI stripping | disabled |
| Transcript values | redacted |

## Line and protocol pools

Both pool types use the same lifecycle defaults. Worker request, protocol, readiness, terminal, and shutdown settings
come from the Draft on which `pooled()` was called.

| Setting | Default |
| --- | --- |
| Maximum workers | 1 |
| Eager warmup workers | 0 |
| Minimum idle workers | 0 |
| Acquire timeout | 5 seconds |
| Reset and health-hook timeout | 5 seconds |
| Synchronous close timeout | 15 seconds |
| Requests per worker | 2,147,483,647 |
| Maximum worker age | disabled (`Duration.ZERO`) |
| Background replenishment | enabled |
| Reset hook | no-op |
| Health check | healthy while the worker process has not exited |

The 15-second close timeout bounds the caller's wait. It does not abandon internal worker cleanup; use `closeAsync()` to
observe eventual completion after a timed-out `close()`.

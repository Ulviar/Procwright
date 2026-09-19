# Scenario defaults

These values apply until you override them with a `with*` method. Commands inherit their executable, arguments, working
directory, and environment from `CommandSpec`.

`run()` has a 30-second timeout. Long-lived sessions have no idle timeout by default, and `listen()` has no absolute
timeout. Close their handles when finished or configure a timeout for your task.

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
| Text send charset | UTF-8; stdout and stderr remain caller-owned bytes decoded by the caller |
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
| Maximum request | 1 MiB of encoded bytes and 1,048,576 characters |
| Maximum response | 1,024 lines and 1,048,576 characters |
| Charset policy | UTF-8 with malformed and unmappable input replaced |
| Response decoder | first stdout line |

Request and response limits apply to one exchange. The unread stdout queue uses the response line and character limits;
an unfinished line uses the response character limit. Set `withMaxResponseLines(...)` and `withMaxResponseChars(...)`
when the worker returns larger responses. LF/CRLF separators do not count as content characters. The transcript limit
only controls retained diagnostics.

## Protocol sessions

Protocol sessions inherit the interactive-session defaults and add these adapter budgets:

| Setting | Default |
| --- | --- |
| Request timeout | 5 seconds |
| Retained transcript | 65,536 characters |
| Maximum request | 1 MiB of bytes and `Integer.MAX_VALUE` characters |
| Maximum response | 1 MiB of bytes and `Integer.MAX_VALUE` characters |
| Charset policy | UTF-8 with malformed and unmappable input replaced |

Per-call limits passed to `ProtocolReader` methods apply in addition to these response-global limits.
Each unread stdout/stderr queue uses `withMaxResponseBytes(...)` as its bound. Include framing bytes such as headers and
delimiters in this limit. It also bounds the total bytes read across both streams for one response.

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

`interactive().expect()` starts from these defaults. The same draft also configures process shutdown and idle timeout.

| Setting | Default |
| --- | --- |
| Match timeout | 5 seconds |
| Retained transcript | 65,536 characters |
| Match buffer | 65,536 characters |
| Input and output charset | UTF-8; output follows input unless `withOutputCharset(...)` overrides it |
| ANSI CSI stripping | disabled |
| Transcript values | redacted |

## Line and protocol pools

Both pool types use the same lifecycle defaults. Worker request, protocol, readiness, terminal, and shutdown settings
come from the Draft on which `pooled()` was called.

| Setting | Default |
| --- | --- |
| Maximum workers per pool | 1 (allowed range: 1 through 256) |
| Eager warmup workers | 0 |
| Minimum idle workers | 0 (background replenishment disabled) |
| Acquire timeout | 5 seconds |
| Reset and health-hook timeout | 5 seconds |
| Synchronous close timeout | 15 seconds |
| Requests per worker | 2,147,483,647 |
| Maximum worker age | disabled (`Duration.ZERO`) |
| Reset hook | none |
| Health check | healthy while the worker process has not exited |

The 15-second close timeout bounds the caller's wait. It does not abandon worker shutdown; use `closeAsync()` to
observe eventual completion after a timed-out `close()`. Neither close method waits for potentially blocking
physical process-stream close.

Each pool applies its own maximum to starting, idle, leased, and retiring workers. Pools and directly opened sessions do
not share a worker quota; the application controls the aggregate process count through the resources it creates.

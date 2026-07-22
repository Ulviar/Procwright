# Output ownership

Exactly one component may consume a process output stream.

| API | Output owner | Caller action |
| --- | --- | --- |
| `run()` | Procwright capture runtime | Read `CommandResult`; do not add stream pumps. |
| Raw `interactive()` | Caller | The first effective stdout/stderr operation selects raw mode; drain both streams, usually concurrently. |
| `session.expect().open()` | `Expect` | Do not operate on raw output; closing `Expect` closes the session. |
| `lineSession()` | Line decoder | Use `request`; do not read raw output. |
| `protocolSession(factory)` | Adapter decoder | Read only through `ProtocolReader`. |
| `listen()` | Output listener | Handle delivered chunks; do not add raw readers. |
| Pools | Active worker protocol | Use pool request methods; leases and streams stay internal. |

Getting a raw stdout or stderr wrapper does not choose an owner. Reading, inspecting, marking, resetting, or closing either
wrapper does. After raw mode is selected, opening a helper fails with `IllegalStateException`; after a helper claim, raw
operations fail with the same exception.

Ownership is not handed back between modes. Closing a helper closes its underlying session and starts process cleanup.

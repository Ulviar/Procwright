# Output ownership

Exactly one component may consume a process output stream.

| API | Output owner | Caller action |
| --- | --- | --- |
| `run()` | Procwright capture runtime | Read `CommandResult`; do not add stream pumps. |
| Raw `interactive()` | Caller | Drain both streams, usually concurrently. |
| `interactive().expect()` | `Expect` | Match output through `Expect`; raw streams are not exposed. |
| `lineSession()` | Line decoder | Use `request`; do not read raw output. |
| `protocolSession(factory)` | Adapter decoder | Read only through `ProtocolReader`. |
| `listen()` | Output listener | Handle delivered chunks; do not add raw readers. |
| Pools | Active worker protocol | Use pool request methods; leases and streams stay internal. |

The scenario selects the owner before process launch. A raw `Session` cannot be converted into Expect, line, protocol, or
listen mode later. Closing a scenario handle starts cleanup for its process.

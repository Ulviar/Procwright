# Output Ownership

Output ownership answers one question: who is allowed to read stdout and stderr for a process?

This is a public contract because mixing output readers is a common source of deadlocks, lost output, and inconsistent
diagnostics. Pick one scenario owner for each process.

## Ownership by scenario

| Scenario | stdout/stderr owner | Caller access | Retained output |
| --- | --- | --- | --- |
| `run` | iCLI runtime | `CommandResult` after completion | Bounded stdout/stderr bytes and text. |
| `listen` | `StreamSession` listener pipeline | `StreamListener` callbacks while alive | Bounded diagnostics, not full output. |
| `interactive` | Caller, until a helper claims ownership | Raw session streams and stdin | None unless caller stores it. |
| `Expect` | `Expect` helper over a `Session` | Expect/send operations | Bounded transcript. |
| `lineSession` | Line-session decoder | `request(...)` responses | Bounded transcript and backlog. |
| `protocolSession` | Protocol adapter decoder | Typed `request(...)` responses | Bounded transcript and output backlog. |
| `pooled` | Pooled line-session workers | Pool request API | Worker metrics and bounded worker transcripts on failures. |
| `pooledProtocol` | Pooled protocol-session workers | Pool request API | Worker metrics and bounded worker transcripts on failures. |
| Integrations | Integration adapter | Structured adapter API | Adapter-specific bounded results/errors. |

## Practical rules

- Do not read raw `Session.stdout()` or `Session.stderr()` after handing the session to `Expect`, `lineSession`,
  `protocolSession`, or `listen`-style helpers.
- Do not add separate pump threads around `run`. The `run` scenario already drains both streams.
- Do not use `listen` when the caller needs a complete final stdout/stderr result. Use `run` instead.
- Do not use `run` for unbounded log following. Use `listen` and keep listener work bounded.
- Do not keep a line worker alive after timeout or decoder failure. `lineSession` closes because the protocol state is
  unknown.

## Choosing between similar cases

Use `run` for "finish and return a result". Use `listen` for "process output as it arrives". Use `interactive` when the
caller owns a custom protocol. Add `Expect` only for prompt matching. Use `lineSession` only for serialized
request/response line protocols. Use `protocolSession` for adapter-owned framed, multi-line, byte, or typed
request/response protocols.

Diagnostics observe lifecycle events, but they do not own command output and do not change execution behavior.

# Choose a process scenario

Choose how your application will interact with the process before starting it. A finite command needs an overall
timeout and captured output; a worker needs request boundaries and a timeout for each exchange. Each scenario keeps
the relevant settings together.

| Your task | API | Next step |
| --- | --- | --- |
| Run a command, wait, use its output or exit status | `run()` | [First command](../getting-started.md) |
| Send repeated requests to a long-lived worker | `lineSession()` or `protocolSession(...)` | Choose the message format below |
| Receive logs or events while the process runs | `listen()` | [Follow live output](follow-logs.md) |

## Choose a worker's message format

The program must already accept repeated requests while staying alive. For a tool that exits after each command,
use `run()` for each call.

| What the worker speaks | Start here |
| --- | --- |
| One request line; one or more response lines | [Line session](talk-to-line-worker.md) |
| JSON Lines with application request/response types | [Typed JSON Lines service](wrap-cli-tool.md) |
| Delimiter-framed bytes or Content-Length JSON | [Ready-made adapters](../scenarios/integrations.md) |
| Another framing or decoding convention | [Write a protocol adapter](../scenarios/protocol-session.md#custom-framing) |

A direct session keeps one process alive and serializes its requests. Keep it for sequential calls or worker-local state.
Add [pooling](reuse-workers.md) when independent concurrent requests can use interchangeable workers; pooling does not
provide caller-to-worker affinity.

## Prompts and direct process control

- Match prompts and send replies with [`interactive().expect()`](automate-prompts.md).
- Own the raw stdin/stdout protocol with [`interactive()`](../scenarios/interactive.md).
- [Require a terminal](require-terminal.md) only when the CLI needs terminal behavior.

Choose the scenario before `open()`: it determines [who reads output](../reference/output-ownership.md).
Two readers would compete for the same bytes, so an open raw session cannot later become an Expect or protocol session.
Close sessions and pools with try-with-resources or Kotlin `use`. For file input and output, stay with
[`run()`](../scenarios/run.md#files); it does not require a separate scenario.

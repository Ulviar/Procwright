# Procwright

Procwright controls external command-line processes through workflow-specific APIs. Start with the process behavior you
need, configure its immutable Draft with `with*` methods, then call `execute()` or `open()`.

## Start here

- [Install Procwright and run a command](getting-started.md).
- [Choose the right process scenario](how-to/choose-process-scenario.md).
- [Open a complete, runnable example](examples.md).
- [Check lifecycle, timeout, output, and error contracts](reference/index.md).
- [Use the optional Kotlin extensions](reference/kotlin-api.md).

## Scenario map

| Task | Scenario |
| --- | --- |
| Run a finite command and capture a result | [`run`](scenarios/run.md) |
| Control a live process or automate prompts | [`interactive`](scenarios/interactive.md) and [`Expect`](scenarios/expect.md) |
| Exchange line requests | [`lineSession`](scenarios/line-session.md) |
| Implement a custom framed protocol | [`protocolSession`](scenarios/protocol-session.md) |
| Consume output as it arrives | [`listen`](scenarios/streaming.md) |
| Distribute independent concurrent requests across interchangeable workers | [pooling](scenarios/pooling.md) |

A direct line or protocol session already reuses one process. Pooling adds concurrent workers; it is unnecessary for
sequential requests to one worker and does not provide worker affinity for stateful requests.

The planned first release is `0.1.0`. It targets and requires Java 25. No public artifact has been
published yet; [installation](release/installation.md) uses Maven Local from this checkout.

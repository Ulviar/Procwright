# Procwright

Call external CLI tools from Java or Kotlin. For finite commands, Procwright handles stdin, concurrent output reading,
timeouts, and process cleanup. Sessions let you make repeated requests to a worker, match prompts, or consume live output.

Requires **Java 25**. The core has no runtime dependencies outside the JDK. See
[platform and terminal support](reference/platforms-and-pty.md) and [cleanup guarantees](explanations/process-cleanup-limits.md)
for the boundaries that affect your choice.

## Start with a task

| Task | Walkthrough |
| --- | --- |
| Run a command and inspect its result | [First command](getting-started.md) |
| Add Procwright to an existing application | [Dependency setup](release/installation.md) |
| Read input from a file or save large output | [File input and output](scenarios/run.md#files) |
| Make repeated typed requests to one worker | [CLI worker as a service](how-to/wrap-cli-tool.md) |
| Show logs and progress as they arrive | [Follow live output](how-to/follow-logs.md) |

The checkout demos require JDK 25 and include their own workers. Start with `./gradlew -q demoRun`.

One session already reuses one process. Once independent requests need multiple workers, continue to
[concurrent requests with a pool](how-to/reuse-workers.md).

## Find the right level of detail

- [Choose a scenario](how-to/choose-process-scenario.md) for line protocols, custom framing, prompts, and raw streams.
- [Runnable examples](examples.md) lists demo commands and their source files.
- [Reference](reference/index.md) defines settings, defaults, results, and lifecycle contracts.
- [Kotlin extensions](reference/kotlin-api.md) adds coroutines and Flow to the same core API.

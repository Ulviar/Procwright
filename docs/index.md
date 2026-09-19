# Procwright

Use an external CLI as a managed component of your Java or Kotlin application. Procwright handles the process lifecycle,
timeouts, and output bounds while your code calls the tool.

## Start with a task

| Task | Walkthrough |
| --- | --- |
| Run a command and inspect its result | [First command](getting-started.md) |
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
- [Dependency setup](release/installation.md) connects a separate application to this checkout.

# Procwright

[![CI](https://github.com/Ulviar/Procwright/actions/workflows/ci.yml/badge.svg)](https://github.com/Ulviar/Procwright/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Use an external CLI as a managed component of your Java or Kotlin application. Run a command, make repeated requests to
a worker, or consume live output. Procwright owns timeouts, output bounds, and process cleanup for the chosen workflow.

## Try it

From this checkout, with **JDK 25** available:

```shell
./gradlew -q demoRun
```

On Windows, use `.\gradlew.bat -q demoRun`. The demo prints the current Java version followed by `succeeded=true`.
Gradle builds directly from this checkout and supplies the classpath.

The central call is:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#run -->
```java
CommandResult result =
        Procwright.command(executable).run().withArgs(arguments).execute();
```

`executable` is a program path or name; `arguments` contains separate argv entries. The demo supplies the current JDK
and `--version`. Defaults are a 30-second timeout and at most 1 MiB of retained output per stream.

[Run your own command](docs/getting-started.md) · [Complete source](docs/examples/java/io/github/ulviar/procwright/examples/RunExample.java)

## What do you need to do?

| Your task | Start here |
| --- | --- |
| Run a tool and use its output or exit status | [Run a finite command](docs/how-to/run-finite-command.md) |
| Read input from a file or save large output | [File input and output](docs/scenarios/run.md#files) |
| Call a long-lived CLI as a typed service | [JSON Lines worker → service](docs/how-to/wrap-cli-tool.md) |
| Show logs and progress as they arrive | [Follow live output](docs/how-to/follow-logs.md) |

A session already reuses one process. Add a [pool](docs/how-to/reuse-workers.md) for independent concurrent requests to
interchangeable workers. For prompts, raw streams, or custom framing, use the [scenario chooser](docs/how-to/choose-process-scenario.md).

## Learn the API

The API follows one sequence: **command → scenario → configuration → execute/open**.

- A command stores the executable and shared launch context.
- A scenario chooses how you interact with the process.
- Each `with*` call returns a new configuration (called a Draft); retain its return value.
- `execute()` returns a result. `open()` returns a handle to close with try-with-resources or Kotlin `use`.

For reusable workers, set request and response limits; output buffers follow the response limits automatically.

See [Getting started](docs/getting-started.md), [runnable examples](docs/examples.md), and the
[API and policy reference](docs/reference/index.md). To use Procwright in a separate application, see
[dependency setup](docs/release/installation.md).

## Modules

- `procwright`: Java core, with no runtime dependency outside the JDK.
- `procwright-kotlin`: Kotlin durations, coroutine calls, Flow, and adapter factory DSL.
- `procwright-integrations`: ready-made JSON and byte-framing adapters.

All modules require Java 25. See [Kotlin usage](docs/reference/kotlin-api.md),
[platform support](docs/reference/platforms-and-pty.md), and [cleanup guarantees](docs/explanations/process-cleanup-limits.md).
Report vulnerabilities through [SECURITY.md](SECURITY.md). Licensed under [Apache License 2.0](LICENSE).

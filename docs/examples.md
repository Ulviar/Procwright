# Runnable examples

Run these commands from the checkout root with JDK 25. Gradle supplies the dependencies, classpath, and bundled worker;
you do not need to assemble source files to try them. On Windows, replace `./gradlew` with `.\gradlew.bat`.

| Command | What you see | Walkthrough |
| --- | --- | --- |
| `./gradlew -q demoRun` | Current Java version and `succeeded=true` | [Use your own command](getting-started.md) |
| `./gradlew -q demoWorker` | Two text metrics results from one long-lived session | [Worker as a service](how-to/wrap-cli-tool.md) |
| `./gradlew -q demoPool` | The same results from concurrent independent requests | [Add a pool](how-to/reuse-workers.md) |

`demoRun --args='git --version'` runs your executable and argv. `demoWorker` and `demoPool` also accept `--args`, but the
replacement worker must implement the JSON contract in their walkthrough. Each example closes the resources it opens.

## Service example files

- [TextWorkerService.java](examples/integrations/io/github/ulviar/procwright/examples/integration/TextWorkerService.java):
  domain records, the JSON adapter configuration, and the session's application owner.
- [WorkerServiceExample.java](examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerServiceExample.java):
  two calls through one service.
- [WorkerPoolExample.java](examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerPoolExample.java):
  the same protocol configuration used by concurrent callers.
- [JsonLinesTextWorker.java](examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLinesTextWorker.java):
  the bundled demo process; replace its command with your existing CLI in an application.

## Core

The finite-command example is standalone. The other examples below share
[ExampleSupport.java](examples/java/io/github/ulviar/procwright/examples/ExampleSupport.java) and
[ExampleWorker.java](examples/java/io/github/ulviar/procwright/examples/ExampleWorker.java). Protocol examples also use
[LengthLineFrameAdapter.java](examples/java/io/github/ulviar/procwright/examples/LengthLineFrameAdapter.java) and
[DocumentProtocol.java](examples/java/io/github/ulviar/procwright/examples/DocumentProtocol.java).
Those helpers launch the bundled worker on the consumer module's classpath. If you copy a core example, copy its linked
helpers into the same package or replace `ExampleSupport.workerCommand(...)` with a `CommandSpec` for your own CLI.

- [Finite command](examples/java/io/github/ulviar/procwright/examples/RunExample.java)
- [Stop a hung command](examples/java/io/github/ulviar/procwright/examples/StopHungCommandExample.java)
- [Raw interactive streams](examples/java/io/github/ulviar/procwright/examples/InteractiveExample.java)
- [Prompt automation](examples/java/io/github/ulviar/procwright/examples/ExpectExample.java)
- [ANSI-decorated prompt automation](examples/java/io/github/ulviar/procwright/examples/AnsiExpectExample.java)
- [Streaming output](examples/java/io/github/ulviar/procwright/examples/ListenExample.java)
- [Line session](examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java)
- [Readiness probe](examples/java/io/github/ulviar/procwright/examples/ReadinessExample.java)
- [Framed protocol session](examples/java/io/github/ulviar/procwright/examples/ProtocolSessionExample.java)
- [Line worker pool](examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java)
- [Protocol worker pool](examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java)
- [Diagnostics](examples/java/io/github/ulviar/procwright/examples/DiagnosticsExample.java)
- [Required terminal](examples/java/io/github/ulviar/procwright/examples/TerminalExample.java)

## Optional modules

- [Kotlin coroutines, Flow, and protocol factory](examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt)
- [Kotlin pool lifecycle](examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinPoolExample.kt)
- [JSON Lines integration](examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java)
- [Typed Content-Length JSON session](examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java)

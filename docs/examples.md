# Runnable examples

These sources compile and run in external consumer modules. Most launch included workers; the finite-command and Kotlin
quick-start examples invoke the current JDK through its absolute path.

## Core

Most examples share [ExampleSupport.java](examples/java/io/github/ulviar/procwright/examples/ExampleSupport.java) and
[ExampleWorker.java](examples/java/io/github/ulviar/procwright/examples/ExampleWorker.java). Protocol examples also use
[LengthLineFrameAdapter.java](examples/java/io/github/ulviar/procwright/examples/LengthLineFrameAdapter.java) and
[DocumentProtocol.java](examples/java/io/github/ulviar/procwright/examples/DocumentProtocol.java).

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

# Kotlin extensions

After [adding Procwright to your application](../release/installation.md#optional-modules), configure a Kotlin/JVM 25 project:

<!-- procwright-docs: build-configuration -->
```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:procwright-kotlin:0.1.0")
}

kotlin {
    jvmToolchain(25)
}
```

The Kotlin artifact includes core, the Kotlin standard library, and coroutines transitively. Use a compiler that can read
Kotlin 2.4 metadata. You keep the Java scenario API and gain Kotlin durations, suspending calls, Flow, and a protocol
adapter factory DSL.

See the [generated Kotlin API](../api/kotlin/index.html) for exact receivers and overloads. Import extensions from
`io.github.ulviar.procwright.kotlin`; the examples below link to source files with complete imports.

Command arguments and vararg elements must be non-null. Protocol request and response types are also non-null
(`I : Any`, `O : Any`).

## Run a command

Inside a coroutine, execute a command and inspect its result. Here `javaExecutable()` selects the current JDK;
the linked complete example includes this helper and the imports.

<!-- procwright-example: examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt#run -->
```kotlin
val version =
    Procwright.command(javaExecutable())
        .run()
        .withArgs("--version")
        .withTimeout(5.seconds)
        .executeAwait()
if (!version.succeeded()) {
    throw version.toException()
}
```

[Open `KotlinExample.kt`](../examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt) and the
[optional-module example sources](../examples.md#optional-modules).

## Durations

Timeout extensions accept `kotlin.time.Duration` and return the same immutable Java draft types. Retain the returned
draft, as in `.withTimeout(5.seconds)` above. Use `use { ... }` to close opened sessions and pools; see the
[pool lifecycle example](../examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinPoolExample.kt).

## Coroutines

`executeAwait()` and `requestAwait(...)` run blocking core calls on an interruptible I/O dispatcher. Request and pool
acquisition timeouts still apply. Cancellation has these effects:

| Operation | Effect of cancellation |
| --- | --- |
| `executeAwait()` | Interrupt the call and stop its process using the configured shutdown policy. |
| Direct `requestAwait(...)`, waiting behind another request | Abandon only this call; keep the session reusable. |
| Active direct line request | Stop the session once stdin writing has been admitted. |
| Active direct protocol request | Stop the session once it has acquired the request slot, even if no bytes have been written. |
| Pooled request, waiting for a worker | Abandon only the acquisition wait. |
| Active pooled request | Retire its worker. |
| `awaitExit()` | Cancel only the wait; keep the handle and its shared exit state intact. |

`awaitExit()` works with Expect, interactive, line, protocol, and stream handles. Open resource-owning handles explicitly
with `open().use { ... }`; there is no `openAwait()`.

## Flow

`listen().openFlow()` returns a cold Flow: it starts a new process for each collection and closes that process when the
collection is cancelled. Slow collectors apply backpressure; chunks are not silently dropped.

<!-- procwright-example: examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt#flow -->
```kotlin
Procwright.command(javaExecutable())
    .listen()
    .withArgs("--version")
    .withTimeout(5.seconds)
    .openFlow()
    .collect { chunk ->
        when (chunk.source()) {
            StreamSource.STDOUT -> print(chunk.text())
            StreamSource.STDERR -> System.err.print(chunk.text())
        }
    }
```

The Flow emits text chunks, not complete lines. It does **not** report the exit code, timeout status, duration, or exit
transcript. Successful collection therefore does not prove that the command succeeded. When the outcome matters, use
`listen().onOutput(...).open()` and inspect `awaitExit()` or `onExit()`. `openFlow()` replaces any listener previously set
with `onOutput` on that draft.

Launch and process I/O failures fail collection. A cleanup failure fails an otherwise successful collection, but does not
replace an existing failure or cancellation.

## Protocol adapter factory

`protocolAdapterFactory<I, O> { ... }` creates a fresh adapter for each session and pool worker. Define both
`writeRequest` and `readResponse`; a missing handler fails before process startup. This example defines a line-based
adapter and makes two suspending requests through one session:

<!-- procwright-example: examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt#protocol -->
```kotlin
val service = Procwright.command(lineWorkerCommand())
val adapters =
    protocolAdapterFactory<String, String> {
        writeRequest { request, writer ->
            writer.writeLine(request)
            writer.flush()
        }
        readResponse { readers -> readers.stdout().readLine(4096) }
    }
service.protocolSession(adapters).open().use { session ->
    check(session.requestAwait("hello", 5.seconds) == "response:hello")
    check(session.requestAwait("世界", 5.seconds) == "response:世界")
}
```

The [complete example](../examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt) includes
`lineWorkerCommand()` and its worker. Replace that helper with your CLI's `CommandSpec` in an application.

Put mutable per-adapter state inside the configuration block. Concurrent opens can execute that block at the same time;
handlers on separate adapters can also run concurrently. State captured from outside the block remains shared and must
be thread-safe. One session serializes its own adapter's request and response handlers.

## Named Java modules

For a named JPMS application, require the Kotlin module:

<!-- procwright-docs: build-configuration -->
```java
module example.application {
    requires io.github.ulviar.procwright.kotlin;
}
```

It transitively requires core, Kotlin stdlib, and coroutines.

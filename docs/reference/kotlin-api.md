# Kotlin extensions

After [installing Procwright from this checkout](../getting-started.md), configure a Kotlin/JVM 17 project:

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:procwright-kotlin:0.1.0")
}

kotlin {
    jvmToolchain(17)
}
```

The Kotlin artifact brings in Procwright core, Kotlin standard library, and coroutines transitively. It requires a
compiler that can read Kotlin 2.3 metadata. The Java persistent Draft API remains the primary API; this module adds
type-safe durations, coroutine terminals, Flow streaming, and a protocol adapter factory DSL.

Use the [generated Kotlin API](../api/kotlin/index.html) for exact receivers and overloads. This page focuses on the
contracts needed to choose and use those extensions safely.

Core's exported packages use JSpecify `@NullMarked`: Kotlin callers must pass non-null command arguments and vararg
elements, and protocol request and response types are non-null (`I : Any`, `O : Any`).

For a named JPMS application, require only the Kotlin module:

```java
module example.application {
    requires io.github.ulviar.procwright.kotlin;
}
```

The module transitively requires core, Kotlin stdlib, and coroutines.

```kotlin
--8<-- "examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt"
```

[Open `KotlinExample.kt`](../examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt) and the
[optional-module example sources](../examples.md#optional-modules).

## Durations

Scenario Draft timeouts, request calls, `Expect`, pool acquisition, hooks, and `withCloseTimeout` accept
`kotlin.time.Duration`. The extensions return the same Java Draft types. Pool `use` is safe because core `close()` performs
a bounded synchronous drain.

## Coroutines

- `RunScenario.Draft.executeAwait()` executes on an interruptible I/O dispatcher. Cancellation interrupts the core call,
  which applies the Draft's shutdown policy to its process.
- Direct and pooled `requestAwait(...)` variants preserve core request and acquisition timeouts. Cancelling while waiting
  for a direct line or protocol request slot abandons only that call; no request is handed to stdin and the session remains
  reusable. A line request remains retryable until stdin handoff; a protocol request becomes terminal once it acquires the
  serialized slot, even if callback scheduling has not written yet. Cancelling an active pooled request retires its worker,
  while cancellation during worker acquisition abandons only that wait.
- `awaitExit()` waits for interactive, line, protocol, or stream exit. Cancelling the waiter does not close the session or
  cancel its shared exit future.

There is no suspending `openAwait()`. Resource-returning terminals remain explicit `open()` calls so ownership cannot be
lost in a cancellation race.

## Flow

`StreamScenario.Draft.openFlow()` is cold. Calling it does not start a process. Every collection opens and owns a fresh
`StreamSession`; cancellation closes only that collector's session. A rendezvous channel applies backpressure instead of
silently dropping chunks.

The Flow emits only `StreamChunk` values. It does not expose the normal `StreamExit` outcome: exit code, whether timeout or
caller close terminated the process, duration, and diagnostic transcript are discarded after completion. When that outcome
matters, use `listen().onOutput(...).open()` and inspect the session with `awaitExit()` or `onExit()`. `openFlow()` installs
its own output callback and replaces any listener previously set with `onOutput` on that Draft.

## Protocol adapter factory

`protocolAdapterFactory<I, O> { ... }` returns a `Supplier<ProtocolAdapter<I, O>>`. Its configuration block runs for every
factory call, so each session and pool worker receives isolated adapter state. Both `writeRequest` and `readResponse` are
required; omission fails before a process starts.

Concurrent session opens and pool startup may invoke the supplier and configuration block concurrently. They must be
thread-safe and return a fresh adapter each time. Put mutable per-adapter state inside the configuration block or
factory call. One adapter's `writeRequest` and `readResponse` handlers are serialized by its session, but handlers on
different adapters can run concurrently. Mutable state captured from outside remains shared and must be synchronized or
avoided.

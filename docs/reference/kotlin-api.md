# Kotlin API

The `io.github.ulviar:icli-kotlin` artifact is optional. It adds Kotlin ergonomics over the Java core without changing
the underlying scenario model.

Add it next to the core dependency:

```kotlin
dependencies {
    implementation("io.github.ulviar:icli:0.1.0")
    implementation("io.github.ulviar:icli-kotlin:0.1.0")
}
```

Use the package `io.github.ulviar.icli.kotlin` for extensions.

## Receiver-style command calls

`runCommand` keeps the Java `run` scenario but lets the invocation builder be used as a Kotlin receiver.

```kotlin
val result = service.runCommand {
    args("status", "--short")
    timeout(Duration.ofSeconds(2))
}

if (!result.succeeded()) {
    throw result.toException()
}
```

Use `runCommandAwait` from coroutines when the caller should not block its coroutine thread while iCLI supervises the
process on an IO dispatcher.

```kotlin
val result = service.runCommandAwait {
    args("--version")
}
```

## Sessions

`openSession` opens the same raw interactive `Session` as the Java API.

```kotlin
service.openSession {
    args("repl")
}.use { session ->
    session.sendLine("status")
    val exit = session.awaitExit()
    check(exit.exitCode().isPresent)
}
```

`awaitExit` is available for raw `Session` and `StreamSession`. Cancelling the waiting coroutine does not cancel the
shared `onExit()` future or bypass iCLI shutdown policy.

## Line sessions

`requestAwait` runs a line-session request from a coroutine-friendly blocking boundary.

```kotlin
service.lineSession { invocation ->
    invocation.args("line-worker")
}.use { session ->
    val response = session.requestAwait("status", Duration.ofSeconds(1))
    check(response.text().isNotBlank())
}
```

The same line-session invariants still apply: one request at a time, bounded transcript diagnostics, request timeout,
and session close after protocol failure.

## Streaming as Flow

`listenFlow` exposes `listen` as a cold `Flow<StreamChunk>`.

```kotlin
val chunks = service.listenFlow {
    args("logs", "--follow")
    timeout(Duration.ofSeconds(30))
}

chunks.collect { chunk ->
    print(chunk.text())
}
```

The flow adapter intentionally does not expose `onOutput`. Flow owns chunk delivery and uses rendezvous buffering so a
slow collector applies backpressure instead of creating an unbounded queue.

## Public surface

The Kotlin artifact currently publishes:

- top-level extensions in `io.github.ulviar.icli.kotlin.IcliKotlinKt`;
- `ListenFlowInvocation`, the narrow builder facade used by `listenFlow`.

The exact public type set is guarded by `PublicKotlinApiSurfaceTest`, and public Kotlin declarations must have KDoc via
`:icli-kotlin:kotlinApiDocsCheck`.

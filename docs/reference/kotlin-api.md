# Kotlin API

The `io.github.ulviar:procwright-kotlin` artifact is optional. It adds Kotlin ergonomics over the Java core without changing
the underlying scenario model.

Add it next to the core dependency:

```kotlin
dependencies {
    implementation("io.github.ulviar:procwright:0.1.0")
    implementation("io.github.ulviar:procwright-kotlin:0.1.0")
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>procwright-kotlin</artifactId>
    <version>0.1.0</version>
</dependency>
```

Use the package `io.github.ulviar.procwright.kotlin` for extensions. The module depends on
`kotlinx-coroutines-core`; Gradle and Maven receive that dependency transitively. Full optional-module coordinates are
listed in [Installation](../release/installation.md#optional-modules).

The examples below use these imports:

```kotlin
import io.github.ulviar.procwright.Procwright
import io.github.ulviar.procwright.kotlin.*
import java.time.Duration
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
```

## Receiver-style command calls

`runCommand` keeps the Java `run` scenario but lets the invocation builder be used as a Kotlin receiver.

```kotlin
val service = Procwright.command("git")

val result = service.runCommand {
    args("status", "--short")
    timeout(2.seconds)
}

if (!result.succeeded()) {
    throw result.toException()
}
```

Kotlin duration overloads are available for the Kotlin extensions and DSL scopes. The Java API still uses
`java.time.Duration`.

Use `runCommandAwait` from coroutines when the caller should not block its coroutine thread while Procwright supervises the
process on an IO dispatcher.

```kotlin
runBlocking {
    val service = Procwright.command("java")
    val result = service.runCommandAwait {
        args("--version")
    }

    check(result.succeeded())
}
```

## Sessions

`openSession` opens the same raw interactive `Session` as the Java API.

```kotlin
runBlocking {
    val service = Procwright.command("tool")

    service.openSession {
        args("repl")
    }.use { session ->
        session.sendLine("status")
        val exit = session.awaitExit()
        check(exit.exitCode().isPresent)
    }
}
```

`awaitExit` is available for raw `Session` and `StreamSession`. Cancelling the waiting coroutine does not cancel the
shared `onExit()` future or bypass Procwright shutdown policy.

## Line sessions

`requestAwait` runs a line-session request from a coroutine-friendly blocking boundary.

```kotlin
runBlocking {
    val service = Procwright.command("tool")

    service.lineSession().withArgs("line-worker").open().use { session ->
        val response = session.requestAwait("status", Duration.ofSeconds(1))
        check(response.text().isNotBlank())
    }
}
```

The same line-session invariants still apply: one request at a time, bounded transcript diagnostics, request timeout,
and session close after protocol failure.

`requestAwait` is also available for typed `ProtocolSession` workers and for pooled workers (`PooledLineSession` and
`PooledProtocolSession`), each with a `kotlin.time.Duration` overload. Omitting the timeout uses the session or worker
default request timeout.

## Pooled line-session DSL

`pooledLineSession` keeps the pooled line-session scenario but gives Kotlin callers separate worker and pool scopes.

```kotlin
val service = Procwright.command("tool")

service.pooledLineSession {
    worker {
        args("line-worker")
        requestTimeout(1.seconds)
    }

    maxSize(4)
    minIdle(1)
    acquireTimeout(1.seconds)
    maxRequestsPerWorker(1_000)
}.use { pool ->
    val response = pool.request("status")
    check(response.text().isNotBlank())
}
```

The DSL does not expose worker leases. Worker launch settings stay under `worker { ... }`; pool lifecycle settings stay
on the outer scope.

## Protocol adapter DSL

Use `protocolAdapter` when a small typed protocol is clearer as Kotlin handlers than as an anonymous Java class.

```kotlin
val adapter = protocolAdapter<String, String> {
    writeRequest { request, writer ->
        writer.writeLine(request)
        writer.flush()
    }

    readResponse { readers ->
        readers.stdout().readLine(4096)
    }
}

Procwright.command("tool")
    .protocolSession(adapter)
    .withArgs("line-worker")
    .open()
    .use { session ->
        val response = session.request("status")
        check(response.isNotBlank())
    }
```

Both handlers are required. The adapter uses the core `ProtocolAdapter` runtime and keeps the same timeout, transcript,
charset, and protocol-failure guarantees.

## Streaming as Flow

`listenFlow` exposes `listen` as a cold `Flow<StreamChunk>`.

```kotlin
runBlocking {
    val service = Procwright.command("tool")
    val chunks = service.listenFlow {
        args("logs", "--follow")
        timeout(Duration.ofSeconds(30))
    }

    chunks.collect { chunk ->
        print(chunk.text())
    }
}
```

The flow adapter intentionally does not expose `onOutput`. Flow owns chunk delivery and uses rendezvous buffering so a
slow collector applies backpressure instead of creating an unbounded queue.

## Public surface

The Kotlin artifact publishes receiver-style extensions for `CommandService`, suspending helpers for session waits and
for line, protocol, and pooled requests, Kotlin duration overloads, `listenFlow`, pooled line-session DSL scopes, and
`protocolAdapter` DSL scopes.

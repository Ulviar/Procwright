# Procwright

[![CI](https://github.com/Ulviar/Procwright/actions/workflows/ci.yml/badge.svg)](https://github.com/Ulviar/Procwright/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Procwright is a JVM library for running and controlling external command-line processes. Choose the workflow first;
Procwright then applies the timeout, output, lifecycle, and cleanup rules for that workflow.

No public release exists yet. The planned first version is `0.1.0`. Artifacts target Java 17 and run on Java 17 or
newer.

## Install from this checkout

```shell
./gradlew publishToMavenLocal \
  --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.version=0.1.0 \
  --no-daemon
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:procwright:0.1.0")
}
```

## Run a command

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import java.nio.file.Path;
import java.time.Duration;

public final class RunExample {

    private RunExample() {}

    public static void main(String[] args) {
        CommandResult result = Procwright.command(javaExecutable())
                .run()
                .withArgs("--version")
                .withCapture(CapturePolicy.bounded(256 * 1024))
                .withTimeout(Duration.ofSeconds(5))
                .execute();

        System.out.print(result.stdout());
        System.err.print(result.stderr());
        System.err.printf(
                "exit=%s, timedOut=%s, stdoutTruncated=%s, stderrTruncated=%s%n",
                result.exitCode().isPresent()
                        ? Integer.toString(result.exitCode().getAsInt())
                        : "unavailable",
                result.timedOut(),
                result.stdoutTruncated(),
                result.stderrTruncated());

        if (!result.succeeded()) {
            throw result.toException();
        }
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
```

[Open `RunExample.java`](docs/examples/java/io/github/ulviar/procwright/examples/RunExample.java).

The explicit capture policy retains at most 256 KiB (262,144 bytes) from each of stdout and stderr in memory.
Procwright continues draining both streams and sets `stdoutTruncated()` or `stderrTruncated()` if it discards later
bytes. See the [run reference](docs/scenarios/run.md) for result behavior and alternative output policies.

Each `with*` call returns a new immutable Draft. Only `execute()` or `open()` starts a process, so a configured Draft can
be reused or branched safely.

## Choose a workflow

| Process behavior | API |
| --- | --- |
| Exits after one command | `run()` |
| Needs direct stdin/stdout control | `interactive()` |
| Prompts for input | `interactive()` then `session.expect().open()` |
| One long-lived worker with line request/response | `lineSession()` |
| One long-lived worker with framed, binary, or typed messages | `protocolSession(adapterFactory)` |
| Emits a continuous output stream | `listen()` |
| Concurrent independent requests across resettable workers | add `pooled()` to a line or protocol Draft |

Direct line and protocol sessions are already long-lived and serialize requests on one worker. Use them when one worker,
stable affinity, or worker-local state matters. Pool only when requests are independent, workers can be safely reset, and
concurrent callers may use different workers.

Open sessions and pools with try-with-resources. Pool `close()` waits for bounded worker drain, using a 15-second default;
configure it with `withCloseTimeout(...)`. Use `closeAsync()` only when the caller must start terminal cleanup without
blocking. Do not read a session's raw stdout after an `Expect`, line-session, or protocol helper owns that output.

## Documentation

- [Documentation](docs/index.md)
- [Getting started](docs/getting-started.md)
- [Choose a process scenario](docs/how-to/choose-process-scenario.md)
- [Runnable examples](docs/examples.md)
- [Scenario and policy reference](docs/reference/index.md)
- [Kotlin extensions](docs/reference/kotlin-api.md)
- [Compatibility and limitations](docs/release/compatibility.md)

## Modules

- `io.github.ulviar:procwright` provides the Java core and has no runtime dependency outside the JDK.
- `io.github.ulviar:procwright-kotlin` adds Kotlin durations, coroutine terminals, Flow streaming, and an adapter factory DSL.
- `io.github.ulviar:procwright-integrations` adds JSON and byte-framing protocol adapters.

Report vulnerabilities through [SECURITY.md](SECURITY.md). Procwright is licensed under
[Apache License 2.0](LICENSE).

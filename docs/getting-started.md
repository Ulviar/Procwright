# Getting started

Procwright requires Java 17 or newer. Until the planned `0.1.0` release is published, install it from this checkout:

```shell
./gradlew publishToMavenLocal \
  --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.version=0.1.0 \
  --no-daemon
```

Add Maven Local and the core dependency:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:procwright:0.1.0")
}
```

## Run one command

The canonical example below is compiled and executed as an external consumer.

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

[Open `RunExample.java`](examples/java/io/github/ulviar/procwright/examples/RunExample.java).

The example explicitly retains up to 256 KiB from each output stream. The [run scenario](scenarios/run.md) explains
what happens when either stream exceeds that byte limit.

`run()` returns an immutable Draft. Configuration does not start the process. `execute()` starts it and returns a
`CommandResult` containing the exit status, bounded output, truncation flags, timeout status, and elapsed time.
Inspect captured output and status before converting an unsuccessful result with `toException()`; the exception keeps the
complete `CommandResult`.

## Choose the next scenario

- Use [`interactive()`](scenarios/interactive.md) for direct stdin/stdout control.
- Add [`session.expect().open()`](scenarios/expect.md) for prompts.
- Use [`lineSession()`](scenarios/line-session.md) for one-line request/response workers.
- Use [`protocolSession(adapterFactory)`](scenarios/protocol-session.md) for custom framing or typed messages.
- Use [`listen()`](scenarios/streaming.md) for continuous output.
- Add [`pooled()`](scenarios/pooling.md) only when a worker can safely serve multiple requests.

Sessions and pools own processes and can use try-with-resources in Java or `use` in Kotlin. Pool close is synchronous but
bounded; configure its budget with `withCloseTimeout(...)`. See the [pooling scenario](scenarios/pooling.md) for copyable
Java examples.

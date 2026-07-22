# Choose a process scenario

Choose by the process's I/O contract, not by a list of low-level flags.

| Process contract | Scenario |
| --- | --- |
| Starts, produces bounded output, and exits | `run()` |
| Needs direct stdin/stdout access | `interactive()` |
| Prints prompts that must be matched | `interactive()` and `session.expect().open()` |
| Accepts one line and returns one line | `lineSession()` |
| Uses custom framing, bytes, multi-line messages, or typed values | `protocolSession(adapterFactory)` |
| Produces output continuously | `listen()` |
| Is expensive to start and safely reusable | add `pooled()` to a line or factory-backed protocol Draft |

## Finite command

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

Use this when completion and a typed result matter. [Run guide](run-finite-command.md).

## Prompt-driven installer or configurator

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ExpectExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.Session;
import java.time.Duration;

public final class ExpectExample {

    private ExpectExample() {}

    public static void main(String[] args) {
        try (Session session = Procwright.command(ExampleSupport.workerCommand("expect"))
                        .interactive()
                        .withIdleTimeout(Duration.ofSeconds(10))
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(5)).open()) {
            expect.expectText("ready> ");
            expect.sendLine("café");
            expect.expectText("ok:café");
        }
    }
}
```

`Expect` owns output while it matches prompts. [Prompt guide](automate-prompts.md).

## Line request/response worker

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import java.time.Duration;

public final class LineSessionExample {

    private LineSessionExample() {}

    public static void main(String[] args) {
        try (LineSession session = Procwright.command(ExampleSupport.workerCommand("line"))
                .lineSession()
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            LineResponse response = session.request("Zażółć gęślą jaźń");
            if (!response.text().equals("response:Zażółć gęślą jaźń")) {
                throw new IllegalStateException("Unexpected line response");
            }
        }
    }
}
```

Use this only when embedded newlines are impossible. [Line-worker guide](talk-to-line-worker.md).

## Framed or typed protocol worker

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ProtocolSessionExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentRequest;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentResponse;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ProtocolSessionExample {

    private ProtocolSessionExample() {}

    public static void main(String[] args) {
        try (ProtocolSession<DocumentRequest, DocumentResponse> session = Procwright.command(
                        ExampleSupport.workerCommand("protocol"))
                .protocolSession(LengthLineFrameAdapter::new)
                .withRequestTimeout(Duration.ofSeconds(5))
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .withMaxRequestBytes(16_384)
                .withMaxRequestChars(8192)
                .withMaxResponseBytes(16_384)
                .withMaxResponseChars(8192)
                .withOutputBacklogLimit(16_384)
                .open()) {
            DocumentResponse response = session.request(new DocumentRequest("first line\nПривет, 世界"));
            if (!response.text().equals("first line\nПривет, 世界")) {
                throw new IllegalStateException("Unexpected protocol response");
            }
        }
    }
}
```

The adapter in this example writes a byte length and reads a multi-line LF-framed response through `ProtocolReader`.
Its `readResponse` method is the response decoder. Pass an adapter factory, not one adapter instance, so each opened
session or pool worker has isolated protocol state. [Protocol contract](../scenarios/protocol-session.md).

## Continuous output

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ListenExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamSession;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ListenExample {

    private ListenExample() {}

    public static void main(String[] args) {
        AtomicInteger chunks = new AtomicInteger();
        try (StreamSession stream = Procwright.command(ExampleSupport.workerCommand("listen"))
                .listen()
                .withTimeout(Duration.ofSeconds(2))
                .onOutput(chunk -> chunks.incrementAndGet())
                .open()) {
            StreamExit exit = stream.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            if (!exit.timedOut() || chunks.get() == 0) {
                throw new IllegalStateException("Expected bounded log streaming");
            }
        }
    }
}
```

Use this when retaining all output would be wasteful or unbounded. [Streaming guide](follow-logs.md).

## Reusable worker

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;
import java.time.Duration;

public final class LinePoolExample {

    private LinePoolExample() {}

    public static void main(String[] args) {
        try (PooledLineSession pool = Procwright.command(ExampleSupport.workerCommand("line"))
                .lineSession()
                .withRequestTimeout(Duration.ofSeconds(5))
                .withMaxRequestBytes(16 * 1024)
                .withMaxRequestChars(8 * 1024)
                .withMaxLineChars(8 * 1024)
                .withMaxResponseLines(1)
                .withMaxResponseChars(8 * 1024)
                .withStdoutBacklogLines(128)
                .withStdoutBacklogChars(64 * 1024)
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withAcquireTimeout(Duration.ofSeconds(2))
                .withHookTimeout(Duration.ofSeconds(1))
                .withCloseTimeout(Duration.ofSeconds(15))
                .withMaxRequestsPerWorker(100)
                .open()) {
            LineResponse response = pool.request("Привет", Duration.ofSeconds(5));
            if (!response.text().equals("response:Привет")) {
                throw new IllegalStateException("Unexpected pooled response");
            }
        }
    }
}
```

Pooling is available for line and factory-backed protocol sessions. It is not available for raw interactive sessions.
[Pooling guide](reuse-workers.md).

All complete sources and their shared worker are listed in [Runnable examples](../examples.md).

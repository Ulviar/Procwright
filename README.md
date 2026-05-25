# iCLI

iCLI is a JVM library for scenario-first control of external command-line processes. Its APIs make bounded output,
timeouts, stream ownership, diagnostics, and best-effort process cleanup explicit for each workflow.

The first public release is `0.1.0`. Use Java 17 or newer; published artifacts target Java 17.

## Why iCLI exists

Most process APIs expose low-level pieces: argv, environment, streams, timeouts, and process handles. Real CLI
automation usually needs a workflow instead:

- run a finite command and inspect a typed result;
- automate a prompt-oriented session;
- exchange requests with a line-oriented worker;
- exchange framed, multi-line, byte, or typed requests with a protocol worker;
- follow streaming output without retaining everything in memory;
- reuse warm workers when the protocol can be reset and checked;
- wrap an external CLI as a structured integration boundary.

iCLI keeps those workflows explicit. The user chooses a scenario, and the library owns the scenario invariants:
bounded output, timeout and shutdown handling, stream draining, process-tree cleanup, output ownership, diagnostics, and
redaction-friendly observation.

## Installation

Published releases use Maven Central coordinates.

Gradle Kotlin DSL:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:icli:0.1.0")
}
```

Gradle Groovy:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.ulviar:icli:0.1.0'
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>icli</artifactId>
    <version>0.1.0</version>
</dependency>
```

To work from source:

```bash
git clone https://github.com/Ulviar/iCLI.git
cd iCLI
./gradlew quickCheck
```

Source builds can also be checked with Java 21 or 25; dependency consumers do not need separate coordinates for those
runtimes.

The smallest workflow is a one-shot command:

```java
import io.github.ulviar.icli.CommandService;
import io.github.ulviar.icli.Icli;
import io.github.ulviar.icli.command.CommandResult;

public final class GettingStartedExample {

    private GettingStartedExample() {}

    public static void main(String[] args) {
        CommandService java = Icli.command("java");

        CommandResult result = java.run().execute("--version");

        if (!result.succeeded()) {
            throw result.toException();
        }

        System.out.print(result.stdout());
        System.err.print(result.stderr());
    }
}
```

## Choose a Scenario

| Need | Use |
| --- | --- |
| Run a finite command and get a result. | `run` |
| Configure reusable command defaults. | `CommandSpec` + `CommandService` |
| Control a live process directly. | `interactive` |
| Automate prompts. | `interactive` + `Expect` |
| Talk to a line request/response worker. | `lineSession` |
| Talk to a framed or typed request/response worker. | `protocolSession` |
| Follow logs or streaming output. | `listen` |
| Reuse expensive workers. | `lineSession().pooled()` / `protocolSession(factory).pooled()` |
| Require terminal capability. | session API + `TerminalPolicy.REQUIRED` |
| Wrap a CLI as a structured adapter. | `io.github.ulviar:icli-integrations` |

Start with the public docs:

- [Getting Started](docs/getting-started.md)
- [How-to Guides](docs/how-to/index.md)
- [Examples](docs/examples.md)
- [Reference](docs/reference/index.md)
- [Release](docs/release/index.md)

## Modules

- `io.github.ulviar:icli` is the Java core module `io.github.ulviar.icli` with no runtime dependencies outside the JDK.
- `io.github.ulviar:icli-kotlin` is an optional Kotlin ergonomics module.
- `io.github.ulviar:icli-integrations` is an optional Java module for structured CLI-backed integration helpers.

## Verification

For source evaluation, run the fast check:

```bash
./gradlew quickCheck
```

The broader verification matrix is documented in [Compatibility](docs/release/compatibility.md).

## License

iCLI is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

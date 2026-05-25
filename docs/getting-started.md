# Getting Started

## Requirements

- JDK 17 or newer. Published artifacts target Java 17.

## Add the dependency

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

## First scenario

The smallest iCLI workflow is a one-shot command scenario: choose the command once, run a call, and inspect a typed
result.

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

Complete example locations are listed in [Examples](examples.md).

## Mental model

iCLI has three layers that matter to a new user:

- `CommandSpec` describes the executable and stable defaults such as working directory or environment.
- `CommandService` is the reusable handle around that command.
- Scenario methods such as `run`, `lineSession`, `protocolSession`, and `listen` choose the workflow and expose only the
  configuration that is meaningful for that workflow.

This is why most code starts with `Icli.command(...)`: after that, choose the scenario by method name instead of building
a manual process harness.

## Choose a scenario

Do not start by assembling process flags. Start by choosing the workflow:

- `run` for finite commands;
- `interactive` for raw live process control;
- `interactive` + `Expect` for prompt automation;
- `lineSession` for request/response protocols;
- `protocolSession` for framed, multi-line, byte, or typed request/response protocols;
- `listen` for streaming output;
- `lineSession().pooled()` and `protocolSession(factory).pooled()` for reusable workers;
- `io.github.ulviar:icli-integrations` for structured CLI adapters.

The [How-to Guides](how-to/index.md) section starts from common tasks. [Scenario Contracts](scenarios/index.md) is the
reference index for the public scenario surface.

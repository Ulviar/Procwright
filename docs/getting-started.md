# Getting Started

## Requirements

- JDK 17, 21, or 25 for the matching release variant. The default local target is 25.
- GitHub Packages credentials when consuming the published artifact.
- Gradle wrapper from this repository only when building iCLI from source.

## Add the dependency

Published artifacts are available from GitHub Packages:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Ulviar/iCLI")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
            password = providers.gradleProperty("gpr.key").orNull
        }
    }
}

dependencies {
    implementation("com.github.ulviar:icli:0.1.0")
}
```

## Build from source

Clone the repository and run the fast verification tier:

```bash
git clone https://github.com/Ulviar/iCLI.git
cd iCLI
./gradlew quickCheck
```

Use broader verification tiers only when changing iCLI itself. They are listed in
[Compatibility](release/compatibility.md) and [Installation](release/installation.md).

## First scenario

The smallest iCLI workflow is a one-shot command scenario: choose the command once, run a call, and inspect a typed
result.

```java
CommandService git = Icli.command("git");

CommandResult result = git.run().execute("status", "--short");

if (!result.succeeded()) {
    throw result.toException();
}
```

The compile-tested source for this shape is `CommandServiceApiExamples.oneShotScenario`.

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
- `Expect` for prompt automation;
- `lineSession` for request/response protocols;
- `protocolSession` for framed, multi-line, byte, or typed request/response protocols;
- `listen` for streaming output;
- `lineSession().pooled()` and `protocolSession(factory).pooled()` for reusable workers;
- `:icli-integrations` for structured CLI adapters.

The [How-to Guides](how-to/index.md) section starts from common tasks. [Scenario Contracts](scenarios/index.md) is the
reference index for the public scenario surface.

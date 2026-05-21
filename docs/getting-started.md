# Getting Started

!!! warning "Unreleased baseline"
    iCLI is not published as a stable release yet. Treat coordinates, package names, and API names as pre-release
    until the first release candidate is cut.

## Requirements

- JDK 17, 21, or 25 for the matching release variant. The default local target is 25.
- Gradle wrapper from this repository for local builds.
- No stable Maven artifact is published yet.

## Evaluate from source

Clone the repository and run the fast verification tier:

```bash
git clone https://github.com/Ulviar/iCLI.git
cd iCLI
./gradlew quickCheck
```

Use broader verification tiers only when changing iCLI itself. They are listed in
[Compatibility](release/compatibility.md) and [Installation](release/installation.md).

## After a release candidate is published

The first public artifact target is GitHub Packages with Java 17 bytecode. Until an RC exists, treat dependency
coordinates as planned shape, not an available artifact. See [Installation](release/installation.md) for repository and
authentication details.

## First scenario

The smallest iCLI workflow is a one-shot command scenario: choose the command once, run a call, and inspect a typed
result.

```java
CommandService git = CommandService.forCommand("git");

CommandResult result = git.run(call -> call.args("status", "--short"));

if (!result.succeeded()) {
    throw result.toException();
}
```

The compile-tested source for this shape is `CommandServiceApiExamples.oneShotScenario`.

Complete example locations are listed in [Examples](examples.md).

## Choose a scenario

Do not start by assembling process flags. Start by choosing the workflow:

- `run` for finite commands;
- `interactive` for raw live process control;
- `Expect` for prompt automation;
- `lineSession` for request/response protocols;
- `protocolSession` for framed, multi-line, byte, or typed request/response protocols;
- `listen` for streaming output;
- `pooled` and `pooledProtocol` for reusable workers;
- `:icli-integrations` for structured CLI adapters.

The [How-to Guides](how-to/index.md) section starts from common tasks. [Scenario Contracts](scenarios/index.md) is the
reference index for the public scenario surface.

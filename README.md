# iCLI

iCLI is a JVM library for safe, scenario-first control of external command-line processes.

The project is not published as a stable release yet. The current version is `0.0.0-SNAPSHOT`, the default build target
is Java 25, Java 17/21/25 release variants are checked from the same source tree, and public API names may still change
before the first release candidate.

## Why iCLI exists

Most process APIs expose low-level pieces: argv, environment, streams, timeouts, and process handles. Real CLI
automation usually needs a workflow instead:

- run a finite command and inspect a typed result;
- automate a prompt-oriented session;
- exchange requests with a line-oriented worker;
- follow streaming output without retaining everything in memory;
- reuse warm workers safely;
- wrap an external CLI as a structured integration boundary.

iCLI keeps those workflows explicit. The user chooses a scenario, and the library owns the scenario invariants:
bounded output, timeout and shutdown handling, stream draining, process-tree cleanup, output ownership, diagnostics, and
redaction-friendly observation.

## Quick Start From Source

There is no stable Maven artifact yet. For now, evaluate iCLI from source:

```bash
git clone https://github.com/Ulviar/iCLI.git
cd iCLI
./gradlew quickCheck
./gradlew publicDocsCheck
```

To evaluate a specific Java release variant, run Gradle with the matching JDK and `-Picli.javaRelease=17`, `21`, or
`25`.

The smallest workflow is a one-shot command:

```java
CommandService git = CommandService.forCommand("git");

CommandResult result = git.run(call -> call.args("status", "--short"));

if (!result.succeeded()) {
    throw result.toException();
}
```

The example above is compile-tested as `CommandServiceApiExamples.oneShotScenario`.

## Choose a Scenario

| Need | Use |
| --- | --- |
| Run a finite command and get a result. | `run` |
| Configure reusable command defaults. | `CommandSpec` + `CommandService` |
| Control a live process directly. | `interactive` |
| Automate prompts. | `interactive` + `Expect` |
| Talk to a line request/response worker. | `lineSession` |
| Follow logs or streaming output. | `listen` |
| Reuse expensive line workers. | `pooled` |
| Require terminal capability. | session API + `TerminalPolicy.REQUIRED` |
| Wrap a CLI as a structured adapter. | `:icli-integrations` |

Start with the public docs:

- [Getting Started](docs/getting-started.md)
- [Scenarios](docs/scenarios/index.md)
- [How-to Guides](docs/how-to/index.md)
- [Examples](docs/examples.md)
- [Reference](docs/reference/index.md)
- [Release status](docs/release/index.md)

## Modules

- `:` is the Java core module `com.github.ulviar.icli` with no runtime dependencies outside the JDK.
- `:icli-kotlin` is an optional Kotlin ergonomics module.
- `:icli-integrations` is an optional Java module for structured CLI-backed integration helpers.
- `:icli-comparison` is a research module for library comparison and benchmarks. It is not a runtime dependency of the
  public artifacts.

## Verification

Common checks:

```bash
./gradlew quickCheck
./gradlew scenarioCheck
./gradlew regressionCheck
./gradlew check
./gradlew publicDocsCheck
```

Release-candidate validation is stricter and intentionally requires a clean worktree:

```bash
./gradlew releaseCandidateCheck
```

Contributor context, ADRs, audits, and release policies live under [context/](context/). They are project memory, not a
replacement for the public documentation under [docs/](docs/).

## License

iCLI is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

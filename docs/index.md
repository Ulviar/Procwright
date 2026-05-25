# iCLI

iCLI is a JVM library for safe, scenario-first control of external command-line processes.

The current public version is `0.1.0`. Published artifacts target Java 17; Java 17, 21, and 25 source variants are
checked in CI.

If you are new to iCLI, start with one question: what shape does the external process have? A command that exits belongs
to `run`. A long-lived worker with one line per response belongs to `lineSession`. A framed or typed protocol belongs to
`protocolSession`. A process that emits output continuously belongs to `listen`.

## What iCLI is for

iCLI is intended for applications that need to call external CLIs without owning a custom process harness:

- run one command and receive a typed result;
- interact with a long-running process through stdin/stdout;
- automate prompt-oriented dialogues;
- use line-oriented request/response workers;
- use framed, multi-line, byte, or typed protocol workers;
- consume streaming output without retaining all data in memory;
- reuse warm line-session or typed protocol workers;
- wrap a CLI as a structured integration boundary.

## Documentation model

The public documentation follows a Diataxis-style split: getting started for first learning, how-to guides for concrete
tasks, reference for contracts and generated API docs, and explanation for rationale. Scenario contracts point back to
compile-tested examples in the repository. Low-level runtime details are documented only when they are part of the public
contract.

Internal project context, ADRs, audits, and planning documents live under `context/` and are not a substitute for this
public documentation.

## First useful call

```java
CommandService git = Icli.command("git");

CommandResult result = git.run().execute("status", "--short");

if (!result.succeeded()) {
    throw result.toException();
}
```

This is the smallest scenario: choose a command, choose `run`, execute with arguments, then inspect a typed result.

## Main entry points

- [Getting Started](getting-started.md)
- [Examples](examples.md)
- [How-to Guides](how-to/index.md)
- [Reference](reference/index.md)
- [Kotlin API](reference/kotlin-api.md)
- [Explanation](explanations/scenario-first.md)
- [Non-goals](explanations/non-goals.md)
- [Release](release/index.md)

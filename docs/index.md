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

- [Getting Started](getting-started.md) for installation and the first command.
- [How-to Guides](how-to/index.md) for common CLI automation tasks.
- [Reference](reference/index.md) for scenario contracts, policies, results, errors, and generated API docs.
- [Examples](examples.md) for compile-tested source locations.
- [Kotlin API](reference/kotlin-api.md) for the optional Kotlin module.
- [Non-goals](explanations/non-goals.md) for scope boundaries.
- [Release](release/index.md) for compatibility and limitations.

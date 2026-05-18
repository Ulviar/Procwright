# iCLI

iCLI is a JVM library for safe, scenario-first control of external command-line processes.

The project is currently a `0.0.0-SNAPSHOT` rewrite. The documentation describes behavior that is implemented and
covered by tests in the current branch, but public API names may still change before the first release.

## What iCLI is for

iCLI is intended for applications that need to call external CLIs without owning a custom process harness:

- run one command and receive a typed result;
- interact with a long-running process through stdin/stdout;
- automate prompt-oriented dialogues;
- use line-oriented request/response workers;
- consume streaming output without retaining all data in memory;
- reuse warm line-session workers;
- wrap a CLI as a structured integration boundary.

## Documentation model

The public documentation is organized by user scenarios. Each scenario page points back to compile-tested examples in
the repository. Low-level runtime details are documented only when they are part of the public contract.

Internal project context, ADRs, audits, and planning documents live under `context/` and are not a substitute for this
public documentation.

## Main entry points

- [Getting Started](getting-started.md)
- [Examples](examples.md)
- [Scenarios](scenarios/index.md)
- [How-to Guides](how-to/index.md)
- [Reference](reference/index.md)
- [API](api/index.md)
- [Release](release/index.md)

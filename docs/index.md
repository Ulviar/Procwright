# iCLI

iCLI is a JVM library for calling external command-line processes through task-shaped APIs. Choose the workflow first:
run a finite command, automate prompts, talk to a worker, follow output, or reuse warm workers.

The current public version is `0.1.0`. Use Java 17 or newer; published artifacts target Java 17.

If you are new to iCLI, start with one question: what shape does the external process have? A command that exits belongs
to `run`. A raw live process belongs to `interactive`. Prompt automation uses `interactive` + `Expect`. A long-lived
worker with one line per response belongs to `lineSession`. A framed or typed protocol belongs to `protocolSession`. A
process that emits output continuously belongs to `listen`.

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

This is the smallest scenario: choose a command, choose `run`, execute with arguments, then inspect a typed result.

## Main entry points

- [Getting Started](getting-started.md) for installation and the first command.
- [How-to Guides](how-to/index.md) for common CLI automation tasks.
- [Reference](reference/index.md) for scenario contracts, policies, results, errors, and generated API docs.
- [Examples](examples.md) for selected scenario snippets.
- [Kotlin API](reference/kotlin-api.md) for the optional Kotlin module.
- [Explanations](explanations/index.md) for design rationale and scope boundaries.
- [Version and Compatibility](release/index.md) for installation, compatibility, and limitations.

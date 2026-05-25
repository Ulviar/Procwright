# Run a Finite Command

Use `run` for tools such as linters, validators, code generators, `git status`, and package manager commands that
should finish and return a result.

## Steps

1. Create a `CommandService` for the executable.
2. Select `run` and add per-call arguments.
3. Inspect `CommandResult`.
4. Convert unsuccessful results with `toException()` only when fail-fast flow is useful.

```java
CommandService git = Icli.command("git");

CommandResult result = git.run().execute("status", "--short");

if (!result.succeeded()) {
    throw result.toException();
}
```

Complete example source: [`CommandServiceApiExamples.oneShotScenario`](https://github.com/Ulviar/iCLI/blob/main/src/test/java/io/github/ulviar/icli/examples/CommandServiceApiExamples.java).

## Use this scenario because

`run` owns process completion, bounded capture, timeout supervision, stderr draining, and typed results. A streaming or
interactive scenario would make the caller own more lifecycle state than this task needs.

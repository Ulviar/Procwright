# Migrate from ProcessBuilder

Use this guide when existing code launches external commands with `ProcessBuilder`, `Runtime.exec`, or a small local
process utility. The migration should start from the workflow the caller needs, not from a method-by-method API mapping.

## Choose the scenario first

| Existing shape | iCLI scenario | Why |
| --- | --- | --- |
| Start a command, wait for completion, inspect exit code and output. | [`run`](../scenarios/run.md) | Owns completion, bounded capture, timeout, stderr draining, and typed results. |
| Start a command and keep reading output while it is alive. | [`listen`](../scenarios/streaming.md) | Owns stream draining without retaining all output. |
| Keep a process open and write/read raw streams. | [`interactive`](../scenarios/interactive.md) | Exposes direct session control and process lifecycle. |
| Wait for prompts and send replies. | [`interactive`](../scenarios/interactive.md) + [`Expect`](../scenarios/expect.md) | Owns prompt matching, transcript bounds, and timeout/EOF distinction. |
| Send one line request and read one logical response. | [`lineSession`](../scenarios/line-session.md) | Owns serialized request/response state and closes on protocol uncertainty. |
| Reuse expensive line workers. | [`pooled`](../scenarios/pooling.md) | Reuses line-session workers with bounded pool and retirement policies. |
| Wrap a CLI as a structured adapter. | [`integrations`](../scenarios/integrations.md) | Keeps structured adapter behavior outside the core process runtime. |

## One-shot commands

Replace ad hoc `ProcessBuilder` plus stream pump code with `run` when the process should finish and return an outcome.

```java
CommandService git = CommandService.forCommand("git");

CommandResult result = git.run(call -> call.args("status", "--short"));

if (!result.succeeded()) {
    throw result.toException();
}
```

Compile-tested source: `CommandServiceApiExamples.oneShotScenario`.

Do not throw away the result too early. `CommandResult` carries exit code, timeout state, stdout/stderr text and bytes,
truncation flags, and elapsed duration. Convert to an exception only when fail-fast application flow is the right shape.

## Command defaults

Move stable command-level defaults into `CommandSpec`. Keep operation-specific arguments and policies in the scenario
callback.

```java
CommandSpec command = CommandSpec.builder("python")
        .workingDirectory(projectDir)
        .putEnvironment("PYTHONUTF8", "1")
        .build();

CommandService python = new CommandService(command, RunOptions.defaults());

python.run(call -> call.args("--version"));
```

Compile-tested source: `CommandServiceApiExamples.explicitCommandConfiguration`.

This preserves the same split that mature hand-written runners usually grow over time: one reusable command profile,
many scenario invocations.

## Streaming output

If the old code starts background threads to drain stdout and stderr, check whether the caller actually needs a final
captured result. If not, prefer `listen`.

```java
try (StreamSession stream =
        tool.listen(call -> call.args("logs", "--follow").onOutput(chunk -> {
            if (chunk.source() == StreamSource.STDERR) {
                System.err.print(chunk.text());
            }
        }))) {
    stream.onExit().join();
}
```

Compile-tested source: `CommandServiceApiExamples.listenOnlyStreamingScenario`.

Use `run` when the output is part of the completed result. Use `listen` when the output is an event stream and retaining
all of it would be the wrong invariant.

## Prompt automation

If the old code loops over process output waiting for prompt text, use `Expect` over an interactive session.

```java
try (Session session = repl.interactive(call -> call.args("repl"));
        Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(2)))) {
    expect.expectText("ready> ");
    expect.sendLine("status");
    expect.expectRegex(java.util.regex.Pattern.compile("ok|ready"));
}
```

Compile-tested source: `CommandServiceApiExamples.expectScenario`.

`Expect` owns output matching and transcript bounds. Do not read the raw session output streams at the same time.

## Line workers

If the old code writes a command line and then waits for one response line, use `lineSession`.

```java
try (LineSession session = repl.lineSession(call -> call.args("repl"))) {
    LineResponse response = session.request("status");
    if (response.text().isBlank()) {
        throw new IllegalStateException("empty response");
    }
}
```

Compile-tested source: `CommandServiceApiExamples.lineSessionScenario`.

On timeout, EOF, decoder failure, or read/write failure, the line session closes. This is intentional: after a failed
request/response cycle, the protocol state is unknown.

## What not to migrate literally

- Do not translate every `ProcessBuilder` setter into a long iCLI callback. Choose the scenario and then set only the
  policies that matter to that scenario.
- Do not keep manual stream pump threads next to `run`, `listen`, `Expect`, or `lineSession`; that would create two
  output owners.
- Do not use shell command strings just because the old code did. Direct argv is the default; shell is an explicit
  boundary.
- Do not hide timeout and cleanup in a generic helper. In iCLI these are scenario policies, so the call site can see the
  lifecycle contract.

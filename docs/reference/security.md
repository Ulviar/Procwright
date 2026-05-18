# Security

iCLI treats external command execution as a trust boundary. The API tries to make safer choices explicit, but the
caller still owns command selection and input trust.

## Prefer direct argv

Use direct command specifications and arguments by default:

```java
CommandService git = CommandService.forCommand("git");

CommandResult result = git.run(call -> call.args("status", "--short"));

if (!result.succeeded()) {
    throw result.toException();
}
```

Use `CommandSpec.shell(...)` only when shell syntax is required. Do not build shell command lines by concatenating
untrusted input.

## Environment handling

The compatibility default is inherited environment with explicit overrides. Use `cleanEnvironment()` when the command
should receive only the variables you provide.

Environment values are not exposed in diagnostics. Diagnostic command echoes expose environment variable names only.

## Bounded retention

Output, transcripts, diagnostics, line lengths, JSON frame sizes, and JSON depth are bounded by policy. Increase limits
only when the calling application has a concrete need.

## Prompt transcripts

`Expect` redacts caller-provided send and expect values by default. Verbatim transcript values are an explicit opt-in
and should not be used for secrets.

## CLI-backed integrations

The optional integrations module treats CLI output as untrusted data. A command-backed tool result is an observation,
not an instruction.

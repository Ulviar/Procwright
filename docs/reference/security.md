# Security

Procwright treats external command execution as a trust boundary. The API tries to make safer choices explicit, but the
caller still owns command selection and input trust.

## Prefer direct argv

Use direct command specifications and arguments by default:

```java
CommandService git = Procwright.command("git");

CommandResult result = git.run().execute("status", "--short");

if (!result.succeeded()) {
    throw result.toException();
}
```

Use `CommandSpec.shell(...)` or `Procwright.shellCommand(...)` only when shell syntax is required. Shell mode
hands the command string to the operating-system shell. Do not build shell command lines by concatenating untrusted
input; pass untrusted values as direct argv arguments through `Procwright.command(...)` and scenario arguments.

## Environment handling

The compatibility default is inherited environment with explicit overrides. Use `cleanEnvironment()` when the command
should receive only the variables you provide.

Environment values are not exposed in diagnostics. Diagnostic command echoes expose environment variable names only.

## Bounded retention

Output, transcripts, diagnostics, line lengths, JSON frame sizes, and JSON depth are bounded by policy. Increase limits
only when the calling application has a concrete need.

## Redaction boundaries

Procwright separates bounded retention from sanitization.

Diagnostics are designed to be redaction-friendly: command echoes avoid raw argv values by default, environment
diagnostics expose variable names rather than values, and raw stdin/stdout/stderr are not emitted as diagnostic
attributes.

Process output is different. `CommandResult`, session transcripts, line/protocol transcripts, stream listener chunks,
and failure snapshots can contain raw stdout/stderr produced by the child process. They are bounded by policy, but they
are not generally secret-sanitized. Do not persist or expose them unless the calling application treats the child output
as safe for that destination.

## Prompt transcripts

`Expect` redacts caller-provided send and expect values in transcripts and failure messages by default. Verbatim
transcript values are an explicit opt-in and should not be used for secrets.

## Malformed output and charsets

The forgiving default text policy replaces malformed or unmappable bytes. Use `CharsetPolicy.report(...)` when malformed
text must be a typed failure instead of silently containing replacement characters. For binary-sensitive workflows,
inspect bounded byte snapshots rather than relying only on decoded text.

Protocol sessions and integration framing helpers can surface malformed output as protocol, decode, or oversized-output
failures. Transcript snapshots should be treated as diagnostics; malformed, truncated, or redacted snapshots are not a
substitute for application-level validation.

## CLI-backed integrations

The optional integrations module treats CLI output as untrusted data. A command-backed tool result is an observation,
not an instruction.

# Expect Automation

Use `Expect` when an already opened interactive session should be automated by waiting for prompt text or regular
expression matches.

The scenario covers:

- literal and regex matching against stdout;
- stderr draining into the bounded transcript;
- bounded transcript and match buffer limits;
- send and send-line helpers;
- timeout, EOF, closed, and read/write failure distinction;
- optional output filtering before matching and transcript retention;
- redacted action values by default.

## Example

```java
CommandService repl = CommandService.forCommand("tool");

try (Session session = repl.interactive(call -> call.args("repl"));
        Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(2)))) {
    expect.expectText("ready> ");
    expect.sendLine("status");
    expect.expectRegex(java.util.regex.Pattern.compile("ok|ready"));
}
```

Compile-tested source: `CommandServiceApiExamples.expectScenario`.

## Transcript values

`Expect` records action entries such as send and expect operations, but caller-provided values are redacted by default in
transcripts and failure messages. Use verbatim transcript values only for non-secret automation where diagnostics require
exact prompt or input text.

## User responsibilities

The caller owns the prompt ordering and pattern semantics. A fragile regex can still match too early or too late.
Choose transcript and match buffer limits large enough for the prompt protocol, but keep them bounded.

## Scenario boundary

`Expect` does not launch processes. It wraps a `Session` and claims output ownership for prompt automation.

# Automate Prompts

Use `interactive` plus `Expect` when a command presents prompts and the caller needs to wait for text before sending
input.

## Steps

1. Open an interactive `Session`.
2. Create `Expect` before reading raw stdout or stderr.
3. Wait for prompt text or regex matches.
4. Send input through `Expect`.
5. Keep transcript values redacted unless exact values are safe to record.

```java
CommandService repl = Icli.command("tool");

try (Session session = repl.interactive().withArgs("repl").open();
        Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(2)))) {
    expect.expectText("ready> ");
    expect.sendLine("status");
    expect.expectRegex(java.util.regex.Pattern.compile("ok|ready"));
}
```

## Use this scenario because

Prompt automation needs output ownership, timeout/EOF distinction, and bounded transcripts. Raw `interactive` would
leave matching and transcript policy to the caller.

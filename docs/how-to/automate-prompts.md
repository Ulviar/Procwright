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
CommandService repl = Procwright.command("tool");

try (Session session = repl.interactive().withArgs("repl").open();
        Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(2)))) {
    expect.expectText("ready> ");
    expect.sendLine("status");
    expect.expectRegex(java.util.regex.Pattern.compile("ok|ready"));
}
```

## Read the matched output

Use `expectTextMatch` or `expectRegexMatch` when the caller needs the matched output, not only the synchronization
point. Both wait like `expectText`/`expectRegex` (with the same default and per-call timeouts) but return an
`ExpectMatch` carrying the matched text, regex capture groups in declaration order (empty for literal matches;
non-participating groups are empty strings), and the output consumed before the match within the bounded match buffer.

```java
ExpectMatch match = expect.expectRegexMatch(java.util.regex.Pattern.compile("version (\\d+\\.\\d+)"));
String version = match.groups().get(0);
String beforeMatch = match.before();
```

Unlike transcripts, a match result is live process output that the caller explicitly asked for — it is not redacted.
Do not log match results verbatim when the automated prompt may contain secrets.

## Use this scenario because

Prompt automation needs output ownership, timeout/EOF distinction, and bounded transcripts. Raw `interactive` would
leave matching and transcript policy to the caller.

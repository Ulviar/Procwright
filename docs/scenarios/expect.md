# Expect automation

`interactive().expect()` waits for text or a regular expression, then lets you send the next reply. Start with
[Automate prompts](../how-to/automate-prompts.md) for a complete example.

Choose this mode before `open()`. Expect owns process output: it matches decoded stdout and drains stderr into the
transcript. Raw output streams are not exposed. Close the handle with try-with-resources to stop its process.

## Matching and input

- `expectText(...)` and `expectRegex(...)` wait for a match. Their `*Match(...)` variants also return matched text, regex
  groups, and the output before the match. These returned values are live output, not redacted transcript text.
- Match and transcript buffers have separate limits. Increase the match buffer for large prompts and the transcript
  limit when you need more diagnostic history.
- `sendLine(...)` writes a reply followed by LF; `send(...)` writes text without a line separator.
- `closeStdin()` rejects later writes and starts closing input without stopping output matching. Use it when the command
  prints its final response after EOF. Its return does not confirm that the child has observed EOF. A later close failure
  can complete `onExit()` exceptionally with the original failure.
- `withCharset(...)` sets input and output encoding. Add `withOutputCharset(...)` only if the output encoding differs.

## Failures and retry

`ExpectException` contains a reason and a bounded transcript snapshot. A timeout while waiting for output or another
matcher leaves the handle open. A regex evaluation that outlives its deadline makes the handle terminal: Procwright stops
the process and rejects later matches. After a regex timeout, use a fresh handle instead of assuming it is safe to retry.

Input or output failures stop the process. If stdout reaches EOF before a match and the process is still running,
Procwright stops it; an already selected natural exit keeps its process result. When close, EOF, and output failures race,
the first selected failure reason is retained.

## ANSI-decorated prompts

Add `withAnsiControlSequenceStripping()` before `open()` to match prompts containing ANSI colors or other CSI sequences:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/AnsiExpectExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import java.time.Duration;

public final class AnsiExpectExample {

    private AnsiExpectExample() {}

    public static void main(String[] args) {
        try (Expect expect = Procwright.command(ExampleSupport.workerCommand("ansi-expect"))
                .interactive()
                .expect()
                .withAnsiControlSequenceStripping()
                .withTimeout(Duration.ofSeconds(5))
                .open()) {
            expect.expectText("ready> ");
        }
    }
}
```

[Open `AnsiExpectExample.java`](../examples/java/io/github/ulviar/procwright/examples/AnsiExpectExample.java).

The option removes complete 7-bit ECMA-48 CSI sequences beginning with `ESC [`, including sequences split across output
chunks. It does not interpret cursor movement or reconstruct a terminal screen. Other control-sequence families, and
incomplete, malformed, or overlong CSI candidates, remain in the text.

See [scenario defaults](../reference/defaults.md#expect) for match timeout, transcript, match buffer, charsets, ANSI, and
redaction values.

# Talk to a line worker

Use `lineSession()` only when one request is one encoded line and one response is one stdout line.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import java.time.Duration;

public final class LineSessionExample {

    private LineSessionExample() {}

    public static void main(String[] args) {
        try (LineSession session = Procwright.command(ExampleSupport.workerCommand("line"))
                .lineSession()
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            LineResponse response = session.request("Zażółć gęślą jaźń");
            if (!response.text().equals("response:Zażółć gęślą jaźń")) {
                throw new IllegalStateException("Unexpected line response");
            }
        }
    }
}
```

[Open `LineSessionExample.java`](../examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java) and the
[shared example sources](../examples.md#core).

This example uses the exact defaults: one exchange is capped at 1,024 response lines and 1,048,576 response characters,
and unread stdout is capped at 1,024 lines and 1,048,576 characters. See
[line-session defaults](../reference/defaults.md#line-sessions) for the request, line, transcript, and decoding limits.

One session permits one request at a time. Set a request timeout and close the session with try-with-resources. Local
validation, request-size, encoding, and wait failures leave the direct session open when the request was not handed off
for stdin writing and cannot write later. Once it is handed off, a timeout, interruption, or write failure closes the
direct session because stream framing can no longer be trusted, even if no received byte can be confirmed. EOF, malformed
output, oversized response, and other protocol failures are also terminal.

Use [protocol sessions](../scenarios/protocol-session.md) when requests or responses can contain newlines or use custom
framing.

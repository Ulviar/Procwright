# Protocol sessions

`protocolSession(adapterFactory)` models request/response protocols whose adapter owns framing and typed conversion.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ProtocolSessionExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentRequest;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentResponse;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ProtocolSessionExample {

    private ProtocolSessionExample() {}

    public static void main(String[] args) {
        try (ProtocolSession<DocumentRequest, DocumentResponse> session = Procwright.command(
                        ExampleSupport.workerCommand("protocol"))
                .protocolSession(LengthLineFrameAdapter::new)
                .withRequestTimeout(Duration.ofSeconds(5))
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .withMaxRequestBytes(16_384)
                .withMaxRequestChars(8192)
                .withMaxResponseBytes(16_384)
                .withMaxResponseChars(8192)
                .withOutputBacklogLimit(16_384)
                .open()) {
            DocumentResponse response = session.request(new DocumentRequest("first line\nПривет, 世界"));
            if (!response.text().equals("first line\nПривет, 世界")) {
                throw new IllegalStateException("Unexpected protocol response");
            }
        }
    }
}
```

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/DocumentProtocol.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

public final class DocumentProtocol {

    private DocumentProtocol() {}

    public record DocumentRequest(String text) {}

    public record DocumentResponse(String text) {}
}
```

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/LengthLineFrameAdapter.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentRequest;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentResponse;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;

final class LengthLineFrameAdapter implements ProtocolAdapter<DocumentRequest, DocumentResponse> {

    private static final int MAX_HEADER_CHARS = 64;
    private static final int MAX_BODY_BYTES = 8192;
    private static final int MAX_BODY_CHARS = 4096;

    @Override
    public void writeRequest(DocumentRequest request, ProtocolWriter writer) {
        byte[] body = request.text().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Request body exceeds " + MAX_BODY_BYTES + " UTF-8 bytes");
        }
        writer.writeLine(Integer.toString(body.length));
        writer.write(request.text());
        writer.flush();
    }

    @Override
    public DocumentResponse readResponse(ProtocolReaders readers) {
        ProtocolReader stdout = readers.stdout();
        int length = parseBodyLength(stdout.readLine(MAX_HEADER_CHARS));
        String body = stdout.readTextExactly(length, MAX_BODY_CHARS);
        if (!stdout.readLine(8).isEmpty() || !stdout.readLine(16).equals("END")) {
            throw new IllegalStateException("Unexpected frame terminator");
        }
        return new DocumentResponse(body);
    }

    static int parseBodyLength(String header) {
        String prefix = "len:";
        if (!header.startsWith(prefix) || header.length() == prefix.length()) {
            throw invalidHeader(header);
        }
        if (header.length() > prefix.length() + 1 && header.charAt(prefix.length()) == '0') {
            throw invalidHeader(header);
        }
        int length = 0;
        for (int index = prefix.length(); index < header.length(); index++) {
            char digit = header.charAt(index);
            if (digit < '0' || digit > '9') {
                throw invalidHeader(header);
            }
            int value = digit - '0';
            if (length > (MAX_BODY_BYTES - value) / 10) {
                throw invalidHeader(header);
            }
            length = length * 10 + value;
        }
        return length;
    }

    private static IllegalStateException invalidHeader(String header) {
        return new IllegalStateException("Invalid response length header: " + header);
    }
}
```

[Open `ProtocolSessionExample.java`](../examples/java/io/github/ulviar/procwright/examples/ProtocolSessionExample.java),
[the message types](../examples/java/io/github/ulviar/procwright/examples/DocumentProtocol.java),
[the adapter](../examples/java/io/github/ulviar/procwright/examples/LengthLineFrameAdapter.java), and the
[shared example sources](../examples.md#core).

Pass a `Supplier<ProtocolAdapter<I, O>>`. Each `open()` and each pool worker receives a fresh adapter. Only mutable state
created by or owned by that adapter is isolated. Factory calls may overlap during concurrent opens or pool startup; mutable
state captured from outside remains shared and must be synchronized or avoided. Factory failure or `null` fails before a
process starts.

One session serializes its adapter calls: request writing completes before response decoding, and no second request cycle
overlaps them. Different factory-created adapters can run concurrently. Readiness, diagnostics recipients, and a custom
PTY provider are retained separately from the adapters; concurrent direct opens and protocol-pool workers can invoke the
same supplied instances concurrently. Make them thread-safe or use separate Draft branches with separate instances.

A timeout or interruption while waiting for the serialized request slot happens before adapter admission, writes no
request bytes and leaves the direct session open. The caller can retry after the active request completes. If the session
has already selected a terminal failure or fatal error, that outcome wins instead. Once the serialized slot is acquired,
the request owns the session; a timeout, interruption, callback-start failure, or protocol failure closes the direct
session because the runtime can no longer prove that request processing did not begin.

`ProtocolWriter` and `ProtocolReader` are callback-scoped and thread-confined. Use them only on the thread executing the
adapter callback and do not retain them after it returns. A late or cross-thread call fails before touching process I/O,
so it cannot write during response decoding or consume output belonging to a later request.

`DocumentRequest` and `DocumentResponse` keep the public session typed while `LengthLineFrameAdapter` owns wire framing.
The adapter writes the UTF-8 byte length of `DocumentRequest.text()` and then its text. It reads
`len:<bytes>\n<body>\nEND\n`; a body such as
`first line\nsecond line` therefore ends with `second line\nEND\n`, not an extra blank line. `readTextExactly` treats
the declared body bytes as one complete field, applies strict UTF-8 decoding, and enforces both its local character
limit and the response-global character budget. The adapter bounds the header at 64 characters and accepts only an
ASCII decimal body length from 0 through 8192 bytes.

Every `ProtocolReader` method enforces the session deadline, response-global byte budget, and unread backlog limit. Text
methods additionally apply the configured `CharsetPolicy` and response-global character budget. Raw methods such as
`readExactly` return bytes without decoding and therefore do not count characters. Use `readTextExactly` for a
length-framed complete text field; use `readLine` or `readTextUntil` for a continuous text stream. Switch modes only at
complete character boundaries. Procwright rejects a raw or exact-field read before consumption when continuous decoding
still holds an incomplete character. It cannot infer a boundary after arbitrary raw bytes, so the adapter must know its
framing before returning to continuous text. A character-limit failure stops after the first excess decoded character
instead of draining the rest of a declared text field; the terminal session close discards any unread field bytes.

See [scenario defaults](../reference/defaults.md#protocol-sessions) for request, response, backlog, decoding, terminal,
and readiness limits.

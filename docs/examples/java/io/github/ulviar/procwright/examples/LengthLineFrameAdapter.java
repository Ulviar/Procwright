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

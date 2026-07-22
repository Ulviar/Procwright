/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;

final class LengthPrefixedTextAdapter implements ProtocolAdapter<String, String> {

    @Override
    public void writeRequest(String request, ProtocolWriter writer) {
        byte[] body = request.getBytes(StandardCharsets.UTF_8);
        writer.writeLine(Integer.toString(body.length));
        writer.write(body);
        writer.flush();
    }

    @Override
    public String readResponse(ProtocolReaders readers) {
        ProtocolReader stdout = readers.stdout();
        String header = stdout.readLine(32);
        if (!header.startsWith("len:")) {
            throw new IllegalArgumentException("missing length prefix");
        }
        int length = Integer.parseInt(header.substring("len:".length()));
        byte[] body = stdout.readExactly(length);
        if (!stdout.readLine(1).isEmpty() || !stdout.readLine(8).equals("END")) {
            throw new IllegalArgumentException("invalid response terminator");
        }
        return new String(body, StandardCharsets.UTF_8);
    }
}

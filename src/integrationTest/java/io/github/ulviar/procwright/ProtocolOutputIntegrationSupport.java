/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;

final class ProtocolOutputIntegrationSupport {

    private ProtocolOutputIntegrationSupport() {}

    static final class StderrLineAdapter implements ProtocolAdapter<String, String> {

        private final int maxChars;

        StderrLineAdapter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stderr().readLine(maxChars);
        }
    }

    static final class StderrEchoAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(":stderr " + request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stderr().readLine(64);
        }
    }
}

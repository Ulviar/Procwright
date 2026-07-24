/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.awaitIgnoringInterrupts;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

final class ProtocolConcurrencyIntegrationSupport {

    private ProtocolConcurrencyIntegrationSupport() {}

    static final class CoordinatedTwoLineAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch firstResponseStarted;
        private final List<String> writtenRequests = new ArrayList<>();

        CoordinatedTwoLineAdapter(CountDownLatch firstResponseStarted) {
            this.firstResponseStarted = firstResponseStarted;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writtenRequests.add(request);
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            String first = stdout.readLine(32);
            firstResponseStarted.countDown();
            return first + "\n" + stdout.readLine(32);
        }

        List<String> writtenRequests() {
            return List.copyOf(writtenRequests);
        }
    }

    static final class SlowAfterReadAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch responseRead;
        private final CountDownLatch release;

        SlowAfterReadAdapter(CountDownLatch responseRead, CountDownLatch release) {
            this.responseRead = responseRead;
            this.release = release;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            String response = readers.stdout().readLine(64);
            responseRead.countDown();
            awaitIgnoringInterrupts(release);
            return response;
        }
    }
}

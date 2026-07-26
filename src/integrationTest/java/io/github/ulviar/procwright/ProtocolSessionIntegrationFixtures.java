/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;

final class ProtocolSessionIntegrationFixtures {

    private ProtocolSessionIntegrationFixtures() {}

    static CommandService fixtureService() {
        return Procwright.command(TestCliSupport.command());
    }

    static <I, O> ProtocolSession<I, O> openProtocolSession(
            CommandService service,
            ProtocolAdapter<I, O> adapter,
            UnaryOperator<ProtocolSessionScenario.Draft<I, O>> configure) {
        return configure.apply(service.protocolSession(() -> adapter)).open();
    }

    static Throwable captureFailure(ThrowingOperation operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static int parseLength(String line) {
        if (!line.startsWith("len:")) {
            throw new IllegalArgumentException("missing length prefix");
        }
        return Integer.parseInt(line.substring("len:".length()));
    }

    @FunctionalInterface
    interface ThrowingOperation {

        void run() throws Throwable;
    }

    static class FramedStringAdapter implements ProtocolAdapter<String, String> {

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
            int length = parseLength(stdout.readLine(32));
            byte[] body = stdout.readExactly(length);
            assertEquals("", stdout.readLine(1));
            assertEquals("END", stdout.readLine(8));
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static class TextLineAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.write(request);
            writer.write("\n");
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(64);
        }
    }

    static final class StdoutLineAdapter implements ProtocolAdapter<String, String> {

        private final int maxChars;

        StdoutLineAdapter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(maxChars);
        }
    }

    static final class TwoLineTextAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            return stdout.readLine(32) + "\n" + stdout.readLine(32);
        }
    }
}

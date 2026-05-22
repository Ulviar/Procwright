package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.session.LineResponse;
import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledProtocolSession;
import com.github.ulviar.icli.session.ProtocolAdapter;
import com.github.ulviar.icli.session.ProtocolReader;
import com.github.ulviar.icli.session.ProtocolReaders;
import com.github.ulviar.icli.session.ProtocolSession;
import com.github.ulviar.icli.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ScenarioEntrypointIntegrationTest {

    @Test
    void runScenarioExecutesFromUnifiedEntryPoint() {
        CommandResult result = Icli.command(TestCliSupport.command())
                .run()
                .withArgs("exit", "--stdout=ready\n")
                .execute();

        assertTrue(result.succeeded());
        assertEquals("ready\n", result.stdout());
    }

    @Test
    void lineSessionScenarioOpensWorkerBeforeOptionalPooling() {
        try (LineSession session = Icli.command(TestCliSupport.command())
                .lineSession()
                .withArgs("controlled-line-repl")
                .withRequestTimeout(Duration.ofSeconds(2))
                .open()) {
            assertEquals("response:alpha", session.request("alpha").text());
        }
    }

    @Test
    void pooledLineSessionScenarioBranchesAfterLineSessionChoice() {
        try (PooledLineSession pool = Icli.command(TestCliSupport.command())
                .lineSession()
                .withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            LineResponse response = pool.request("pooled");

            assertEquals("response:pooled", response.text());
            assertEquals(1, pool.metrics().created());
        }
    }

    @Test
    void protocolSessionScenarioOpensTypedWorker() {
        try (ProtocolSession<String, String> session = Icli.command(TestCliSupport.command())
                .protocolSession(new FramedStringAdapter())
                .withArgs("length-line-frame")
                .withRequestTimeout(Duration.ofSeconds(2))
                .open()) {
            assertEquals("document", session.request("document"));
        }
    }

    @Test
    void pooledProtocolScenarioBranchesAfterProtocolSessionChoice() {
        try (PooledProtocolSession<String, String> pool = Icli.command(TestCliSupport.command())
                .protocolSession(FramedStringAdapter::new)
                .withArgs("length-line-frame")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            assertEquals("document", pool.request("document"));
            assertEquals(1, pool.metrics().created());
        }
    }

    private static final class FramedStringAdapter implements ProtocolAdapter<String, String> {

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

        private static int parseLength(String line) {
            if (!line.startsWith("len:")) {
                throw new IllegalArgumentException("missing length prefix");
            }
            return Integer.parseInt(line.substring("len:".length()));
        }
    }
}

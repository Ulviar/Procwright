package io.github.ulviar.procwright.consumer.examples;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ConsumerScenarios {

    private ConsumerScenarios() {}

    public static CommandResult run(CommandSpec command) {
        return Procwright.command(command)
                .run()
                .withArgs("stdin-echo", "--mode=text", "--prefix=echo:")
                .withInput("hello")
                .withTimeout(Duration.ofSeconds(2))
                .execute();
    }

    public static LineResponse lineSession(CommandSpec command) {
        try (LineSession session = Procwright.command(command)
                .lineSession()
                .withArgs("controlled-line-repl")
                .withRequestTimeout(Duration.ofSeconds(2))
                .open()) {
            return session.request("status");
        }
    }

    public static String protocolSession(CommandSpec command) {
        try (ProtocolSession<String, String> session = Procwright.command(command)
                .protocolSession(new LengthLineFrameAdapter())
                .withArgs("length-line-frame")
                .withRequestTimeout(Duration.ofSeconds(2))
                .open()) {
            return session.request("one\ntwo");
        }
    }

    public static LineResponse pooledLineSession(CommandSpec command) {
        CommandService service = Procwright.command(command);
        try (PooledLineSession pool = service.lineSession()
                .withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withMaxRequestsPerWorker(100)
                .open()) {
            return pool.request("status", Duration.ofSeconds(2));
        }
    }

    public static String pooledProtocolSession(CommandSpec command) {
        try (PooledProtocolSession<String, String> pool = Procwright.command(command)
                .protocolSession(LengthLineFrameAdapter::new)
                .withArgs("length-line-frame")
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withMinIdle(1)
                .open()) {
            return pool.request("pooled\nbody", Duration.ofSeconds(2));
        }
    }

    private static final class LengthLineFrameAdapter implements ProtocolAdapter<String, String> {

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
            String header = stdout.readLine(64);
            if (!header.startsWith("len:")) {
                throw new IllegalStateException("Unexpected response header: " + header);
            }
            int length = Integer.parseInt(header.substring("len:".length()));
            String body = new String(stdout.readExactly(length), StandardCharsets.UTF_8);
            String separator = stdout.readLine(8);
            String end = stdout.readLine(16);
            if (!separator.isEmpty() || !end.equals("END")) {
                throw new IllegalStateException("Unexpected frame terminator");
            }
            return body;
        }
    }
}

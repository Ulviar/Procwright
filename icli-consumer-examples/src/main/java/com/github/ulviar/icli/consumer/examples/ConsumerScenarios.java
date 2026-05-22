package com.github.ulviar.icli.consumer.examples;

import com.github.ulviar.icli.CommandService;
import com.github.ulviar.icli.Icli;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.command.CommandSpec;
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

public final class ConsumerScenarios {

    private ConsumerScenarios() {}

    public static CommandResult run(CommandSpec command) {
        return Icli.command(command)
                .run()
                .withArgs("stdin-echo", "--mode=text", "--prefix=echo:")
                .withInput("hello")
                .withTimeout(Duration.ofSeconds(2))
                .execute();
    }

    public static LineResponse lineSession(CommandSpec command) {
        try (LineSession session = Icli.command(command)
                .lineSession()
                .withArgs("controlled-line-repl")
                .withRequestTimeout(Duration.ofSeconds(2))
                .open()) {
            return session.request("status");
        }
    }

    public static String protocolSession(CommandSpec command) {
        try (ProtocolSession<String, String> session = Icli.command(command)
                .protocolSession(new LengthLineFrameAdapter())
                .withArgs("length-line-frame")
                .withRequestTimeout(Duration.ofSeconds(2))
                .open()) {
            return session.request("one\ntwo");
        }
    }

    public static LineResponse pooledLineSession(CommandSpec command) {
        CommandService service = Icli.command(command);
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
        try (PooledProtocolSession<String, String> pool = Icli.command(command)
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

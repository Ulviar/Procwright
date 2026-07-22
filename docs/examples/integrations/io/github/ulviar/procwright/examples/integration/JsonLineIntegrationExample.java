/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.integration.ProtocolAdapters;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

public final class JsonLineIntegrationExample {

    private static final int MAX_FRAME = 1024 * 1024;

    private JsonLineIntegrationExample() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            runWorker(args[0]);
            return;
        }

        JsonNode request = JsonNodeFactory.instance.objectNode().put("input", "café — Привет, 世界");
        try (ProtocolSession<JsonNode, JsonNode> session = Procwright.command(workerCommand("--json-lines-worker"))
                .protocolSession(ProtocolAdapters.jsonLinesSession(MAX_FRAME))
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            if (!session.request(request).equals(request)) {
                throw new IllegalStateException("Unexpected JSON Lines response");
            }
        }

        var typedFactory = ProtocolAdapters.typedJsonSession(
                TextNode::valueOf, JsonNode::textValue, ProtocolAdapters.jsonLinesSession(MAX_FRAME));
        try (ProtocolSession<String, String> session = Procwright.command(workerCommand("--json-lines-worker"))
                .protocolSession(typedFactory)
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            if (!session.request("typed-json").equals("typed-json")) {
                throw new IllegalStateException("Unexpected typed JSON response");
            }
        }

        byte[] binaryRequest = new byte[] {1, 2, 3, (byte) 0xFF};
        try (ProtocolSession<byte[], byte[]> session = Procwright.command(workerCommand("--delimiter-worker"))
                .protocolSession(ProtocolAdapters.delimiterSession((byte) 0, MAX_FRAME))
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            if (!Arrays.equals(session.request(binaryRequest), binaryRequest)) {
                throw new IllegalStateException("Unexpected delimiter-framed response");
            }
        }
    }

    private static void runWorker(String mode) throws Exception {
        switch (mode) {
            case "--json-lines-worker" -> echoJsonLines();
            case "--delimiter-worker" -> echoDelimitedFrames();
            default -> throw new IllegalArgumentException("Unknown worker mode");
        }
    }

    private static void echoJsonLines() throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            output.write(line);
            output.write('\n');
            output.flush();
        }
    }

    private static void echoDelimitedFrames() throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int value;
        while ((value = System.in.read()) >= 0) {
            if (value == 0) {
                frame.writeTo(System.out);
                System.out.write(0);
                System.out.flush();
                frame.reset();
            } else {
                frame.write(value);
            }
        }
    }

    private static CommandSpec workerCommand(String mode) {
        return CommandSpec.of(javaExecutable())
                .withArgs(
                        "-cp", System.getProperty("java.class.path"), JsonLineIntegrationExample.class.getName(), mode);
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}

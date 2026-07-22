/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.integration.IntegrationProtocolException;
import io.github.ulviar.procwright.integration.ProtocolAdapters;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

public final class TypedContentLengthJsonSessionExample {

    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_REQUEST_WIRE_BYTES = MAX_HEADER_BYTES + MAX_BODY_BYTES;
    private static final int MAX_RESPONSE_WIRE_BYTES = MAX_HEADER_BYTES + MAX_BODY_BYTES;
    private static final ObjectMapper JSON = new ObjectMapper();

    private TypedContentLengthJsonSessionExample() {}

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--worker")) {
            runWorker();
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Expected no arguments or --worker");
        }

        Supplier<ProtocolAdapter<TextMetricsRequest, TextMetricsResponse>> adapterFactory =
                ProtocolAdapters.typedJsonSession(
                        TypedContentLengthJsonSessionExample::encodeRequest,
                        TypedContentLengthJsonSessionExample::decodeResponse,
                        ProtocolAdapters.contentLengthJsonSession(MAX_BODY_BYTES));

        try (ProtocolSession<TextMetricsRequest, TextMetricsResponse> session = Procwright.command(workerCommand())
                .protocolSession(adapterFactory)
                .withRequestTimeout(Duration.ofSeconds(5))
                .withMaxRequestBytes(MAX_REQUEST_WIRE_BYTES)
                .withMaxResponseBytes(MAX_RESPONSE_WIRE_BYTES)
                .withOutputBacklogLimit(MAX_RESPONSE_WIRE_BYTES)
                .open()) {
            TextMetricsResponse response = session.request(new TextMetricsRequest("cafe\u0301 \uD83D\uDE80"));
            if (response.codePoints() != 7 || response.utf8Bytes() != 11) {
                throw new IllegalStateException("Unexpected text metrics: " + response);
            }
        } catch (ProtocolSessionException failure) {
            System.err.println("Protocol request failed: " + failure.reason());
            if (failure.getCause() instanceof IntegrationProtocolException framingFailure) {
                System.err.println("Framing failed: " + framingFailure.reason());
            }
            throw failure;
        }
    }

    private static void runWorker() {
        try {
            TextMetricsRequest request = decodeRequest(readFrame());
            TextMetricsResponse response = new TextMetricsResponse(
                    request.text().codePointCount(0, request.text().length()),
                    request.text().getBytes(StandardCharsets.UTF_8).length);
            writeFrame(encodeResponse(response));
        } catch (IOException exception) {
            throw new IllegalStateException("Worker framing failed", exception);
        }
    }

    private static JsonNode encodeRequest(TextMetricsRequest request) {
        return JsonNodeFactory.instance.objectNode().put("text", request.text());
    }

    private static TextMetricsRequest decodeRequest(JsonNode value) {
        JsonNode text = value.get("text");
        if (text == null || !text.isTextual()) {
            throw new IllegalArgumentException("text must be a string");
        }
        return new TextMetricsRequest(text.textValue());
    }

    private static JsonNode encodeResponse(TextMetricsResponse response) {
        return JsonNodeFactory.instance
                .objectNode()
                .put("codePoints", response.codePoints())
                .put("utf8Bytes", response.utf8Bytes());
    }

    private static TextMetricsResponse decodeResponse(JsonNode value) {
        return new TextMetricsResponse(
                value.required("codePoints").intValue(),
                value.required("utf8Bytes").intValue());
    }

    private static JsonNode readFrame() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (header.size() < MAX_HEADER_BYTES) {
            int value = System.in.read();
            if (value < 0) {
                throw new IOException("Input ended before frame headers");
            }
            header.write(value);
            byte expected = new byte[] {'\r', '\n', '\r', '\n'}[matched];
            matched = value == expected ? matched + 1 : value == '\r' ? 1 : 0;
            if (matched == 4) {
                break;
            }
        }
        String firstLine =
                header.toString(StandardCharsets.US_ASCII).lines().findFirst().orElseThrow();
        int length = Integer.parseInt(firstLine.substring("Content-Length: ".length()));
        if (length > MAX_BODY_BYTES) {
            throw new IOException("Frame body is too large");
        }
        byte[] body = System.in.readNBytes(length);
        if (body.length != length) {
            throw new IOException("Input ended before frame body");
        }
        return JSON.readTree(body);
    }

    private static void writeFrame(JsonNode value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        System.out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        System.out.write(body);
        System.out.flush();
    }

    private static CommandSpec workerCommand() {
        return CommandSpec.of(javaExecutable())
                .withArgs(
                        "-cp",
                        System.getProperty("java.class.path"),
                        TypedContentLengthJsonSessionExample.class.getName(),
                        "--worker");
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    public record TextMetricsRequest(String text) {}

    public record TextMetricsResponse(int codePoints, int utf8Bytes) {}
}

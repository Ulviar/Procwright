/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.integration.ContentLengthJsonFrames;
import io.github.ulviar.procwright.integration.IntegrationProtocolException;
import io.github.ulviar.procwright.integration.JsonValue;
import io.github.ulviar.procwright.integration.ProtocolAdapters;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

public final class TypedContentLengthJsonSessionExample {

    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_GENERATED_REQUEST_HEADER_BYTES =
            ("Content-Length: " + MAX_BODY_BYTES + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII).length;
    private static final int MAX_REQUEST_WIRE_BYTES = Math.addExact(MAX_GENERATED_REQUEST_HEADER_BYTES, MAX_BODY_BYTES);
    private static final int MAX_RESPONSE_WIRE_BYTES = MAX_HEADER_BYTES + MAX_BODY_BYTES;

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
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
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
            if (failure.getCause() instanceof IntegrationProtocolException protocolFailure) {
                System.err.println("Content-Length failure: " + protocolFailure.reason());
            }
            throw failure;
        }
    }

    private static void runWorker() {
        TextMetricsRequest request = decodeRequest(ContentLengthJsonFrames.read(System.in, MAX_BODY_BYTES));
        TextMetricsResponse response = new TextMetricsResponse(
                request.text().codePointCount(0, request.text().length()),
                request.text().getBytes(StandardCharsets.UTF_8).length);
        ContentLengthJsonFrames.write(System.out, encodeResponse(response));
    }

    private static JsonValue encodeRequest(TextMetricsRequest request) {
        return JsonValue.object(Map.of("text", JsonValue.string(request.text())));
    }

    private static TextMetricsRequest decodeRequest(JsonValue value) {
        return new TextMetricsRequest(stringMember(value, "text"));
    }

    private static JsonValue encodeResponse(TextMetricsResponse response) {
        return JsonValue.object(Map.of(
                "codePoints", JsonValue.number(response.codePoints()),
                "utf8Bytes", JsonValue.number(response.utf8Bytes())));
    }

    private static TextMetricsResponse decodeResponse(JsonValue value) {
        return new TextMetricsResponse(intMember(value, "codePoints"), intMember(value, "utf8Bytes"));
    }

    private static String stringMember(JsonValue value, String name) {
        JsonValue member =
                object(value).member(name).orElseThrow(() -> new IllegalArgumentException("Missing " + name));
        if (member instanceof JsonValue.JsonString string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    private static int intMember(JsonValue value, String name) {
        JsonValue member =
                object(value).member(name).orElseThrow(() -> new IllegalArgumentException("Missing " + name));
        if (member instanceof JsonValue.JsonNumber number) {
            return number.value().intValueExact();
        }
        throw new IllegalArgumentException(name + " must be a number");
    }

    private static JsonValue.JsonObject object(JsonValue value) {
        if (value instanceof JsonValue.JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException("Expected a JSON object");
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

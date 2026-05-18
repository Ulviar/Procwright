package com.github.ulviar.icli.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.ulviar.icli.command.CommandResult;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

final class CliAdapterErrorTest {

    @Test
    void mapsCommandFailureWithoutOutputExcerpts() {
        CommandResult result = new CommandResult(
                OptionalInt.of(7),
                "secret-stdout".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "secret-stderr".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "secret-stdout",
                "secret-stderr",
                false,
                false,
                false,
                Duration.ZERO);

        CliAdapterError error = CliAdapterError.from(result);
        String encoded = JsonCodec.write(error.toJson());

        assertEquals("command_failed", error.code());
        assertFalse(encoded.contains("secret"));
        assertEquals(JsonValue.number(7), error.details().member("exitCode").orElseThrow());
    }

    @Test
    void mapsProtocolFailuresWithReason() {
        CliAdapterError error = CliAdapterError.from(
                new IntegrationProtocolException(IntegrationProtocolException.Reason.OVERSIZED_FRAME, "too large"));

        assertEquals("protocol_error", error.code());
        assertEquals(
                JsonValue.string("OVERSIZED_FRAME"),
                error.details().member("reason").orElseThrow());
    }

    @Test
    void unwrapsCompletionAndExecutionExceptionFailures() {
        CliAdapterError cancelled = CliAdapterError.from(new CompletionException(new CancellationException()));
        CliAdapterError protocol = CliAdapterError.from(new ExecutionException(new JsonParseException("bad json")));

        assertEquals("cancelled", cancelled.code());
        assertEquals("protocol_error", protocol.code());
    }

    @Test
    void toolCallResultCarriesSuccessOrStructuredFailure() {
        ToolCallResult<String> success = ToolCallResult.success("ok");
        ToolCallResult<String> failure = ToolCallResult.failure(new JsonParseException("bad json"));

        assertEquals("ok", success.value().orElseThrow());
        assertFalse(failure.succeeded());
        assertEquals("protocol_error", failure.error().orElseThrow().code());
    }
}

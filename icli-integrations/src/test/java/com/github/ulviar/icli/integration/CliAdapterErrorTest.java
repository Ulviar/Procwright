package com.github.ulviar.icli.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.session.PooledLineSessionException;
import com.github.ulviar.icli.session.ProtocolSessionException;
import com.github.ulviar.icli.session.ProtocolTranscript;
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
    void mapsRuntimeProtocolFailuresWithoutTranscriptText() {
        CliAdapterError error = CliAdapterError.from(new ProtocolSessionException(
                ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                new ProtocolTranscript("secret output", true, true, false),
                OptionalInt.of(3),
                "too large",
                null));
        String encoded = JsonCodec.write(error.toJson());

        assertEquals("protocol_response_too_large", error.code());
        assertEquals(
                JsonValue.string("RESPONSE_TOO_LARGE"),
                error.details().member("reason").orElseThrow());
        assertEquals(JsonValue.number(3), error.details().member("exitCode").orElseThrow());
        assertEquals(
                JsonValue.bool(true),
                error.details().member("transcriptTruncated").orElseThrow());
        assertEquals(
                JsonValue.bool(true),
                error.details().member("transcriptMalformed").orElseThrow());
        assertFalse(encoded.contains("secret output"));
    }

    @Test
    void mapsPooledFailuresWithStableCodes() {
        CliAdapterError error = CliAdapterError.from(
                new PooledLineSessionException(PooledLineSessionException.Reason.STARTUP_FAILED, "startup failed"));

        assertEquals("pool_startup_failed", error.code());
        assertEquals(
                JsonValue.string("STARTUP_FAILED"),
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

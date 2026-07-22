/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.Session;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class CommandBackedToolTest {

    @Test
    void mapsThrowingHandlerToStructuredFailure() {
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw new JsonParseException("bad json");
        });

        ToolCallResult<String> result = tool.call("payload");

        assertFalse(result.succeeded());
        assertEquals("protocol_error", result.error().orElseThrow().code());
    }

    @Test
    void returnsSuccessValue() {
        CommandBackedTool<String, Integer> tool = CommandBackedTool.of(String::length);

        ToolCallResult<Integer> result = tool.call("tool");

        assertTrue(result.succeeded());
        assertEquals(4, result.value().orElseThrow());
    }

    @Test
    void doesNotConvertSeriousErrorsToToolFailures() {
        AssertionError failure = new AssertionError("serious");
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw failure;
        });

        assertSame(failure, assertThrows(AssertionError.class, () -> tool.call("payload")));
    }

    @ParameterizedTest
    @MethodSource("expectFailures")
    void mapsEveryExpectFailureThroughToolBoundaryWithoutSensitivePayload(
            ExpectException.Reason reason, boolean truncated, boolean malformed, String message) {
        IllegalStateException cause = new IllegalStateException("sensitive-cause-marker");
        ExpectException failure = new ExpectException(
                reason, new LineTranscript("sensitive-transcript-marker", truncated, malformed), message, cause);
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw failure;
        });

        ToolCallResult<String> result = tool.call("payload");

        assertFalse(result.succeeded());
        CliAdapterError error = result.error().orElseThrow();
        assertEquals(expectCode(reason), error.code());
        assertEquals("Expect operation failed", error.message());
        assertEquals(
                Map.of(
                        "reason", JsonValue.string(reason.name()),
                        "transcriptTruncated", JsonValue.bool(truncated),
                        "transcriptMalformed", JsonValue.bool(malformed)),
                error.details().members());
        assertSame(cause, failure.getCause());
        String serialized = JsonCodec.write(error.toJson());
        assertFalse(serialized.contains("sensitive"));
        assertFalse(serialized.contains("unsafe-control"));
        assertFalse(serialized.contains("\\u0000"));
    }

    private static Stream<Arguments> expectFailures() {
        List<TranscriptState> transcriptStates = List.of(
                new TranscriptState(false, false),
                new TranscriptState(false, true),
                new TranscriptState(true, false),
                new TranscriptState(true, true));
        List<String> messages =
                java.util.Arrays.asList(null, "unsafe-control\u0000\u0001\nmessage", "sensitive-message-marker");
        return Stream.of(ExpectException.Reason.values())
                .flatMap(reason -> transcriptStates.stream().flatMap(state -> messages.stream()
                        .map(message -> Arguments.of(reason, state.truncated(), state.malformed(), message))));
    }

    @Test
    void mapsRealExpectTimeoutThroughToolBoundary() {
        ToolCallResult<Void> result = realExpectFailure("expect-timeout", Duration.ofMillis(300));

        assertEquals("expect_timeout", result.error().orElseThrow().code());
    }

    @Test
    void mapsRealExpectEofThroughToolBoundary() {
        ToolCallResult<Void> result = realExpectFailure("expect-eof", Duration.ofSeconds(2));

        assertEquals("expect_eof", result.error().orElseThrow().code());
    }

    @Test
    void unwrapsOnlyLeadingAsyncWrappersForTypedClassification() {
        assertWrappedExpectCode(
                new CompletionException(expectFailure(ExpectException.Reason.TIMEOUT)), "expect_timeout");
        assertWrappedExpectCode(new ExecutionException(expectFailure(ExpectException.Reason.EOF)), "expect_eof");
    }

    @Test
    void doesNotReclassifyArbitraryNestedTypedCause() {
        ExpectException nested = expectFailure(ExpectException.Reason.TIMEOUT);
        IllegalStateException wrapper = new IllegalStateException("wrapper", nested);
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw wrapper;
        });

        CliAdapterError error = tool.call("payload").error().orElseThrow();

        assertEquals("adapter_failure", error.code());
        assertSame(nested, wrapper.getCause());
    }

    @Test
    void nestedErrorIsNeverConvertedToToolResult() {
        AssertionError fatal = new AssertionError("fatal");
        IllegalStateException wrapper = new IllegalStateException("wrapper", fatal);
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw wrapper;
        });

        assertSame(fatal, assertThrows(AssertionError.class, () -> tool.call("payload")));
    }

    @Test
    void nestedInterruptionMapsFailureAndRestoresInterruptFlag() {
        try {
            Thread.interrupted();
            InterruptedException interruption = new InterruptedException("interrupted");
            IllegalStateException wrapper = new IllegalStateException("wrapper", interruption);
            CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
                throw wrapper;
            });

            CliAdapterError error = tool.call("payload").error().orElseThrow();

            assertEquals("interrupted", error.code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertSame(interruption, wrapper.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void typedMappingPreservesPreexistingInterruptFlag() {
        try {
            Thread.currentThread().interrupt();
            CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
                throw expectFailure(ExpectException.Reason.CLOSED);
            });

            CliAdapterError error = tool.call("payload").error().orElseThrow();

            assertEquals("expect_closed", error.code());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static ToolCallResult<Void> realExpectFailure(String workerMode, Duration timeout) {
        CommandBackedTool<Void, Void> tool = CommandBackedTool.of(input -> {
            try (Session session = Procwright.command(ProtocolAdaptersTestWorker.command(workerMode))
                            .interactive()
                            .open();
                    Expect expect = session.expect().withTimeout(timeout).open()) {
                expect.expectText("never-produced");
                return null;
            }
        });
        return tool.call(null);
    }

    private static ExpectException expectFailure(ExpectException.Reason reason) {
        return new ExpectException(reason, new LineTranscript("sensitive", false, false), "sensitive");
    }

    private static void assertWrappedExpectCode(Exception wrapper, String expectedCode) {
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw wrapper;
        });

        assertEquals(expectedCode, tool.call("payload").error().orElseThrow().code());
    }

    private static String expectCode(ExpectException.Reason reason) {
        return switch (reason) {
            case TIMEOUT -> "expect_timeout";
            case EOF -> "expect_eof";
            case CLOSED -> "expect_closed";
            case FAILURE -> "expect_failure";
        };
    }

    private record TranscriptState(boolean truncated, boolean malformed) {}
}

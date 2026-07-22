/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandException;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamTranscript;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

final class CliAdapterErrorTest {

    private static final String SECRET = "sensitive-payload-marker";

    @ParameterizedTest
    @MethodSource("commandResults")
    void mapsCommandResultsWithStableMetadataAndWithoutOutput(boolean timedOut, String expectedCode) {
        CommandResult result = commandResult(timedOut);

        CliAdapterError error = CliAdapterError.from(result);

        assertEquals(expectedCode, error.code());
        assertCommandResultDetails(error, timedOut);
        assertSensitiveDataAbsent(error);
    }

    private static Stream<Arguments> commandResults() {
        return Stream.of(Arguments.of(false, "command_failed"), Arguments.of(true, "command_timeout"));
    }

    @Test
    void omitsAbsentCommandExitCodeFromExactDetails() {
        byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);
        CommandResult result = new CommandResult(
                OptionalInt.empty(), secret, secret, SECRET, SECRET, false, true, false, Duration.ZERO);

        CliAdapterError error = CliAdapterError.from(result);

        assertDetails(
                error,
                Map.of(
                        "timedOut", JsonValue.bool(false),
                        "stdoutTruncated", JsonValue.bool(false),
                        "stderrTruncated", JsonValue.bool(true)));
        assertSensitiveDataAbsent(error);
    }

    @Test
    void mapsCommandExceptionThroughItsResult() {
        CliAdapterError error = CliAdapterError.from(new CommandException(commandResult(true)));

        assertEquals("command_timeout", error.code());
        assertCommandResultDetails(error, true);
        assertSensitiveDataAbsent(error);
    }

    @ParameterizedTest
    @EnumSource(CommandExecutionException.Reason.class)
    void mapsEveryCommandExecutionReason(CommandExecutionException.Reason reason) {
        CommandExecutionException failure = reason == CommandExecutionException.Reason.DECODE_ERROR
                ? new CommandExecutionException(reason, SECRET, new IllegalStateException(SECRET), commandResult(false))
                : new CommandExecutionException(reason, SECRET, new IllegalStateException(SECRET));

        CliAdapterError error = CliAdapterError.from(failure);

        assertEquals(commandExecutionCode(reason), error.code());
        Map<String, JsonValue> expectedDetails;
        if (reason == CommandExecutionException.Reason.DECODE_ERROR) {
            expectedDetails = Map.of(
                    "reason", JsonValue.string(reason.name()),
                    "exitCode", JsonValue.number(7),
                    "timedOut", JsonValue.bool(false),
                    "stdoutTruncated", JsonValue.bool(true),
                    "stderrTruncated", JsonValue.bool(false));
        } else {
            expectedDetails = Map.of("reason", JsonValue.string(reason.name()));
        }
        assertDetails(error, expectedDetails);
        assertSensitiveDataAbsent(error);
    }

    @ParameterizedTest
    @EnumSource(LineSessionException.Reason.class)
    void mapsEveryLineSessionReason(LineSessionException.Reason reason) {
        LineSessionException failure = new LineSessionException(
                reason, new LineTranscript(SECRET, true, true), SECRET, new IllegalStateException(SECRET));

        CliAdapterError error = CliAdapterError.from(failure);

        assertEquals(lineCode(reason), error.code());
        assertDetails(
                error,
                Map.of(
                        "reason", JsonValue.string(reason.name()),
                        "transcriptTruncated", JsonValue.bool(true),
                        "transcriptMalformed", JsonValue.bool(true)));
        assertSensitiveDataAbsent(error);
    }

    @ParameterizedTest
    @EnumSource(ExpectException.Reason.class)
    void mapsEveryExpectReasonWithoutExposingTranscriptOrCause(ExpectException.Reason reason) {
        IllegalStateException cause = new IllegalStateException(SECRET);
        ExpectException failure = new ExpectException(reason, new LineTranscript(SECRET, true, true), SECRET, cause);

        CliAdapterError error = CliAdapterError.from(failure);

        assertEquals(expectCode(reason), error.code());
        assertDetails(
                error,
                Map.of(
                        "reason", JsonValue.string(reason.name()),
                        "transcriptTruncated", JsonValue.bool(true),
                        "transcriptMalformed", JsonValue.bool(true)));
        assertSame(cause, failure.getCause());
        assertSensitiveDataAbsent(error);
    }

    @ParameterizedTest
    @EnumSource(ProtocolSessionException.Reason.class)
    void mapsEveryProtocolSessionReason(ProtocolSessionException.Reason reason) {
        ProtocolSessionException failure = new ProtocolSessionException(
                reason,
                new ProtocolTranscript(SECRET, true, true),
                OptionalInt.of(37),
                SECRET,
                new IllegalStateException(SECRET));

        CliAdapterError error = CliAdapterError.from(failure);

        assertEquals(protocolCode(reason), error.code());
        assertDetails(
                error,
                Map.of(
                        "reason", JsonValue.string(reason.name()),
                        "exitCode", JsonValue.number(37),
                        "transcriptTruncated", JsonValue.bool(true),
                        "transcriptMalformed", JsonValue.bool(true)));
        assertSensitiveDataAbsent(error);
    }

    @ParameterizedTest
    @EnumSource(StreamException.Reason.class)
    void mapsEveryStreamReason(StreamException.Reason reason) {
        StreamException failure = new StreamException(
                reason, SECRET, new StreamTranscript(SECRET, true), new IllegalStateException(SECRET));

        CliAdapterError error = CliAdapterError.from(failure);

        assertEquals(streamCode(reason), error.code());
        assertDetails(
                error,
                Map.of(
                        "reason", JsonValue.string(reason.name()),
                        "transcriptTruncated", JsonValue.bool(true)));
        assertSensitiveDataAbsent(error);
    }

    @ParameterizedTest
    @EnumSource(PooledLineSessionException.Reason.class)
    void mapsEveryPooledLineSessionReasonWithoutClearingInterrupt(PooledLineSessionException.Reason reason) {
        try {
            Thread.currentThread().interrupt();
            Throwable cause = reason == PooledLineSessionException.Reason.INTERRUPTED
                    ? new InterruptedException(SECRET)
                    : new IllegalStateException(SECRET);

            CliAdapterError error = CliAdapterError.from(new PooledLineSessionException(reason, SECRET, cause));

            assertEquals(poolCode(reason.name()), error.code());
            assertDetails(error, Map.of("reason", JsonValue.string(reason.name())));
            assertTrue(Thread.currentThread().isInterrupted());
            assertSensitiveDataAbsent(error);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(PooledProtocolSessionException.Reason.class)
    void mapsEveryPooledProtocolSessionReasonWithoutClearingInterrupt(PooledProtocolSessionException.Reason reason) {
        try {
            Thread.currentThread().interrupt();
            Throwable cause = reason == PooledProtocolSessionException.Reason.INTERRUPTED
                    ? new InterruptedException(SECRET)
                    : new IllegalStateException(SECRET);

            CliAdapterError error = CliAdapterError.from(new PooledProtocolSessionException(reason, SECRET, cause));

            assertEquals(poolCode(reason.name()), error.code());
            assertDetails(error, Map.of("reason", JsonValue.string(reason.name())));
            assertTrue(Thread.currentThread().isInterrupted());
            assertSensitiveDataAbsent(error);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void pooledLineInterruptionRestoresClearedFlagAndPreservesTypedMapping() {
        try {
            Thread.interrupted();

            CliAdapterError error = CliAdapterError.from(new PooledLineSessionException(
                    PooledLineSessionException.Reason.INTERRUPTED, SECRET, new InterruptedException(SECRET)));

            assertEquals("pool_interrupted", error.code());
            assertDetails(error, Map.of("reason", JsonValue.string("INTERRUPTED")));
            assertTrue(Thread.currentThread().isInterrupted());
            assertSensitiveDataAbsent(error);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void pooledProtocolInterruptionRestoresClearedFlagAndPreservesTypedMapping() {
        try {
            Thread.interrupted();

            CliAdapterError error = CliAdapterError.from(
                    new PooledProtocolSessionException(PooledProtocolSessionException.Reason.INTERRUPTED, SECRET));

            assertEquals("pool_interrupted", error.code());
            assertDetails(error, Map.of("reason", JsonValue.string("INTERRUPTED")));
            assertTrue(Thread.currentThread().isInterrupted());
            assertSensitiveDataAbsent(error);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unwrapsCompletionAndExecutionExceptionRecursively() {
        CliAdapterError cancelled = CliAdapterError.from(
                new CompletionException(new ExecutionException(new CancellationException(SECRET))));
        CliAdapterError protocol =
                CliAdapterError.from(new ExecutionException(new CompletionException(new JsonParseException(SECRET))));

        assertEquals("cancelled", cancelled.code());
        assertEquals("protocol_error", protocol.code());
        assertSensitiveDataAbsent(cancelled);
        assertSensitiveDataAbsent(protocol);
    }

    @Test
    void mapsUnknownCyclicCauseGraphsInBoundedTimeWithoutClearingInterrupt() {
        IllegalStateException first = new IllegalStateException(SECRET);
        IllegalArgumentException second = new IllegalArgumentException(SECRET);
        first.initCause(second);
        second.initCause(first);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            try {
                Thread.currentThread().interrupt();

                CliAdapterError error = CliAdapterError.from(first);

                assertEquals("adapter_failure", error.code());
                assertEquals(Map.of(), error.details().members());
                assertTrue(Thread.currentThread().isInterrupted());
                assertSensitiveDataAbsent(error);
            } finally {
                Thread.interrupted();
            }
        });
    }

    @Test
    void wrapperTargetSelectionAcceptsExactly64PrimaryNodes() {
        Throwable current = new JsonParseException(SECRET);
        for (int index = 1; index < 64; index++) {
            current = new CompletionException(current);
        }

        CliAdapterError error = CliAdapterError.from(current);

        assertEquals("protocol_error", error.code());
        assertSensitiveDataAbsent(error);
    }

    @Test
    void wrapperTargetSelectionExhaustsAt65PrimaryNodes() {
        Throwable current = new JsonParseException(SECRET);
        for (int index = 1; index < 65; index++) {
            current = new CompletionException(current);
        }
        RuntimeException original = (RuntimeException) current;

        RuntimeException propagated = assertThrows(RuntimeException.class, () -> CliAdapterError.from(original));

        assertSame(original, propagated);
    }

    @Test
    void unknownRuntimePrimaryChainAcceptsExactly64Nodes() {
        RuntimeException original = runtimeCauseChain(64);

        CliAdapterError error = CliAdapterError.from(original);

        assertEquals("adapter_failure", error.code());
        assertSensitiveDataAbsent(error);
    }

    @Test
    void unknownRuntimePrimaryChainExhaustsAt65Nodes() {
        RuntimeException original = runtimeCauseChain(65);

        RuntimeException propagated = assertThrows(RuntimeException.class, () -> CliAdapterError.from(original));

        assertSame(original, propagated);
    }

    @Test
    void unknownCheckedPrimaryChainAcceptsExactly64Nodes() {
        Exception original = checkedCauseChain(64);

        CliAdapterError error = CliAdapterError.from(original);

        assertEquals("adapter_failure", error.code());
        assertSensitiveDataAbsent(error);
    }

    @Test
    void unknownCheckedPrimaryChainExhaustsAt65Nodes() {
        Exception original = checkedCauseChain(65);

        IllegalStateException propagated = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> assertThrows(IllegalStateException.class, () -> CliAdapterError.from(original)));

        assertSame(original, propagated.getCause());
        assertFalse(propagated.getMessage().contains(SECRET));
    }

    @Test
    void suppressedFanOutDoesNotExhaustPrimaryCauseInspection() {
        IllegalStateException primary = new IllegalStateException(SECRET);
        addSuppressedFanOut(primary, 96);

        CliAdapterError error = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CliAdapterError.from(primary));

        assertEquals("adapter_failure", error.code());
        assertSensitiveDataAbsent(error);
    }

    @Test
    void suppressedHazardsDoNotOverrideStablePrimaryMappingOrChangeInterruptFlag() {
        try {
            Thread.interrupted();
            JsonParseException primary = new JsonParseException(SECRET);
            primary.addSuppressed(new AssertionError(SECRET));
            primary.addSuppressed(new InterruptedException(SECRET));
            addSuppressedFanOut(primary, 96);

            CliAdapterError error =
                    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CliAdapterError.from(primary));

            assertEquals("protocol_error", error.code());
            assertFalse(Thread.currentThread().isInterrupted());
            assertSensitiveDataAbsent(error);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rethrowsDirectAndWrappedErrorsThatWouldOtherwiseBeMapped() {
        AssertionError direct = new AssertionError(SECRET);
        AssertionError wrapped = new AssertionError(SECRET);

        assertSame(direct, assertThrows(AssertionError.class, () -> CliAdapterError.from(direct)));
        assertSame(
                wrapped,
                assertThrows(AssertionError.class, () -> CliAdapterError.from(new CompletionException(wrapped))));
    }

    @Test
    void rethrowsDeepErrorFromInspectedCauseGraph() {
        AssertionError error = new AssertionError(SECRET);
        Throwable root = causeChain(32, error);

        assertSame(error, assertThrows(AssertionError.class, () -> CliAdapterError.from(root)));
    }

    @Test
    void rethrowsErrorFromCyclicCauseGraph() {
        IllegalStateException root = new IllegalStateException(SECRET);
        AssertionError error = new AssertionError(SECRET);
        root.initCause(error);
        error.initCause(root);

        assertSame(
                error,
                assertTimeoutPreemptively(
                        Duration.ofSeconds(1),
                        () -> assertThrows(AssertionError.class, () -> CliAdapterError.from(root))));
    }

    @Test
    void errorWinsOverInterruptionButInterruptionFlagIsStillRestored() {
        try {
            Thread.interrupted();
            InterruptedException interruption = new InterruptedException(SECRET);
            AssertionError error = new AssertionError(SECRET);
            error.initCause(interruption);

            AssertionError propagated =
                    assertThrows(AssertionError.class, () -> CliAdapterError.from(new CompletionException(error)));

            assertSame(error, propagated);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void knownTypedFailureRetainsItsTaxonomyAcrossCyclicCauseGraph() {
        IllegalStateException first = new IllegalStateException(SECRET);
        IllegalArgumentException second = new IllegalArgumentException(SECRET);
        first.initCause(second);
        second.initCause(first);
        ProtocolSessionException failure = new ProtocolSessionException(
                ProtocolSessionException.Reason.DECODE_ERROR,
                new ProtocolTranscript(SECRET, true, false),
                OptionalInt.of(9),
                SECRET,
                first);

        CliAdapterError error = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CliAdapterError.from(failure));

        assertEquals("protocol_decode_error", error.code());
        assertDetails(
                error,
                Map.of(
                        "reason", JsonValue.string("DECODE_ERROR"),
                        "exitCode", JsonValue.number(9),
                        "transcriptTruncated", JsonValue.bool(true),
                        "transcriptMalformed", JsonValue.bool(false)));
        assertSensitiveDataAbsent(error);
    }

    @Test
    void knownTypedFailureRetainsItsTaxonomyAtAndBeyondPrimaryCauseBoundary() {
        for (int totalNodes : new int[] {64, 65}) {
            ProtocolSessionException failure = new ProtocolSessionException(
                    ProtocolSessionException.Reason.CLOSED,
                    new ProtocolTranscript(SECRET, false, false),
                    SECRET,
                    runtimeCauseChain(totalNodes - 1));

            CliAdapterError error = CliAdapterError.from(failure);

            assertEquals("protocol_closed", error.code(), "totalNodes=" + totalNodes);
            assertDetails(
                    error,
                    Map.of(
                            "reason", JsonValue.string("CLOSED"),
                            "transcriptTruncated", JsonValue.bool(false),
                            "transcriptMalformed", JsonValue.bool(false)));
            assertSensitiveDataAbsent(error);
        }
    }

    @ParameterizedTest
    @MethodSource("interruptibleFailures")
    void relevantInterruptionRestoresClearedFlagAndTakesPrecedenceOverCoreTaxonomy(Throwable failure) {
        try {
            Thread.interrupted();

            CliAdapterError error = CliAdapterError.from(failure);

            assertEquals("interrupted", error.code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertSensitiveDataAbsent(error);
        } finally {
            Thread.interrupted();
        }
    }

    private static Stream<Throwable> interruptibleFailures() {
        InterruptedException interruption = new InterruptedException(SECRET);
        InterruptedException wrappedInterruption = new InterruptedException(SECRET);
        InterruptedException deepInterruption = new InterruptedException(SECRET);
        InterruptedException cyclicInterruption = new InterruptedException(SECRET);
        IllegalStateException cyclicRoot = new IllegalStateException(SECRET);
        cyclicRoot.initCause(cyclicInterruption);
        cyclicInterruption.initCause(cyclicRoot);
        return Stream.of(
                interruption,
                new CompletionException(new ExecutionException(wrappedInterruption)),
                causeChain(32, deepInterruption),
                cyclicRoot,
                new CommandExecutionException(SECRET, interruption),
                new LineSessionException(
                        LineSessionException.Reason.FAILURE,
                        new LineTranscript(SECRET, true, true),
                        SECRET,
                        interruption),
                new ProtocolSessionException(
                        ProtocolSessionException.Reason.FAILURE,
                        new ProtocolTranscript(SECRET, true, true),
                        SECRET,
                        interruption),
                new StreamException(
                        StreamException.Reason.PROCESS_FAILED,
                        SECRET,
                        new StreamTranscript(SECRET, true),
                        interruption));
    }

    @Test
    void cancellationTakesPrecedenceOverUnrelatedInterruptFlag() {
        try {
            Thread.currentThread().interrupt();

            CliAdapterError error = CliAdapterError.from(new CancellationException(SECRET));

            assertEquals("cancelled", error.code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertSensitiveDataAbsent(error);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void mapsFramingFailuresWithReasonWithoutFailurePayload() {
        CliAdapterError error = CliAdapterError.from(
                new IntegrationProtocolException(IntegrationProtocolException.Reason.OVERSIZED_FRAME, SECRET));

        assertEquals("protocol_error", error.code());
        assertReason(error, "OVERSIZED_FRAME");
        assertSensitiveDataAbsent(error);
    }

    @Test
    void mapsUnknownFailureWithoutMessageOrCausePayload() {
        CliAdapterError error = CliAdapterError.from(new IllegalStateException(SECRET));

        assertEquals("adapter_failure", error.code());
        assertSensitiveDataAbsent(error);
    }

    private static Throwable causeChain(int length, Throwable terminal) {
        Throwable current = terminal;
        for (int index = 0; index < length; index++) {
            current = new IllegalStateException(SECRET, current);
        }
        return current;
    }

    private static RuntimeException runtimeCauseChain(int nodeCount) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("nodeCount must be positive");
        }
        RuntimeException current = new IllegalStateException(SECRET);
        for (int index = 1; index < nodeCount; index++) {
            current = new IllegalStateException(SECRET, current);
        }
        return current;
    }

    private static Exception checkedCauseChain(int nodeCount) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("nodeCount must be positive");
        }
        return nodeCount == 1 ? new Exception(SECRET) : new Exception(SECRET, runtimeCauseChain(nodeCount - 1));
    }

    private static void addSuppressedFanOut(Throwable primary, int count) {
        IllegalStateException cycleStart = new IllegalStateException(SECRET);
        IllegalArgumentException cycleEnd = new IllegalArgumentException(SECRET);
        cycleStart.addSuppressed(cycleEnd);
        cycleEnd.addSuppressed(cycleStart);
        primary.addSuppressed(cycleStart);
        for (int index = 1; index < count; index++) {
            primary.addSuppressed(new IllegalStateException(SECRET));
        }
    }

    @ParameterizedTest
    @MethodSource("lineTranscriptMetadata")
    void mapsExactLineTranscriptMetadata(boolean truncated, boolean malformed) {
        CliAdapterError error = CliAdapterError.from(new LineSessionException(
                LineSessionException.Reason.FAILURE, new LineTranscript(SECRET, truncated, malformed), SECRET));

        assertDetails(
                error,
                Map.of(
                        "reason", JsonValue.string("FAILURE"),
                        "transcriptTruncated", JsonValue.bool(truncated),
                        "transcriptMalformed", JsonValue.bool(malformed)));
        assertSensitiveDataAbsent(error);
    }

    private static Stream<Arguments> lineTranscriptMetadata() {
        return Stream.of(Arguments.of(false, false), Arguments.of(true, true));
    }

    @ParameterizedTest
    @MethodSource("protocolTranscriptMetadata")
    void mapsExactProtocolTranscriptAndExitMetadata(
            boolean truncated, boolean malformed, OptionalInt exitCode, Map<String, JsonValue> expectedDetails) {
        CliAdapterError error = CliAdapterError.from(new ProtocolSessionException(
                ProtocolSessionException.Reason.FAILURE,
                new ProtocolTranscript(SECRET, truncated, malformed),
                exitCode,
                SECRET,
                null));

        assertDetails(error, expectedDetails);
        assertSensitiveDataAbsent(error);
    }

    private static Stream<Arguments> protocolTranscriptMetadata() {
        return Stream.of(
                Arguments.of(
                        false,
                        false,
                        OptionalInt.empty(),
                        Map.of(
                                "reason", JsonValue.string("FAILURE"),
                                "transcriptTruncated", JsonValue.bool(false),
                                "transcriptMalformed", JsonValue.bool(false))),
                Arguments.of(
                        true,
                        true,
                        OptionalInt.of(37),
                        Map.of(
                                "reason", JsonValue.string("FAILURE"),
                                "exitCode", JsonValue.number(37),
                                "transcriptTruncated", JsonValue.bool(true),
                                "transcriptMalformed", JsonValue.bool(true))));
    }

    @ParameterizedTest
    @MethodSource("streamTranscriptMetadata")
    void mapsExactStreamTranscriptMetadata(boolean truncated) {
        CliAdapterError error = CliAdapterError.from(new StreamException(
                StreamException.Reason.PROCESS_FAILED, SECRET, new StreamTranscript(SECRET, truncated), null));

        assertDetails(
                error,
                Map.of(
                        "reason", JsonValue.string("PROCESS_FAILED"),
                        "transcriptTruncated", JsonValue.bool(truncated)));
        assertSensitiveDataAbsent(error);
    }

    private static Stream<Arguments> streamTranscriptMetadata() {
        return Stream.of(Arguments.of(false), Arguments.of(true));
    }

    @Test
    void toolCallResultCarriesSuccessOrStructuredFailure() {
        ToolCallResult<String> success = ToolCallResult.success("ok");
        CliAdapterError adapterError = CliAdapterError.from(new JsonParseException(SECRET));
        ToolCallResult<String> failure = ToolCallResult.failure(adapterError);

        assertEquals("ok", success.value().orElseThrow());
        assertFalse(failure.succeeded());
        assertEquals("protocol_error", failure.error().orElseThrow().code());
        assertSensitiveDataAbsent(failure.error().orElseThrow());
    }

    private static CommandResult commandResult(boolean timedOut) {
        byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);
        return new CommandResult(
                OptionalInt.of(7), secret, secret, SECRET, SECRET, true, false, timedOut, Duration.ofMillis(12));
    }

    private static void assertCommandResultDetails(CliAdapterError error, boolean timedOut) {
        assertDetails(
                error,
                Map.of(
                        "exitCode", JsonValue.number(7),
                        "timedOut", JsonValue.bool(timedOut),
                        "stdoutTruncated", JsonValue.bool(true),
                        "stderrTruncated", JsonValue.bool(false)));
    }

    private static void assertReason(CliAdapterError error, String reason) {
        assertEquals(JsonValue.string(reason), error.details().member("reason").orElseThrow());
    }

    private static void assertDetails(CliAdapterError error, Map<String, JsonValue> expected) {
        assertEquals(expected, error.details().members());
    }

    private static void assertSensitiveDataAbsent(CliAdapterError error) {
        assertFalse(JsonCodec.write(error.toJson()).contains(SECRET));
    }

    private static String commandExecutionCode(CommandExecutionException.Reason reason) {
        return switch (reason) {
            case LAUNCH_FAILED -> "command_launch_failed";
            case DECODE_ERROR -> "command_decode_error";
            case READINESS_TIMEOUT -> "readiness_timeout";
            case READINESS_FAILED -> "readiness_failed";
            case RUNTIME_FAILURE -> "command_runtime_failure";
        };
    }

    private static String lineCode(LineSessionException.Reason reason) {
        return switch (reason) {
            case REQUEST_TOO_LARGE -> "line_request_too_large";
            case TIMEOUT -> "line_timeout";
            case EOF -> "line_eof";
            case CLOSED -> "line_closed";
            case BROKEN_PIPE -> "line_broken_pipe";
            case DECODE_ERROR -> "line_decode_error";
            case RESPONSE_TOO_LARGE -> "line_response_too_large";
            case STDOUT_BACKLOG_OVERFLOW -> "line_stdout_backlog_overflow";
            case PROCESS_EXITED -> "line_process_exited";
            case DECODER_FAILED -> "line_decoder_failed";
            case FAILURE -> "line_failure";
        };
    }

    private static String expectCode(ExpectException.Reason reason) {
        return switch (reason) {
            case TIMEOUT -> "expect_timeout";
            case EOF -> "expect_eof";
            case CLOSED -> "expect_closed";
            case FAILURE -> "expect_failure";
        };
    }

    private static String protocolCode(ProtocolSessionException.Reason reason) {
        return switch (reason) {
            case TIMEOUT -> "protocol_timeout";
            case CLOSED -> "protocol_closed";
            case EOF -> "protocol_eof";
            case BROKEN_PIPE -> "protocol_broken_pipe";
            case DECODE_ERROR -> "protocol_decode_error";
            case REQUEST_TOO_LARGE -> "protocol_request_too_large";
            case RESPONSE_TOO_LARGE -> "protocol_response_too_large";
            case OUTPUT_BACKLOG_OVERFLOW -> "protocol_output_backlog_overflow";
            case PROTOCOL_DECODER_FAILED -> "protocol_decoder_failed";
            case PROCESS_EXITED -> "protocol_process_exited";
            case FAILURE -> "protocol_failure";
        };
    }

    private static String streamCode(StreamException.Reason reason) {
        return switch (reason) {
            case LISTENER_FAILED -> "stream_listener_failed";
            case OUTPUT_READ_FAILED -> "stream_output_read_failed";
            case PROCESS_FAILED -> "stream_process_failed";
        };
    }

    private static String poolCode(String reason) {
        return switch (reason) {
            case "ACQUIRE_TIMEOUT" -> "pool_acquire_timeout";
            case "CLOSED" -> "pool_closed";
            case "STARTUP_FAILED" -> "pool_startup_failed";
            case "HOOK_TIMEOUT" -> "pool_hook_timeout";
            case "INTERRUPTED" -> "pool_interrupted";
            case "DRAIN_TIMEOUT" -> "pool_drain_timeout";
            case "WORKER_FAILED" -> "pool_worker_failed";
            default -> throw new AssertionError("Unexpected pool reason: " + reason);
        };
    }
}

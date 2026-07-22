/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.command.CommandException;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.StreamException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Structured, redaction-friendly error for CLI-backed adapters.
 *
 * @param code stable machine-readable error code
 * @param message human-readable message without raw command payloads
 * @param details structured metadata
 */
public record CliAdapterError(String code, String message, JsonValue.JsonObject details) {

    // Legitimate wrapper chains are shallow; this bounds hostile primary-cause chains.
    private static final int MAX_THROWABLE_INSPECTION_NODES = 64;

    /**
     * Creates an adapter error.
     *
     * @param code stable machine-readable error code
     * @param message human-readable message without raw command payloads
     * @param details structured metadata
     */
    public CliAdapterError {
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(details, "details");
    }

    /**
     * Creates an error without details.
     *
     * @param code stable error code
     * @param message redaction-friendly message
     * @return adapter error
     */
    public static CliAdapterError of(String code, String message) {
        return new CliAdapterError(code, message, JsonValue.object(Map.of()));
    }

    /**
     * Maps an unsuccessful one-shot command result to a structured adapter error.
     *
     * @param result command result
     * @return adapter error
     */
    public static CliAdapterError from(CommandResult result) {
        Objects.requireNonNull(result, "result");
        LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
        putCommandResultDetails(details, result);
        if (result.timedOut()) {
            return new CliAdapterError("command_timeout", "Command timed out", JsonValue.object(details));
        }
        return new CliAdapterError("command_failed", "Command exited unsuccessfully", JsonValue.object(details));
    }

    /**
     * Maps a throwable to a structured adapter error.
     *
     * <p>Typed classification unwraps only leading {@link CompletionException} and {@link ExecutionException}
     * instances. It does not search arbitrary nested causes or messages for a typed failure. A separate identity-aware
     * scan follows the primary cause chain, up to 64 throwable objects, only to preserve {@link Error} precedence and
     * interruption state. Suppressed failures remain attached to the original throwable but do not affect
     * classification or hazards.
     *
     * <p>An {@link Error} discovered in the inspected primary chain is rethrown. If an unknown primary chain exceeds
     * the bound, an original {@link RuntimeException} is rethrown unchanged; an original checked exception is wrapped
     * in an {@link IllegalStateException} whose cause is that exact exception.
     *
     * @param throwable failure
     * @return adapter error
     * @throws Error when the inspected primary cause chain contains an error
     * @throws RuntimeException when bounded classification is exhausted for a runtime exception
     * @throws IllegalStateException when bounded classification is exhausted for a checked exception
     */
    public static CliAdapterError from(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        TargetSelection selection = selectTarget(throwable);
        HazardScan hazards = scanHazards(throwable);
        Throwable failure = selection.target();
        boolean pooledInterruption = isPooledInterruption(failure);
        if (hazards.interrupted() || pooledInterruption) {
            Thread.currentThread().interrupt();
        }
        if (failure instanceof Error selectedError) {
            throw selectedError;
        }
        if (hazards.error() != null) {
            throw hazards.error();
        }
        if (selection.exhausted() || hazards.exhausted() && !hasStableTaxonomy(failure)) {
            return propagate(throwable);
        }
        if (hazards.interrupted() && !pooledInterruption) {
            return of("interrupted", "Caller thread was interrupted");
        }
        if (failure instanceof CommandException commandException) {
            return from(commandException.result());
        }
        if (failure instanceof CommandExecutionException commandExecutionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put(
                    "reason",
                    JsonValue.string(commandExecutionException.reason().name()));
            commandExecutionException.result().ifPresent(result -> putCommandResultDetails(details, result));
            return new CliAdapterError(
                    switch (commandExecutionException.reason()) {
                        case LAUNCH_FAILED -> "command_launch_failed";
                        case DECODE_ERROR -> "command_decode_error";
                        case READINESS_TIMEOUT -> "readiness_timeout";
                        case READINESS_FAILED -> "readiness_failed";
                        case RUNTIME_FAILURE -> "command_runtime_failure";
                    },
                    "Command execution failed",
                    JsonValue.object(details));
        }
        if (failure instanceof LineSessionException lineSessionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put("reason", JsonValue.string(lineSessionException.reason().name()));
            details.put(
                    "transcriptTruncated",
                    JsonValue.bool(lineSessionException.transcript().truncated()));
            details.put(
                    "transcriptMalformed",
                    JsonValue.bool(lineSessionException.transcript().malformed()));
            return new CliAdapterError(
                    switch (lineSessionException.reason()) {
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
                    },
                    "Line-session request failed",
                    JsonValue.object(details));
        }
        if (failure instanceof ExpectException expectException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put("reason", JsonValue.string(expectException.reason().name()));
            details.put(
                    "transcriptTruncated",
                    JsonValue.bool(expectException.transcript().truncated()));
            details.put(
                    "transcriptMalformed",
                    JsonValue.bool(expectException.transcript().malformed()));
            return new CliAdapterError(
                    switch (expectException.reason()) {
                        case TIMEOUT -> "expect_timeout";
                        case EOF -> "expect_eof";
                        case CLOSED -> "expect_closed";
                        case FAILURE -> "expect_failure";
                    },
                    "Expect operation failed",
                    JsonValue.object(details));
        }
        if (failure instanceof ProtocolSessionException protocolSessionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put(
                    "reason", JsonValue.string(protocolSessionException.reason().name()));
            putExitCode(details, protocolSessionException.exitCode());
            details.put(
                    "transcriptTruncated",
                    JsonValue.bool(protocolSessionException.transcript().truncated()));
            details.put(
                    "transcriptMalformed",
                    JsonValue.bool(protocolSessionException.transcript().malformed()));
            return new CliAdapterError(
                    switch (protocolSessionException.reason()) {
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
                    },
                    "Protocol-session request failed",
                    JsonValue.object(details));
        }
        if (failure instanceof StreamException streamException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put("reason", JsonValue.string(streamException.reason().name()));
            details.put(
                    "transcriptTruncated",
                    JsonValue.bool(streamException.diagnostics().truncated()));
            return new CliAdapterError(
                    switch (streamException.reason()) {
                        case LISTENER_FAILED -> "stream_listener_failed";
                        case OUTPUT_READ_FAILED -> "stream_output_read_failed";
                        case PROCESS_FAILED -> "stream_process_failed";
                    },
                    "Streaming session failed",
                    JsonValue.object(details));
        }
        if (failure instanceof PooledLineSessionException pooledLineSessionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put(
                    "reason",
                    JsonValue.string(pooledLineSessionException.reason().name()));
            return new CliAdapterError(
                    switch (pooledLineSessionException.reason()) {
                        case ACQUIRE_TIMEOUT -> "pool_acquire_timeout";
                        case CLOSED -> "pool_closed";
                        case STARTUP_FAILED -> "pool_startup_failed";
                        case HOOK_TIMEOUT -> "pool_hook_timeout";
                        case INTERRUPTED -> "pool_interrupted";
                        case DRAIN_TIMEOUT -> "pool_drain_timeout";
                        case WORKER_FAILED -> "pool_worker_failed";
                    },
                    "Pooled line-session request failed",
                    JsonValue.object(details));
        }
        if (failure instanceof PooledProtocolSessionException pooledProtocolSessionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put(
                    "reason",
                    JsonValue.string(pooledProtocolSessionException.reason().name()));
            return new CliAdapterError(
                    switch (pooledProtocolSessionException.reason()) {
                        case ACQUIRE_TIMEOUT -> "pool_acquire_timeout";
                        case CLOSED -> "pool_closed";
                        case STARTUP_FAILED -> "pool_startup_failed";
                        case HOOK_TIMEOUT -> "pool_hook_timeout";
                        case INTERRUPTED -> "pool_interrupted";
                        case DRAIN_TIMEOUT -> "pool_drain_timeout";
                        case WORKER_FAILED -> "pool_worker_failed";
                    },
                    "Pooled protocol-session request failed",
                    JsonValue.object(details));
        }
        if (failure instanceof CancellationException) {
            return of("cancelled", "Call was cancelled");
        }
        if (failure instanceof JsonParseException) {
            return of("protocol_error", "CLI produced invalid JSON");
        }
        if (failure instanceof IntegrationProtocolException protocolException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put("reason", JsonValue.string(protocolException.reason().name()));
            return new CliAdapterError("protocol_error", "CLI protocol framing failed", JsonValue.object(details));
        }
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return of("interrupted", "Caller thread was interrupted");
        }
        return of("adapter_failure", "CLI adapter failed");
    }

    /**
     * Converts this error to a JSON object.
     *
     * @return JSON representation
     */
    public JsonValue.JsonObject toJson() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("code", JsonValue.string(code));
        members.put("message", JsonValue.string(message));
        members.put("details", details);
        return JsonValue.object(members);
    }

    private static void putExitCode(LinkedHashMap<String, JsonValue> details, OptionalInt exitCode) {
        if (exitCode.isPresent()) {
            details.put("exitCode", JsonValue.number(exitCode.orElseThrow()));
        }
    }

    private static void putCommandResultDetails(LinkedHashMap<String, JsonValue> details, CommandResult result) {
        putExitCode(details, result.exitCode());
        details.put("timedOut", JsonValue.bool(result.timedOut()));
        details.put("stdoutTruncated", JsonValue.bool(result.stdoutTruncated()));
        details.put("stderrTruncated", JsonValue.bool(result.stderrTruncated()));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static TargetSelection selectTarget(Throwable root) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = root;
        for (int inspected = 0; inspected < MAX_THROWABLE_INSPECTION_NODES; inspected++) {
            if (!visited.add(current)) {
                return new TargetSelection(current, false);
            }
            if (!isWrapper(current)) {
                return new TargetSelection(current, false);
            }
            Throwable cause = current.getCause();
            if (cause == null) {
                return new TargetSelection(current, false);
            }
            if (inspected + 1 == MAX_THROWABLE_INSPECTION_NODES) {
                return visited.contains(cause) ? new TargetSelection(cause, false) : new TargetSelection(current, true);
            }
            current = cause;
        }
        throw new AssertionError("unreachable target-selection state");
    }

    private static HazardScan scanHazards(Throwable root) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = root;
        Error error = null;
        boolean interrupted = false;
        for (int inspected = 0; inspected < MAX_THROWABLE_INSPECTION_NODES; inspected++) {
            if (!visited.add(current)) {
                return new HazardScan(error, interrupted, false);
            }
            if (error == null && current instanceof Error currentError) {
                error = currentError;
            }
            interrupted |= current instanceof InterruptedException;
            Throwable cause = current.getCause();
            if (cause == null || visited.contains(cause)) {
                return new HazardScan(error, interrupted, false);
            }
            if (inspected + 1 == MAX_THROWABLE_INSPECTION_NODES) {
                return new HazardScan(error, interrupted, true);
            }
            current = cause;
        }
        throw new AssertionError("unreachable hazard-scan state");
    }

    private static boolean isWrapper(Throwable failure) {
        return failure instanceof CompletionException || failure instanceof ExecutionException;
    }

    private static boolean isPooledInterruption(Throwable failure) {
        return failure instanceof PooledLineSessionException pooled
                        && pooled.reason() == PooledLineSessionException.Reason.INTERRUPTED
                || failure instanceof PooledProtocolSessionException protocolPooled
                        && protocolPooled.reason() == PooledProtocolSessionException.Reason.INTERRUPTED;
    }

    private static boolean hasStableTaxonomy(Throwable failure) {
        return failure instanceof CommandException
                || failure instanceof CommandExecutionException
                || failure instanceof LineSessionException
                || failure instanceof ExpectException
                || failure instanceof ProtocolSessionException
                || failure instanceof StreamException
                || failure instanceof PooledLineSessionException
                || failure instanceof PooledProtocolSessionException
                || failure instanceof CancellationException
                || failure instanceof JsonParseException
                || failure instanceof IntegrationProtocolException
                || failure instanceof InterruptedException;
    }

    private static CliAdapterError propagate(Throwable throwable) {
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException(
                "Checked failure exceeded the bounded primary-cause inspection limit", throwable);
    }

    private record TargetSelection(Throwable target, boolean exhausted) {}

    private record HazardScan(Error error, boolean interrupted, boolean exhausted) {}
}

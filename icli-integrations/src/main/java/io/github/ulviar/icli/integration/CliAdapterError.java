package io.github.ulviar.icli.integration;

import io.github.ulviar.icli.command.CommandException;
import io.github.ulviar.icli.command.CommandExecutionException;
import io.github.ulviar.icli.command.CommandResult;
import io.github.ulviar.icli.session.LineSessionException;
import io.github.ulviar.icli.session.PooledLineSessionException;
import io.github.ulviar.icli.session.PooledProtocolSessionException;
import io.github.ulviar.icli.session.ProtocolSessionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
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
        putExitCode(details, result.exitCode());
        details.put("timedOut", JsonValue.bool(result.timedOut()));
        details.put("stdoutTruncated", JsonValue.bool(result.stdoutTruncated()));
        details.put("stderrTruncated", JsonValue.bool(result.stderrTruncated()));
        if (result.timedOut()) {
            return new CliAdapterError("command_timeout", "Command timed out", JsonValue.object(details));
        }
        return new CliAdapterError("command_failed", "Command exited unsuccessfully", JsonValue.object(details));
    }

    /**
     * Maps a throwable to a structured adapter error.
     *
     * @param throwable failure
     * @return adapter error
     */
    public static CliAdapterError from(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        Throwable failure = unwrap(throwable);
        if (failure != throwable) {
            return from(failure);
        }
        if (failure instanceof CommandException commandException) {
            return from(commandException.result());
        }
        if (failure instanceof CommandExecutionException commandExecutionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put(
                    "reason",
                    JsonValue.string(commandExecutionException.reason().name()));
            return new CliAdapterError(
                    switch (commandExecutionException.reason()) {
                        case LAUNCH_FAILED -> "command_launch_failed";
                        case DECODE_ERROR -> "command_decode_error";
                        case READINESS_TIMEOUT -> "readiness_timeout";
                        case READINESS_FAILED -> "readiness_failed";
                        case RUNTIME_FAILURE -> "command_runtime_failure";
                    },
                    "Command could not be launched or supervised",
                    JsonValue.object(details));
        }
        if (failure instanceof LineSessionException lineSessionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put("reason", JsonValue.string(lineSessionException.reason().name()));
            return new CliAdapterError(
                    switch (lineSessionException.reason()) {
                        case TIMEOUT -> "line_timeout";
                        case EOF -> "line_eof";
                        case CLOSED -> "line_closed";
                        case BROKEN_PIPE -> "line_broken_pipe";
                        case DECODE_ERROR -> "line_decode_error";
                        case RESPONSE_TOO_LARGE -> "line_response_too_large";
                        case STDOUT_BACKLOG_OVERFLOW -> "line_stdout_backlog_overflow";
                        case DECODER_FAILED -> "line_decoder_failed";
                        case FAILURE -> "line_failure";
                    },
                    "Line-session request failed",
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
            details.put(
                    "transcriptRedacted",
                    JsonValue.bool(protocolSessionException.transcript().redacted()));
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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        if (throwable instanceof ExecutionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}

package com.github.ulviar.icli.integration;

import com.github.ulviar.icli.command.CommandException;
import com.github.ulviar.icli.command.CommandExecutionException;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.session.LineSessionException;
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
        if (failure instanceof CommandExecutionException) {
            return of("command_launch_failed", "Command could not be launched or supervised");
        }
        if (failure instanceof LineSessionException lineSessionException) {
            LinkedHashMap<String, JsonValue> details = new LinkedHashMap<>();
            details.put("reason", JsonValue.string(lineSessionException.reason().name()));
            return new CliAdapterError(
                    switch (lineSessionException.reason()) {
                        case TIMEOUT -> "line_timeout";
                        case EOF -> "line_eof";
                        case CLOSED -> "line_closed";
                        case FAILURE -> "line_failure";
                    },
                    "Line-session request failed",
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
